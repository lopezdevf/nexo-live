// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sirga Studio contributors

package com.sirga.studio.engine.gl

import android.opengl.EGL14
import android.opengl.EGL15
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.Surface

/**
 * Contexto EGL del compositor. La configuración es «recordable» para poder dibujar directamente
 * sobre la superficie de entrada de MediaCodec sin copias por CPU.
 */
class EglCore {

    val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val config: EGLConfig
    val context: EGLContext
    val glesVersion: Int
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE

    init {
        check(display != EGL14.EGL_NO_DISPLAY) { "Sin pantalla EGL" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "No se pudo iniciar EGL" }

        val es3 = chooseConfig(es3 = true, pbuffer = true) ?: chooseConfig(es3 = true, pbuffer = false)
        config = es3 ?: chooseConfig(es3 = false, pbuffer = true) ?: chooseConfig(es3 = false, pbuffer = false)
            ?: error("Sin configuración EGL compatible")
        glesVersion = if (es3 != null) 3 else 2
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, glesVersion, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "No se pudo crear el contexto GL" }
    }

    private fun chooseConfig(es3: Boolean, pbuffer: Boolean): EGLConfig? {
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, if (es3) EGL15.EGL_OPENGL_ES3_BIT else EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, if (pbuffer) EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT else EGL14.EGL_WINDOW_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        return if (EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) configs[0] else null
    }

    fun createWindowSurface(surface: Surface): EGLSurface {
        val eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "No se pudo crear la superficie EGL (${EGL14.eglGetError()})" }
        return eglSurface
    }

    /** Contexto activo sin ventana, para preparar texturas cuando no hay vista previa ni codificador. */
    fun makeOffscreenCurrent() {
        if (pbuffer == EGL14.EGL_NO_SURFACE) {
            pbuffer = EGL14.eglCreatePbufferSurface(
                display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
            )
        }
        // Sin pbuffer se usa un contexto sin superficie (EGL_KHR_surfaceless_context)
        makeCurrent(pbuffer)
    }

    fun makeCurrent(surface: EGLSurface) {
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent falló (${EGL14.eglGetError()})" }
    }

    fun swapBuffers(surface: EGLSurface): Boolean = EGL14.eglSwapBuffers(display, surface)

    fun setPresentationTime(surface: EGLSurface, nanos: Long) {
        EGLExt.eglPresentationTimeANDROID(display, surface, nanos)
    }

    fun releaseSurface(surface: EGLSurface) {
        if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
    }

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        releaseSurface(pbuffer)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
