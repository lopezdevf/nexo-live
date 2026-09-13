// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Nexo Live contributors

package com.nexo.live.engine.capture

import android.content.Context
import android.content.Intent
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Permiso de captura de pantalla y su pantalla virtual. Desde Android 14 cada permiso sirve para
 * una sola pantalla virtual, así que se mantiene viva y solo se cambia su superficie de destino.
 */
class ScreenProjection(context: Context) {

    private val appContext = context.applicationContext
    private val projectionManager = appContext.getSystemService(MediaProjectionManager::class.java)
    private val displayManager = appContext.getSystemService(DisplayManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var attached: Attachment? = null

    private val _active = MutableStateFlow(false)
    /** true mientras hay permiso vigente para capturar la pantalla. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private class Attachment(val texture: SurfaceTexture, val surface: Surface, val listener: CaptureListener, val targetLongSide: Int)

    fun createPermissionIntent(): Intent = projectionManager.createScreenCaptureIntent()

    /** Proyección vigente, también necesaria para capturar el audio interno. */
    fun currentProjection(): MediaProjection? = projection

    /** Debe llamarse con el servicio en primer plano de tipo mediaProjection ya iniciado. */
    fun onPermissionResult(resultCode: Int, data: Intent) {
        release()
        val p = projectionManager.getMediaProjection(resultCode, data) ?: return
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                attached?.listener?.onStatus(CaptureStatus.Error("La captura de pantalla se detuvo"))
                release()
            }
        }, main)
        projection = p
        _active.value = true
        attached?.let { attachNow(it) }
    }

    fun attach(texture: SurfaceTexture, listener: CaptureListener, targetLongSide: Int) = main.post {
        val attachment = Attachment(texture, Surface(texture), listener, targetLongSide)
        attached?.surface?.release()
        attached = attachment
        if (projection == null) {
            listener.onStatus(CaptureStatus.Error("Toca «Permitir captura de pantalla» para mostrar tu pantalla"))
        } else {
            attachNow(attachment)
        }
    }

    fun detach(texture: SurfaceTexture) = main.post {
        val a = attached ?: return@post
        if (a.texture !== texture) return@post
        virtualDisplay?.surface = null
        a.surface.release()
        attached = null
    }

    fun release() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { projection?.stop() }
        projection = null
        _active.value = false
        displayManager.unregisterDisplayListener(displayListener)
    }

    private fun attachNow(a: Attachment) {
        val p = projection ?: return
        val (w, h, dpi) = captureSize(a.targetLongSide)
        a.texture.setDefaultBufferSize(w, h)
        val vd = virtualDisplay
        if (vd == null) {
            virtualDisplay = p.createVirtualDisplay(
                "NexoLiveScreen", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, a.surface, null, main,
            )
            displayManager.registerDisplayListener(displayListener, main)
        } else {
            vd.resize(w, h, dpi)
            vd.surface = a.surface
        }
        a.listener.onFormat(CaptureFormat(w, h))
        a.listener.onStatus(CaptureStatus.Running)
    }

    /** Al girar el móvil (p. ej. al abrir un juego en horizontal) la pantalla virtual se adapta. */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val a = attached ?: return
            val vd = virtualDisplay ?: return
            val (w, h, dpi) = captureSize(a.targetLongSide)
            a.texture.setDefaultBufferSize(w, h)
            vd.resize(w, h, dpi)
            a.listener.onFormat(CaptureFormat(w, h))
        }

        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
    }

    private fun captureSize(targetLongSide: Int): Triple<Int, Int, Int> {
        val wm = appContext.getSystemService(WindowManager::class.java)
        val (width, height) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.maximumWindowMetrics.bounds.let { it.width() to it.height() }
        } else {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
        val dpi = appContext.resources.displayMetrics.densityDpi
        val longSide = maxOf(width, height)
        val scale = minOf(1f, targetLongSide.coerceIn(640, 1920).toFloat() / longSide)
        // Dimensiones pares: algunos codificadores rechazan impares
        val w = ((width * scale).toInt() and 1.inv()).coerceAtLeast(2)
        val h = ((height * scale).toInt() and 1.inv()).coerceAtLeast(2)
        return Triple(w, h, dpi)
    }
}

/** Fuente de pantalla: se engancha a la proyección compartida. */
class ScreenCapture(private val projection: ScreenProjection, private val targetLongSide: Int) : SurfaceCapture {
    private var texture: SurfaceTexture? = null

    override fun start(texture: SurfaceTexture, listener: CaptureListener) {
        this.texture = texture
        listener.onStatus(CaptureStatus.Starting)
        projection.attach(texture, listener, targetLongSide)
    }

    override fun stop() {
        texture?.let { projection.detach(it) }
        texture = null
    }
}
