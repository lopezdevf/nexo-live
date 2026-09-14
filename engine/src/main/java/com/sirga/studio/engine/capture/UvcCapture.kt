// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sirga Studio contributors

package com.sirga.studio.engine.capture

import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.usb.Size

/**
 * Webcams y capturadoras HDMI por USB (UVC) mediante libuvc. Android pide al usuario permiso
 * para el dispositivo USB la primera vez.
 */
class UvcCapture(
    private val deviceProvider: () -> UsbDevice?,
    private val targetLongSide: Int,
) : SurfaceCapture {

    private val main = Handler(Looper.getMainLooper())
    private var helper: ICameraHelper? = null
    private var surface: Surface? = null
    private var listener: CaptureListener? = null

    override fun start(texture: SurfaceTexture, listener: CaptureListener) {
        this.listener = listener
        main.post {
            val device = deviceProvider()
            if (device == null) {
                listener.onStatus(CaptureStatus.Error("Conecta una cámara USB o capturadora (necesitas un adaptador OTG)"))
                return@post
            }
            listener.onStatus(CaptureStatus.Starting)
            surface = Surface(texture)
            helper = CameraHelper().apply {
                setStateCallback(callback)
                selectDevice(device)
            }
        }
    }

    override fun stop() {
        main.post {
            val h = helper
            surface?.let { s -> runCatching { h?.removeSurface(s) } }
            runCatching { h?.release() }
            surface?.release()
            helper = null
            surface = null
            listener = null
        }
    }

    private val callback = object : ICameraHelper.StateCallback {
        override fun onAttach(device: UsbDevice) = Unit

        override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
            val h = helper ?: return
            val size = runCatching { chooseSize(h.supportedSizeList.orEmpty()) }.getOrNull()
            if (size != null) h.openCamera(size) else h.openCamera()
        }

        override fun onCameraOpen(device: UsbDevice) {
            val h = helper ?: return
            h.startPreview()
            surface?.let { h.addSurface(it, false) }
            val preview = runCatching { h.previewSize }.getOrNull()
            listener?.onFormat(CaptureFormat(preview?.width ?: 1280, preview?.height ?: 720))
            listener?.onStatus(CaptureStatus.Running)
        }

        override fun onCameraClose(device: UsbDevice) = Unit

        override fun onDeviceClose(device: UsbDevice) = Unit

        override fun onDetach(device: UsbDevice) {
            listener?.onStatus(CaptureStatus.Error("Se desconectó la cámara USB"))
        }

        override fun onCancel(device: UsbDevice) {
            listener?.onStatus(CaptureStatus.Error("Sin permiso para usar la cámara USB"))
        }
    }

    private fun chooseSize(sizes: List<Size>): Size? {
        val usable = sizes.filter { maxOf(it.width, it.height) <= 1920 }
        val wanted = targetLongSide.coerceIn(640, 1920)
        return usable.filter { maxOf(it.width, it.height) >= wanted }.minByOrNull { it.width * it.height }
            ?: usable.maxByOrNull { it.width * it.height }
    }
}
