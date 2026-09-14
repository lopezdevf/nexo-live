// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sirga Studio contributors

package com.sirga.studio.engine.gl

import android.graphics.Bitmap
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Dibuja quads con textura externa (cámara, pantalla), textura 2D (imagen, texto, lienzo) o color.
 * Todo el canal es alfa premultiplicado, como los Bitmap de Android.
 */
class GlRenderer {

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(QUAD.size * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply { put(QUAD).position(0) }

    private val external = Program(VERTEX, FRAGMENT_EXTERNAL)
    private val texture2d = Program(VERTEX, FRAGMENT_2D)
    private val solid = Program(VERTEX, FRAGMENT_COLOR)

    init {
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
    }

    fun drawTexture(textureId: Int, isExternal: Boolean, mvp: FloatArray, texMatrix: FloatArray, alpha: Float) {
        val program = if (isExternal) external else texture2d
        val target = if (isExternal) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D
        program.use(mvp, texMatrix)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(target, textureId)
        GLES20.glUniform1i(program.uTexture, 0)
        GLES20.glUniform1f(program.uAlpha, alpha)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        program.unbind()
        GLES20.glBindTexture(target, 0)
    }

    fun drawColor(mvp: FloatArray, argb: Long, alpha: Float) {
        solid.use(mvp, IDENTITY)
        val a = ((argb ushr 24) and 0xFF) / 255f * alpha
        GLES20.glUniform4f(
            solid.uColor,
            ((argb ushr 16) and 0xFF) / 255f * a,
            ((argb ushr 8) and 0xFF) / 255f * a,
            (argb and 0xFF) / 255f * a,
            a,
        )
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        solid.unbind()
    }

    fun release() {
        external.release()
        texture2d.release()
        solid.release()
    }

    private inner class Program(vertex: String, fragment: String) {
        val id: Int = link(compile(GLES20.GL_VERTEX_SHADER, vertex), compile(GLES20.GL_FRAGMENT_SHADER, fragment))
        private val aPosition = GLES20.glGetAttribLocation(id, "aPosition")
        private val aTexCoord = GLES20.glGetAttribLocation(id, "aTexCoord")
        private val uMvp = GLES20.glGetUniformLocation(id, "uMvp")
        private val uTexMatrix = GLES20.glGetUniformLocation(id, "uTexMatrix")
        val uTexture = GLES20.glGetUniformLocation(id, "uTexture")
        val uAlpha = GLES20.glGetUniformLocation(id, "uAlpha")
        val uColor = GLES20.glGetUniformLocation(id, "uColor")

        fun use(mvp: FloatArray, texMatrix: FloatArray) {
            GLES20.glUseProgram(id)
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
            GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
            quad.position(0)
            GLES20.glEnableVertexAttribArray(aPosition)
            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, quad)
            quad.position(2)
            GLES20.glEnableVertexAttribArray(aTexCoord)
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 16, quad)
            quad.position(0)
        }

        fun unbind() {
            GLES20.glDisableVertexAttribArray(aPosition)
            GLES20.glDisableVertexAttribArray(aTexCoord)
            GLES20.glUseProgram(0)
        }

        fun release() = GLES20.glDeleteProgram(id)
    }

    companion object {
        val IDENTITY = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }

        /** Quad unidad (x, y, u, v) con el origen arriba a la izquierda. */
        private val QUAD = floatArrayOf(
            0f, 0f, 0f, 0f,
            1f, 0f, 1f, 0f,
            0f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
        )

        private const val VERTEX = """
            uniform mat4 uMvp;
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMvp * aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        private const val FRAGMENT_EXTERNAL = """#extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            uniform float uAlpha;
            void main() {
                vec4 c = texture2D(uTexture, vTexCoord);
                gl_FragColor = vec4(c.rgb * uAlpha, c.a * uAlpha);
            }
        """

        private const val FRAGMENT_2D = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            uniform float uAlpha;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord) * uAlpha;
            }
        """

        private const val FRAGMENT_COLOR = """
            precision mediump float;
            uniform vec4 uColor;
            void main() {
                gl_FragColor = uColor;
            }
        """

        fun createExternalTexture(): Int = createTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)

        fun createTexture2d(): Int = createTexture(GLES20.GL_TEXTURE_2D)

        fun uploadBitmap(textureId: Int, bitmap: Bitmap) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        }

        fun deleteTexture(textureId: Int) = GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)

        private fun createTexture(target: Int): Int {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            GLES20.glBindTexture(target, ids[0])
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glBindTexture(target, 0)
            return ids[0]
        }

        private fun compile(type: Int, code: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, code.trimIndent())
            GLES20.glCompileShader(shader)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "Shader: ${GLES20.glGetShaderInfoLog(shader)}".also { GLES20.glDeleteShader(shader) } }
            return shader
        }

        private fun link(vertex: Int, fragment: Int): Int {
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertex)
            GLES20.glAttachShader(program, fragment)
            GLES20.glLinkProgram(program)
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { "Programa GL: ${GLES20.glGetProgramInfoLog(program)}" }
            return program
        }
    }
}

/** Framebuffer con textura RGBA donde se compone el lienzo antes de repartirlo. */
class Framebuffer(val width: Int, val height: Int) {
    val textureId: Int = GlRenderer.createTexture2d()
    private val fbo: Int

    init {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        val ids = IntArray(1)
        GLES20.glGenFramebuffers(1, ids, 0)
        fbo = ids[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, textureId, 0)
        check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) { "Framebuffer incompleto" }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    fun bind() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, width, height)
    }

    fun release() {
        GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        GlRenderer.deleteTexture(textureId)
    }
}
