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
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Cámara por Camera2 (incluidas las USB que el sistema expone como externas).
 * Pide la resolución más pequeña que cubre el lienzo: capturar de más solo genera calor.
 */
class CameraCapture(
    context: Context,
    private val cameraId: String,
    private val targetLongSide: Int,
    private val canvasPortrait: Boolean,
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
                // La matriz de la SurfaceTexture ya gira el búfer según el sensor: el contenido llega
                // en la orientación natural del móvil, solo falta compensar si el móvil está girado
                val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                val (w, h) = if (sensor % 180 != 0) size.height to size.width else size.width to size.height
                listener.onFormat(CaptureFormat(w, h, (360 - displayRotation()) % 360))

                @Suppress("MissingPermission")
                manager.openCamera(cameraId, executor, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (stopped) {
                            camera.close()
                            return
                        }
                        device = camera
                        runCatching { createSession(camera, target, chars, listener) }
                            .onFailure { listener.onStatus(CaptureStatus.Error("No se pudo iniciar la cámara; reintentando…")) }
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

    /**
     * TEMPLATE_RECORD da exposición estable para vídeo, pero algunas cámaras (p. ej. la frontal de
     * ciertos Samsung) no lo implementan: entonces se usa TEMPLATE_PREVIEW, que es obligatorio.
     */
    private fun requestBuilder(camera: CameraDevice): CaptureRequest.Builder =
        try {
            camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        } catch (e: Exception) {
            Log.i(TAG, "Cámara $cameraId sin plantilla de grabación; se usa la de vista previa")
            camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
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
                    // Los callbacks de Camera2 corren en nuestro hilo: una excepción aquí cerraría la app entera
                    try {
                        val builder = requestBuilder(camera)
                        builder.addTarget(target)
                        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        bestFpsRange(chars)?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                        s.setRepeatingRequest(builder.build(), null, handler)
                        listener.onStatus(CaptureStatus.Running)
                    } catch (e: Exception) {
                        Log.w(TAG, "Cámara $cameraId: no se pudo iniciar la captura", e)
                        listener.onStatus(CaptureStatus.Error("La cámara rechazó la configuración; reintentando…"))
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    listener.onStatus(CaptureStatus.Error("La cámara no admite esta configuración"))
                }
            },
        )
        runCatching { camera.createCaptureSession(config) }
            .onFailure { listener.onStatus(CaptureStatus.Error("No se pudo iniciar la cámara: ${it.message}")) }
    }

    /** Espera a que la cámara se cierre: si se liberara antes la SurfaceTexture, la cámara escribiría en el vacío. */
    override fun stop() {
        stopped = true
        val closed = CountDownLatch(1)
        handler.post {
            runCatching { session?.stopRepeating() }
            runCatching { session?.close() }
            runCatching { device?.close() }
            surface?.release()
            session = null
            device = null
            surface = null
            closed.countDown()
            thread.quitSafely()
        }
        closed.await(700, TimeUnit.MILLISECONDS)
    }

    private fun chooseSize(chars: CameraCharacteristics): Size {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        if (sizes.isEmpty()) return Size(1280, 720)
        val wide = sizes.filter { it.width * 9 == it.height * 16 && maxOf(it.width, it.height) <= 1920 }
        val pool = wide.ifEmpty { sizes.filter { maxOf(it.width, it.height) <= 1920 }.ifEmpty { sizes } }

        // Si el móvil está en vertical y el lienzo en horizontal (o al revés) la imagen se recorta mucho:
        // entonces el lado corto de la cámara debe cubrir el lado largo del lienzo para no verse borrosa
        val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val naturalPortrait = sensor % 180 != 0
        val contentPortrait = naturalPortrait != (displayRotation() % 180 != 0)
        val mismatch = contentPortrait != canvasPortrait
        val wanted = targetLongSide.coerceIn(640, 1920)
        val candidates = if (mismatch) {
            pool.filter { minOf(it.width, it.height) >= minOf(wanted, 1080) }
        } else {
            pool.filter { maxOf(it.width, it.height) >= wanted }
        }
        return candidates.minByOrNull { it.width * it.height } ?: pool.maxBy { it.width * it.height }
    }

    private fun bestFpsRange(chars: CameraCharacteristics): Range<Int>? {
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        // Rango fijo si existe (fps estables para el codificador); si no, el que llegue a los fps pedidos
        return ranges.firstOrNull { it.lower == fps && it.upper == fps }
            ?: ranges.filter { it.upper >= fps }.maxByOrNull { it.lower }
    }

    private companion object {
        const val TAG = "NexoCamera"
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
