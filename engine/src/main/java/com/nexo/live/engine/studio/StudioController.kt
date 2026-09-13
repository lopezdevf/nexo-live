// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Nexo Live contributors

package com.nexo.live.engine.studio

import com.nexo.live.engine.model.AudioChannel
import com.nexo.live.engine.model.CanvasConfig
import com.nexo.live.engine.model.EncoderConfig
import com.nexo.live.engine.model.LiveStatus
import com.nexo.live.engine.model.RecordStatus
import com.nexo.live.engine.thermal.ThermalLevel
import com.nexo.live.engine.model.Scene
import com.nexo.live.engine.model.SceneItem
import com.nexo.live.engine.model.Source
import com.nexo.live.engine.model.Transform
import com.nexo.live.engine.model.renamed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * Fuente única de verdad del estudio. La UI solo lee [state] y llama a estas
 * operaciones; el compositor GL y las salidas observan el mismo estado.
 */
class StudioController(
    initial: StudioState = StudioState(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<StudioState> = _state.asStateFlow()

    // ---- Escenas ----------------------------------------------------------

    fun addScene(name: String): String {
        val scene = Scene(id = newId(), name = name)
        _state.update { s ->
            s.copy(
                scenes = s.scenes + scene,
                programSceneId = s.programSceneId ?: scene.id,
                previewSceneId = s.previewSceneId ?: scene.id,
            )
        }
        return scene.id
    }

    fun renameScene(sceneId: String, name: String) = updateScene(sceneId) { it.copy(name = name) }

    fun removeScene(sceneId: String) = _state.update { s ->
        if (s.scenes.size <= 1) return@update s
        val remaining = s.scenes.filterNot { it.id == sceneId }
        val fallback = remaining.first().id
        s.copy(
            scenes = remaining,
            programSceneId = s.programSceneId.takeUnless { it == sceneId } ?: fallback,
            previewSceneId = s.previewSceneId.takeUnless { it == sceneId } ?: fallback,
            selectedItemId = null,
        )
    }

    /** Fuera del modo estudio, elegir escena la saca al aire directamente. */
    fun selectScene(sceneId: String) = _state.update { s ->
        if (s.scenes.none { it.id == sceneId }) return@update s
        if (s.studioMode) s.copy(previewSceneId = sceneId, selectedItemId = null)
        else s.copy(programSceneId = sceneId, previewSceneId = sceneId, selectedItemId = null)
    }

    fun setStudioMode(enabled: Boolean) = _state.update { s ->
        s.copy(studioMode = enabled, previewSceneId = s.programSceneId, selectedItemId = null)
    }

    /** Modo estudio: pasa la vista previa al aire (la transición la anima el compositor). */
    fun transition() = _state.update { s ->
        if (!s.studioMode) s
        else s.copy(programSceneId = s.previewSceneId, previewSceneId = s.programSceneId)
    }

    // ---- Fuentes y elementos ----------------------------------------------

    /**
     * Registra la fuente y, si tiene vídeo, la coloca en la escena en edición.
     * Las fuentes de audio van al mezclador.
     */
    fun addSource(source: Source, transform: Transform = Transform.FullCanvas): String? {
        var itemId: String? = null
        _state.update { s ->
            val scene = s.editingScene
            val withSource = s.copy(
                sources = s.sources + (source.id to source),
                audio = if (source.hasAudio && s.audio.none { it.sourceId == source.id }) {
                    s.audio + AudioChannel(source.id)
                } else s.audio,
            )
            if (!source.hasVideo || scene == null) return@update withSource
            val item = SceneItem(id = newId(), sourceId = source.id, transform = transform)
            itemId = item.id
            withSource.replaceScene(scene.copy(items = scene.items + item)).copy(selectedItemId = item.id)
        }
        return itemId
    }

    /** Añade una fuente ya existente (p. ej. la misma cámara) a la escena en edición. */
    fun addExistingSource(sourceId: String, transform: Transform = Transform.FullCanvas) = editItems { items, s ->
        if (s.sources[sourceId]?.hasVideo != true) items
        else items + SceneItem(id = newId(), sourceId = sourceId, transform = transform)
    }

    fun selectItem(itemId: String?) = _state.update { it.copy(selectedItemId = itemId) }

    fun removeItem(itemId: String) = _state.update { s ->
        val scene = s.editingScene ?: return@update s
        s.replaceScene(scene.copy(items = scene.items.filterNot { it.id == itemId }))
            .copy(selectedItemId = s.selectedItemId.takeUnless { it == itemId })
    }

    fun toggleVisibility(itemId: String) = updateItem(itemId) { it.copy(visible = !it.visible) }

    fun toggleLock(itemId: String) = updateItem(itemId) { it.copy(locked = !it.locked) }

    fun setTransform(itemId: String, transform: Transform) = updateItem(itemId) {
        if (it.locked) it else it.copy(transform = transform)
    }

    /** Sube (+1) o baja (-1) un elemento en el orden de capas. */
    fun moveLayer(itemId: String, delta: Int) = editItems { items, _ ->
        val from = items.indexOfFirst { it.id == itemId }
        if (from < 0) return@editItems items
        val to = (from + delta).coerceIn(0, items.lastIndex)
        items.toMutableList().apply { add(to, removeAt(from)) }
    }

    fun updateSource(source: Source) = _state.update { s ->
        if (source.id !in s.sources) s else s.copy(sources = s.sources + (source.id to source))
    }

    // ---- Mezclador ----------------------------------------------------------

    fun setGain(sourceId: String, gainDb: Float) = updateChannel(sourceId) {
        it.copy(gainDb = gainDb.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB))
    }

    fun toggleMute(sourceId: String) = updateChannel(sourceId) { it.copy(muted = !it.muted) }

    // ---- Lienzo y salida ----------------------------------------------------

    fun setCanvas(canvas: CanvasConfig) = _state.update { it.copy(canvas = canvas) }

    fun setLive(status: LiveStatus, bitrateKbps: Int = 0) = _state.update {
        it.copy(live = status, stats = it.stats.copy(bitrateKbps = bitrateKbps))
    }

    fun setEncoder(encoder: EncoderConfig) = _state.update { if (it.encoder == encoder) it else it.copy(encoder = encoder) }

    fun setRecord(status: RecordStatus) = _state.update { it.copy(record = status) }

    fun setThermal(level: ThermalLevel, throttled: Boolean) = _state.update {
        if (it.thermalLevel == level && it.thermalThrottled == throttled) it else it.copy(thermalLevel = level, thermalThrottled = throttled)
    }

    fun setStats(fps: Float, droppedFrames: Long) = _state.update {
        it.copy(stats = it.stats.copy(fps = fps, droppedFrames = droppedFrames))
    }

    // ---- Propiedades de fuentes -----------------------------------------------------------

    fun renameSource(sourceId: String, name: String) = _state.update { s ->
        val source = s.sources[sourceId] ?: return@update s
        s.copy(sources = s.sources + (sourceId to source.renamed(name.ifBlank { source.name })))
    }

    fun setChannel(channel: AudioChannel) = _state.update { s ->
        s.copy(audio = s.audio.map { if (it.sourceId == channel.sourceId) channel.copy(gainDb = channel.gainDb.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)) else it })
    }

    /** Borra la fuente del estudio entero: de todas las escenas y del mezclador. */
    fun deleteSource(sourceId: String) = _state.update { s ->
        s.copy(
            sources = s.sources - sourceId,
            scenes = s.scenes.map { scene -> scene.copy(items = scene.items.filterNot { it.sourceId == sourceId }) },
            audio = s.audio.filterNot { it.sourceId == sourceId },
            selectedItemId = s.selectedItemId.takeIf { id -> s.editingScene?.items?.any { it.id == id && it.sourceId != sourceId } == true },
        )
    }

    // ---- Internos -----------------------------------------------------------

    private fun updateScene(sceneId: String, block: (Scene) -> Scene) = _state.update { s ->
        val scene = s.scenes.firstOrNull { it.id == sceneId } ?: return@update s
        s.replaceScene(block(scene))
    }

    private fun editItems(block: (List<SceneItem>, StudioState) -> List<SceneItem>) = _state.update { s ->
        val scene = s.editingScene ?: return@update s
        s.replaceScene(scene.copy(items = block(scene.items, s)))
    }

    private fun updateItem(itemId: String, block: (SceneItem) -> SceneItem) = editItems { items, _ ->
        items.map { if (it.id == itemId) block(it) else it }
    }

    private fun updateChannel(sourceId: String, block: (AudioChannel) -> AudioChannel) = _state.update { s ->
        s.copy(audio = s.audio.map { if (it.sourceId == sourceId) block(it) else it })
    }

    private fun StudioState.replaceScene(scene: Scene) =
        copy(scenes = scenes.map { if (it.id == scene.id) scene else it })

    companion object {
        const val MIN_GAIN_DB = -60f
        const val MAX_GAIN_DB = 6f
    }
}
