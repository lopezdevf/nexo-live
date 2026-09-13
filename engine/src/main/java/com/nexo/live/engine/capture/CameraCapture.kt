// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Nexo Live contributors

package com.nexo.live.engine.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor

/**
 * Cámara por Camera2 (incluidas las USB que el sistema expone como externas).
 * Pide la resolución más pequeña que cubre el lienzo: capturar de más solo genera calor.
 */
class CameraCapture(
    context: Context,
    private val cameraId: String,
    private val targetLongSide: Int,
    private val fps: Int,
    private val displayRotation: () -> Int,
) : SurfaceCapture {

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("NexoCamera-$cameraId").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surface: Surface? = null
    @Volatile private var stopped = false

    override fun start(texture: SurfaceTexture, listener: CaptureListener) {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            listener.onStatus(CaptureStatus.Error("Falta el permiso de cámara"))
            return
        }
        listener.onStatus(CaptureStatus.Starting)
        handler.post {
            try {
                val chars = manager.getCameraCharacteristics(cameraId)
                val size = chooseSize(chars)
                texture.setDefaultBufferSize(size.width, size.height)
                val target = Surface(texture).also { surface = it }
                listener.onFormat(CaptureFormat(size.width, size.height, rotationFor(chars)))

                @Suppress("MissingPermission")
                manager.openCamera(cameraId, executor, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (stopped) {
                            camera.close()
                            return
                        }
                        device = camera
                        createSession(camera, target, chars, listener)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        device = null
                        if (!stopped) listener.onStatus(CaptureStatus.Error("La cámara se desconectó o la usa otra app"))
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        device = null
                        listener.onStatus(CaptureStatus.Error(errorMessage(error)))
                    }
                })
            } catch (e: Exception) {
                listener.onStatus(CaptureStatus.Error("No se pudo abrir la cámara: ${e.message}"))
            }
        }
    }

    private fun createSession(camera: CameraDevice, target: Surface, chars: CameraCharacteristics, listener: CaptureListener) {
        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(OutputConfiguration(target)),
            executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (stopped) {
                        s.close()
                        return
                    }
                    session = s
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(target)
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        bestFpsRange(chars)?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                    }.build()
                    runCatching { s.setRepeatingRequest(request, null, handler) }
                        .onSuccess { listener.onStatus(CaptureStatus.Running) }
                        .onFailure { listener.onStatus(CaptureStatus.Error("La cámara rechazó la configuración")) }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    listener.onStatus(CaptureStatus.Error("La cámara no admite esta configuración"))
                }
            },
        )
        runCatching { camera.createCaptureSession(config) }
            .onFailure { listener.onStatus(CaptureStatus.Error("No se pudo iniciar la cámara: ${it.message}")) }
    }

    override fun stop() {
        stopped = true
        handler.post {
            runCatching { session?.close() }
            runCatching { device?.close() }
            surface?.release()
            session = null
            device = null
            surface = null
            thread.quitSafely()
        }
    }

    private fun chooseSize(chars: CameraCharacteristics): Size {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        if (sizes.isEmpty()) return Size(1280, 720)
        val wanted = targetLongSide.coerceIn(640, 1920)
        val wide = sizes.filter { it.width * 9 == it.height * 16 && maxOf(it.width, it.height) <= 1920 }
        val pool = wide.ifEmpty { sizes.filter { maxOf(it.width, it.height) <= 1920 }.ifEmpty { sizes } }
        return pool.filter { maxOf(it.width, it.height) >= wanted }.minByOrNull { it.width * it.height }
            ?: pool.maxBy { it.width * it.height }
    }

    private fun bestFpsRange(chars: CameraCharacteristics): Range<Int>? {
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        // Rango fijo si existe (fps estables para el codificador); si no, el que llegue a los fps pedidos
        return ranges.firstOrNull { it.lower == fps && it.upper == fps }
            ?: ranges.filter { it.upper >= fps }.maxByOrNull { it.lower }
    }

    private fun rotationFor(chars: CameraCharacteristics): Int {
        val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val device = displayRotation()
        return if (chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensor + device) % 360
        } else {
            (sensor - device + 360) % 360
        }
    }

    private fun errorMessage(error: Int) = when (error) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "La cámara está en uso por otra app"
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "Este móvil no permite abrir más cámaras a la vez"
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "La cámara está desactivada por una política del sistema"
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "Error de la cámara"
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "El servicio de cámara falló; reinicia la app"
        else -> "Error de cámara ($error)"
    }
}
