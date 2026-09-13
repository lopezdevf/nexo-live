// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Nexo Live contributors

package com.nexo.live.engine.render

import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.nexo.live.engine.capture.CaptureStatus
import com.nexo.live.engine.capture.SurfaceCapture
import com.nexo.live.engine.gl.EglCore
import com.nexo.live.engine.gl.Framebuffer
import com.nexo.live.engine.gl.GlRenderer
import com.nexo.live.engine.model.CanvasConfig
import com.nexo.live.engine.model.Scene
import com.nexo.live.engine.model.Source
import com.nexo.live.engine.settings.StudioSettings
import com.nexo.live.engine.settings.TransitionType
import com.nexo.live.engine.studio.StudioController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors

/** Superficies de vista previa: la de edición y, en modo estudio, la del programa. */
enum class PreviewSlot { Edit, Program }

fun interface CaptureFactory {
    /** null si la fuente no captura de un dispositivo (imagen, texto, color). */
    fun create(source: Source, targetLongSide: Int, fps: Int): SurfaceCapture?
}

/**
 * Compone las escenas con OpenGL en su propio hilo y reparte el lienzo al codificador y a la vista
 * previa. Solo mantiene abiertas las fuentes de las escenas que se están mostrando: una cámara
 * que no se ve no debe gastar batería ni calentar el móvil.
 */
