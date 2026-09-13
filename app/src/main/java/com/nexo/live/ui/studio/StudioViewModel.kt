// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Nexo Live contributors

package com.nexo.live.ui.studio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nexo.live.engine.model.Facing
import com.nexo.live.engine.model.Source
import com.nexo.live.engine.model.SourceKind
import com.nexo.live.engine.model.Transform
import com.nexo.live.engine.model.kind
import com.nexo.live.engine.studio.StudioController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class DockTab(val label: String) { Sources("FUENTES"), Mixer("MEZCLADOR"), Destinations("DESTINOS") }

class StudioViewModel(private val studio: StudioController) : ViewModel() {

    val state = studio.state

    private val _dockTab = MutableStateFlow(DockTab.Sources)
    val dockTab: StateFlow<DockTab> = _dockTab.asStateFlow()

    /** Mensajes breves para el usuario (se consumen al mostrarse). */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun showDock(tab: DockTab) { _dockTab.value = tab }
    fun consumeNotice() { _notice.value = null }

    fun selectScene(id: String) = studio.selectScene(id)
    fun addScene() = studio.addScene("Escena ${state.value.scenes.size + 1}").let(studio::selectScene)
    fun removeScene(id: String) = studio.removeScene(id)
    fun setStudioMode(enabled: Boolean) = studio.setStudioMode(enabled)
    fun transition() = studio.transition()

    fun selectItem(id: String?) = studio.selectItem(id)
    fun toggleVisibility(id: String) = studio.toggleVisibility(id)
    fun toggleLock(id: String) = studio.toggleLock(id)
    fun removeItem(id: String) = studio.removeItem(id)
    fun raise(id: String) = studio.moveLayer(id, +1)
    fun lower(id: String) = studio.moveLayer(id, -1)
    fun setTransform(id: String, transform: Transform) = studio.setTransform(id, transform)

    fun setGain(sourceId: String, db: Float) = studio.setGain(sourceId, db)
    fun toggleMute(sourceId: String) = studio.toggleMute(sourceId)

    fun addSource(kind: SourceKind) {
        val id = UUID.randomUUID().toString()
        val count = state.value.sources.values.count { it.kind == kind }
        val name = if (count == 0) kind.label else "${kind.label} ${count + 1}"
        val source = when (kind) {
            SourceKind.Camera -> Source.Camera(id, name, Facing.Back)
            SourceKind.UsbCamera -> Source.UsbCamera(id, name)
            SourceKind.Screen -> Source.Screen(id, name)
            SourceKind.Image -> Source.Image(id, name, uri = "")
            SourceKind.Text -> Source.Text(id, name, text = "Texto nuevo")
            SourceKind.SolidColor -> Source.SolidColor(id, name, argb = 0xFF1B1E26)
            SourceKind.Microphone -> Source.Microphone(id, name)
            SourceKind.InternalAudio -> Source.InternalAudio(id, name)
        }
        val transform = when (kind) {
            SourceKind.Text -> Transform(x = 0.1f, y = 0.4f, width = 0.8f, height = 0.2f)
            SourceKind.Image -> Transform(x = 0.3f, y = 0.3f, width = 0.4f, height = 0.4f)
            else -> Transform.FullCanvas
        }
        studio.addSource(source, transform)
        _dockTab.value = if (source.hasVideo) DockTab.Sources else DockTab.Mixer
    }

    fun toggleRecording() {
        _notice.value = "La grabación local se conecta en el hito 3 (motor de salida)."
    }

    companion object {
        fun factory(studio: StudioController): ViewModelProvider.Factory = viewModelFactory {
            initializer { StudioViewModel(studio) }
        }
    }
}
