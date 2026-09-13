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

interface CaptureFactory {
    /** Identifica el dispositivo físico que usa la fuente (p. ej. «camera:1»). */
    fun keyFor(source: Source): String

    /** null si la fuente no captura de un dispositivo (imagen, texto, color). */
    fun create(source: Source, canvas: CanvasConfig): SurfaceCapture?
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
    private val createdAt = HashMap<String, Long>()
    private val retries = HashMap<String, Int>()
    /** Estado de cada captura (por clave de dispositivo); lo escriben los hilos de captura. */
    private val keyStatus = java.util.concurrent.ConcurrentHashMap<String, CaptureStatus>()
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

    private var nextTickMs = 0L

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            renderFrame(System.nanoTime())
            val fps = maxOf(if (encoder != null) outputFps else 0, if (previews.isNotEmpty()) previewFps else 0, 5)
            // Cadencia fija: el siguiente fotograma cae un intervalo después del anterior, no del final del render
            val interval = 1000.0 / fps
            val now = SystemClock.uptimeMillis()
            nextTickMs = if (nextTickMs == 0L || now - nextTickMs > interval) now + interval.toLong()
            else nextTickMs + interval.toLong()
            handler.postAtTime(this, nextTickMs)
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

    /** Holgura del 25 % del intervalo: con un temporizador de milisegundos, 33 ms deben contar como 30 fps. */
    private fun due(now: Long, last: Long, fps: Int): Boolean {
        val interval = 1_000_000_000L / fps.coerceAtLeast(1)
        return now - last >= interval - interval / 4
    }

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
            val source = state.sources[item.sourceId] ?: continue
            val renderer = renderers[keyOf(source)] ?: continue
            val t = item.transform
            val box = PixelRect(t.x * fb.width, t.y * fb.height, t.width * fb.width, t.height * fb.height)
            renderer.update(source, box.width.toInt(), box.height.toInt())
            renderer.draw(gl, item, source, box, projection, (t.opacity * alpha).coerceIn(0f, 1f), matrices)
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

    /**
     * Clave del renderizador: las fuentes que usan el mismo dispositivo físico (misma cámara, la
     * pantalla, el mismo puerto del PC) comparten una sola captura. Abrir dos veces una cámara hace
     * que Android expulse a la primera y ambas entran en un bucle de reconexión.
     */
    private fun keyOf(source: Source): String = when (source) {
        is Source.Image, is Source.Text, is Source.SolidColor -> "src:${source.id}"
        else -> captureFactory.keyFor(source)
    }

    /** Crea las capturas visibles, recrea las que fallaron y cierra las que nadie usa. */
    private fun syncRenderers(state: com.nexo.live.engine.studio.StudioState, outgoing: Scene?, canvas: CanvasConfig) {
        val nowMs = SystemClock.uptimeMillis()
        val scenes = listOfNotNull(state.programScene, if (state.studioMode) state.previewScene else null, outgoing)
        val usedSources = scenes.flatMap { s -> s.items.filter { it.visible }.mapNotNull { state.sources[it.sourceId] } }
            .filter { it.hasVideo }
            .distinctBy { it.id }
        val usedKeys = HashMap<String, Source>()
        for (source in usedSources) usedKeys.putIfAbsent(keyOf(source), source)

        for ((key, source) in usedKeys) {
            lastUsed[key] = nowMs
            if (renderers.containsKey(key) && !retryDue(key, nowMs)) continue
            renderers.remove(key)?.release()
            keyStatus.remove(key)
            renderers[key] = createRenderer(key, source, canvas)
            createdAt[key] = nowMs
        }

        val liveKeys = state.sources.values.filter { it.hasVideo }.map { keyOf(it) }.toSet()
        val stale = renderers.keys.filter { (it !in usedKeys && nowMs - (lastUsed[it] ?: 0L) > UNUSED_RELEASE_MS) || it !in liveKeys }
        for (key in stale) {
            renderers.remove(key)?.release()
            lastUsed.remove(key)
            createdAt.remove(key)
            retries.remove(key)
            keyStatus.remove(key)
        }
        publishStatus(state)
    }

    /** Traduce el estado de cada captura compartida a cada fuente que la usa. */
    private fun publishStatus(state: com.nexo.live.engine.studio.StudioState) {
        val bySource = HashMap<String, CaptureStatus>()
        for (source in state.sources.values) {
            if (!source.hasVideo) continue
            keyStatus[keyOf(source)]?.let { bySource[source.id] = it }
        }
        if (bySource != _sourceStatus.value) _sourceStatus.value = bySource
    }

    /**
     * Una captura con error (permiso aún no concedido, cámara ocupada, USB desconectado) se
     * reintenta sola con espera creciente: 3 s, 6 s, 12 s… hasta 30 s.
     */
    private fun retryDue(key: String, nowMs: Long): Boolean {
        val status = keyStatus[key]
        if (status is CaptureStatus.Running) retries.remove(key)
        if (status !is CaptureStatus.Error) return false
        val attempt = retries[key] ?: 0
        val wait = minOf(RETRY_BASE_MS shl attempt.coerceAtMost(4), RETRY_MAX_MS)
        if (nowMs - (createdAt[key] ?: 0L) < wait) return false
        retries[key] = attempt + 1
        return true
    }

    /** Reintenta ya todas las fuentes con error (p. ej. justo después de conceder permisos). */
    fun retryFailedSources() = post {
        retries.clear()
        createdAt.keys.toList().forEach { key ->
            if (keyStatus[key] is CaptureStatus.Error) createdAt[key] = 0L
        }
    }

    private fun createRenderer(key: String, source: Source, canvas: CanvasConfig): SourceRenderer {
        val longSide = maxOf(canvas.width, canvas.height)
        return when (source) {
            is Source.SolidColor -> ColorRenderer(source.id)
            is Source.Image, is Source.Text -> BitmapRenderer(source.id, bitmapWorker, longSide)
            else -> {
                val capture = captureFactory.create(source, canvas)
                if (capture == null) ColorRenderer(source.id)
                else ExternalRenderer(source.id, key, capture) { _, status -> keyStatus[key] = status }
            }
        }
    }

    private fun releaseRenderers() {
        renderers.values.forEach { it.release() }
        renderers.clear()
        lastUsed.clear()
        createdAt.clear()
        retries.clear()
        keyStatus.clear()
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
        const val IDLE_RELEASE_MS = 3_000L
        const val UNUSED_RELEASE_MS = 5_000L
        const val RETRY_BASE_MS = 3_000L
        const val RETRY_MAX_MS = 30_000L
    }
}
