// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Nexo Live contributors

package com.nexo.live.engine.studio

import com.nexo.live.engine.model.AudioChannel
import com.nexo.live.engine.model.CanvasConfig
import com.nexo.live.engine.model.EncoderConfig
import com.nexo.live.engine.model.LiveStatus
import com.nexo.live.engine.model.RecordStatus
import com.nexo.live.engine.model.Scene
import com.nexo.live.engine.model.SceneItem
import com.nexo.live.engine.model.Source
import com.nexo.live.engine.model.StreamStats
import com.nexo.live.engine.thermal.ThermalLevel

data class StudioState(
    val canvas: CanvasConfig = CanvasConfig.HD_LANDSCAPE,
    val sources: Map<String, Source> = emptyMap(),
    val scenes: List<Scene> = emptyList(),
    /** Escena que sale al aire. */
    val programSceneId: String? = null,
    /** Escena en edición. Igual a [programSceneId] salvo en modo estudio. */
    val previewSceneId: String? = null,
    val studioMode: Boolean = false,
    val selectedItemId: String? = null,
    val audio: List<AudioChannel> = emptyList(),
    val encoder: EncoderConfig = EncoderConfig(),
    val live: LiveStatus = LiveStatus.Offline,
    val record: RecordStatus = RecordStatus.Idle,
    val stats: StreamStats = StreamStats(),
    val thermalLevel: ThermalLevel = ThermalLevel.None,
    /** true si la protección térmica está recortando calidad ahora mismo. */
    val thermalThrottled: Boolean = false,
) {
    val programScene: Scene? get() = scenes.firstOrNull { it.id == programSceneId }
    val previewScene: Scene? get() = scenes.firstOrNull { it.id == previewSceneId }

    /** La escena sobre la que actúan las herramientas de edición. */
    val editingScene: Scene? get() = if (studioMode) previewScene else programScene

    val selectedItem: SceneItem? get() = editingScene?.items?.firstOrNull { it.id == selectedItemId }

    val isLive: Boolean get() = live is LiveStatus.Live || live is LiveStatus.Reconnecting
    val isRecording: Boolean get() = record is RecordStatus.Recording
}