class Compositor(
    private val studio: StudioController,
    private val settings: StateFlow<StudioSettings>,
    private val captureFactory: CaptureFactory,
) {
    private val thread = HandlerThread("NexoCompositor", Process.THREAD_PRIORITY_DISPLAY)
    private lateinit var handler: Handler
    private val bitmapWorker = Executors.newSingleThreadExecutor { Thread(it, "NexoBitmaps").apply { priority = Thread.MIN_PRIORITY } }

    private var egl: EglCore? = null
    private var gl: GlRenderer? = null
    private var programFb: Framebuffer? = null
    private var previewFb: Framebuffer? = null
    private var encoder: Target? = null
    private val previews = HashMap<PreviewSlot, Target>()
    private val renderers = HashMap<String, SourceRenderer>()
    private val lastUsed = HashMap<String, Long>()
    private val projection = FloatArray(16)
    private val matrices = Matrices()
    private var running = false

    private var lastOutputNanos = 0L
    private var lastPreviewNanos = 0L
    private var lastProgramPreviewNanos = 0L
    private var idleSinceMillis = 0L

    private var shownProgramId: String? = null
    private var fromSceneId: String? = null
    private var transitionStartNanos = 0L

    private var fpsWindowStart = 0L
    private var fpsFrames = 0

    /** Límites dinámicos que aplica el control térmico. */
    @Volatile var outputFps = 30
    @Volatile var previewFps = 30
    @Volatile var resolutionScale = 1f
    @Volatile var singleRender = false

    private val _sourceStatus = MutableStateFlow<Map<String, CaptureStatus>>(emptyMap())
    val sourceStatus: StateFlow<Map<String, CaptureStatus>> = _sourceStatus.asStateFlow()

    private val _renderFps = MutableStateFlow(0f)
    val renderFps: StateFlow<Float> = _renderFps.asStateFlow()

    private class Target(val surface: Surface, val eglSurface: EGLSurface, val width: Int, val height: Int)

    fun start() {
        thread.start()
        handler = Handler(thread.looper)
        handler.post {
            try {
                egl = EglCore().also { it.makeOffscreenCurrent() }
                gl = GlRenderer()
                running = true
                handler.post(tick)
            } catch (e: Exception) {
                Log.e(TAG, "No se pudo iniciar OpenGL", e)
            }
        }
    }

    fun release() {
        if (!::handler.isInitialized) return
        handler.post {
            running = false
            handler.removeCallbacks(tick)
            releaseRenderers()
            previews.values.forEach { egl?.releaseSurface(it.eglSurface) }
            previews.clear()
            encoder?.let { egl?.releaseSurface(it.eglSurface) }
            encoder = null
            programFb?.release()
            previewFb?.release()
            gl?.release()
            egl?.release()
            egl = null
            thread.quitSafely()
        }
        bitmapWorker.shutdown()
    }

    /** [surface] null quita la vista previa (p. ej. la app pasa a segundo plano). */
    fun setPreviewSurface(slot: PreviewSlot, surface: Surface?, width: Int, height: Int) = post {
        previews.remove(slot)?.let { egl?.releaseSurface(it.eglSurface) }
        if (surface != null && surface.isValid) {
            egl?.let { previews[slot] = Target(surface, it.createWindowSurface(surface), width, height) }
        }
    }

    /** Superficie de entrada del codificador de vídeo; null al detener la salida. */
    fun setEncoderSurface(surface: Surface?, width: Int, height: Int) = post {
        encoder?.let { egl?.releaseSurface(it.eglSurface) }
        encoder = null
        if (surface != null) {
            egl?.let { encoder = Target(surface, it.createWindowSurface(surface), width, height) }
        }
    }

    private fun post(block: () -> Unit) {
        if (::handler.isInitialized) handler.post(block)
    }

    // ---- Bucle de render -----------------------------------------------------------------------
    // Temporizador propio en lugar de Choreographer: con la pantalla apagada no hay vsync y el
    // directo debe seguir.

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val start = SystemClock.uptimeMillis()
            renderFrame(System.nanoTime())
            val fps = maxOf(if (encoder != null) outputFps else 0, if (previews.isNotEmpty()) previewFps else 0, 5)
            val interval = 1000L / fps
            handler.postAtTime(this, start + maxOf(1L, interval - (SystemClock.uptimeMillis() - start).coerceAtMost(interval)))
        }
    }

    private fun renderFrame(now: Long) {
        val egl = egl ?: return
        val gl = gl ?: return
        val state = studio.state.value

        if (encoder == null && previews.isEmpty()) {
            // Nadie mira: tras unos segundos se cierran cámaras y capturas
            val nowMs = SystemClock.uptimeMillis()
            if (idleSinceMillis == 0L) idleSinceMillis = nowMs
            if (nowMs - idleSinceMillis > IDLE_RELEASE_MS && renderers.isNotEmpty()) {
                egl.makeOffscreenCurrent()
                releaseRenderers()
            }
            return
        }
        idleSinceMillis = 0L

        val outputDue = encoder != null && due(now, lastOutputNanos, outputFps)
        val editDue = previews.containsKey(PreviewSlot.Edit) && due(now, lastPreviewNanos, previewFps)
        val programPreviewDue = state.studioMode && previews.containsKey(PreviewSlot.Program) &&
            due(now, lastProgramPreviewNanos, if (singleRender) maxOf(2, previewFps / 3) else previewFps)
        if (!outputDue && !editDue && !programPreviewDue) return

        try {
            egl.makeCurrent((encoder ?: previews.values.first()).eglSurface)
            val canvas = state.canvas
            ensureFramebuffers(canvas)
            val transitionScene = updateTransition(state.programSceneId, now)
            syncRenderers(state, transitionScene, canvas)

            val program = programFb!!
            renderScene(gl, program, state, state.programScene, transitionScene, now)

            if (outputDue) {
                val target = encoder!!
                egl.makeCurrent(target.eglSurface)
                blit(gl, program, target, letterbox = false)
                egl.setPresentationTime(target.eglSurface, now)
                egl.swapBuffers(target.eglSurface)
                lastOutputNanos = now
                countFrame(now)
            }

            if (editDue) {
                val target = previews.getValue(PreviewSlot.Edit)
                val source = if (state.studioMode) {
                    val fb = previewFb!!
                    renderScene(gl, fb, state, state.previewScene, null, now)
                    fb
                } else program
                egl.makeCurrent(target.eglSurface)
                blit(gl, source, target, letterbox = true)
                egl.swapBuffers(target.eglSurface)
                lastPreviewNanos = now
                if (encoder == null) countFrame(now)
            }

            if (programPreviewDue) {
                val target = previews.getValue(PreviewSlot.Program)
                egl.makeCurrent(target.eglSurface)
                blit(gl, program, target, letterbox = true)
                egl.swapBuffers(target.eglSurface)
                lastProgramPreviewNanos = now
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error de render", e)
        }
    }

    private fun due(now: Long, last: Long, fps: Int) = now - last >= 1_000_000_000L / fps.coerceAtLeast(1) - SLACK_NANOS

    private fun ensureFramebuffers(canvas: CanvasConfig) {
        val scale = resolutionScale.coerceIn(0.25f, 1f)
        val w = ((canvas.width * scale).toInt() and 1.inv()).coerceAtLeast(2)
        val h = ((canvas.height * scale).toInt() and 1.inv()).coerceAtLeast(2)
        if (programFb?.width != w || programFb?.height != h) {
            programFb?.release()
            previewFb?.release()
            programFb = Framebuffer(w, h)
            previewFb = Framebuffer(w, h)
        }
    }

    /** Devuelve la escena saliente mientras dura un fundido. */
    private fun updateTransition(programId: String?, now: Long): Scene? {
        val transition = settings.value.transition
        if (programId != shownProgramId) {
            if (shownProgramId != null && transition.type == TransitionType.Fade && transition.durationMs > 0) {
                fromSceneId = shownProgramId
                transitionStartNanos = now
            }
            shownProgramId = programId
        }
        val from = fromSceneId ?: return null
        if (now - transitionStartNanos >= transition.durationMs * 1_000_000L) {
            fromSceneId = null
            return null
        }
        return studio.state.value.scenes.firstOrNull { it.id == from }
    }

    private fun renderScene(
        gl: GlRenderer,
        fb: Framebuffer,
        state: com.nexo.live.engine.studio.StudioState,
        scene: Scene?,
        outgoing: Scene?,
        now: Long,
    ) {
        fb.bind()
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        Matrix.orthoM(projection, 0, 0f, fb.width.toFloat(), fb.height.toFloat(), 0f, -1f, 1f)
        var incomingAlpha = 1f
        if (outgoing != null) {
            drawItems(gl, fb, state, outgoing, 1f)
            val duration = settings.value.transition.durationMs.coerceAtLeast(1) * 1_000_000f
            incomingAlpha = ((now - transitionStartNanos) / duration).coerceIn(0f, 1f)
        }
        if (scene != null) drawItems(gl, fb, state, scene, incomingAlpha)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun drawItems(gl: GlRenderer, fb: Framebuffer, state: com.nexo.live.engine.studio.StudioState, scene: Scene, alpha: Float) {
        for (item in scene.items) {
            if (!item.visible) continue
            val renderer = renderers[item.sourceId] ?: continue
            val source = state.sources[item.sourceId] ?: continue
            val t = item.transform
            val box = PixelRect(t.x * fb.width, t.y * fb.height, t.width * fb.width, t.height * fb.height)
            renderer.update(source, box.width.toInt(), box.height.toInt())
            renderer.draw(gl, item, box, projection, (t.opacity * alpha).coerceIn(0f, 1f), matrices)
        }
    }

    private fun blit(gl: GlRenderer, fb: Framebuffer, target: Target, letterbox: Boolean) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, target.width, target.height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        Matrix.orthoM(projection, 0, 0f, target.width.toFloat(), target.height.toFloat(), 0f, -1f, 1f)
        val quad = if (letterbox) Layout.letterbox(fb.width, fb.height, target.width, target.height)
        else PixelRect(0f, 0f, target.width.toFloat(), target.height.toFloat())
        gl.drawTexture(fb.textureId, isExternal = false, mvp = matrices.mvp(projection, quad, 0f), texMatrix = matrices.framebufferTex(), alpha = 1f)
    }

    /** Crea las fuentes visibles, recrea las que cambiaron de dispositivo y cierra las que nadie usa. */
    private fun syncRenderers(state: com.nexo.live.engine.studio.StudioState, outgoing: Scene?, canvas: CanvasConfig) {
        val nowMs = SystemClock.uptimeMillis()
        val scenes = listOfNotNull(state.programScene, if (state.studioMode) state.previewScene else null, outgoing)
        val used = scenes.flatMap { s -> s.items.filter { it.visible }.map { it.sourceId } }.toSet()

        for (id in used) {
            val source = state.sources[id] ?: continue
            if (!source.hasVideo) continue
            lastUsed[id] = nowMs
            val existing = renderers[id]
            val identity = identityOf(source)
            if (existing != null && existing.identity == identity) continue
            existing?.release()
            renderers[id] = createRenderer(source, identity, canvas)
        }

        val stale = renderers.keys.filter { it !in used && nowMs - (lastUsed[it] ?: 0L) > UNUSED_RELEASE_MS || state.sources[it] == null }
        for (id in stale) {
            renderers.remove(id)?.release()
            lastUsed.remove(id)
            _sourceStatus.update { it - id }
        }
    }

    private fun createRenderer(source: Source, identity: Any, canvas: CanvasConfig): SourceRenderer {
        val longSide = maxOf(canvas.width, canvas.height)
        return when (source) {
            is Source.SolidColor -> ColorRenderer(source.id)
            is Source.Image, is Source.Text -> BitmapRenderer(source.id, bitmapWorker, longSide)
            else -> {
                val capture = captureFactory.create(source, longSide, canvas.fps)
                if (capture == null) ColorRenderer(source.id)
                else ExternalRenderer(source.id, identity, capture) { id, status -> _sourceStatus.update { it + (id to status) } }
            }
        }
    }

    /** Cambios que obligan a reabrir el dispositivo; el resto se aplica en caliente. */
    private fun identityOf(source: Source): Any = when (source) {
        is Source.Camera -> listOf("camera", source.cameraId, source.facing)
        is Source.UsbCamera -> listOf("usb", source.deviceName)
        is Source.Screen -> "screen"
        is Source.SolidColor -> "color"
        is Source.Image, is Source.Text -> "bitmap"
        else -> "none"
    }

    private fun releaseRenderers() {
        renderers.values.forEach { it.release() }
        renderers.clear()
        lastUsed.clear()
        _sourceStatus.value = emptyMap()
    }

    private fun countFrame(now: Long) {
        if (fpsWindowStart == 0L) fpsWindowStart = now
        fpsFrames++
        val elapsed = now - fpsWindowStart
        if (elapsed >= 1_000_000_000L) {
            _renderFps.value = fpsFrames * 1_000_000_000f / elapsed
            fpsFrames = 0
            fpsWindowStart = now
        }
    }

    private companion object {
        const val TAG = "NexoCompositor"
        const val SLACK_NANOS = 3_000_000L
        const val IDLE_RELEASE_MS = 3_000L
        const val UNUSED_RELEASE_MS = 5_000L
    }
}
