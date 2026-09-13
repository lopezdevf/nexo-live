// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Nexo Live contributors

package com.nexo.live.ui.studio

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nexo.live.NexoApp
import com.nexo.live.engine.model.AudioChannel
import com.nexo.live.engine.model.Facing
import com.nexo.live.engine.model.FitMode
import com.nexo.live.engine.model.Source
import com.nexo.live.engine.model.SourceKind
import com.nexo.live.engine.model.Transform
import com.nexo.live.engine.model.kind
import com.nexo.live.engine.render.PreviewSlot
import com.nexo.live.engine.service.StudioService
import com.nexo.live.engine.settings.StudioSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

enum class DockTab(val label: String) { Sources("FUENTES"), Mixer("MEZCLADOR"), Destinations("DESTINOS") }

class StudioViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = (application as NexoApp).engine
    private val studio = engine.studio

    val state = studio.state
    val settings: StateFlow<StudioSettings> = engine.settings.settings
    val levels = engine.audio.levels
    val audioErrors = engine.audio.errors
    val monitorStatus = engine.audio.monitorStatus
    val sourceStatus = engine.compositor.sourceStatus
    val cameras = engine.devices.cameras
    val usbCameras = engine.devices.usbCameras
    val audioInputs = engine.devices.inputs
    val audioOutputs = engine.devices.outputs
    val thermal = engine.thermal
    val screenCaptureActive = engine.projection.active

    private val _dockTab = MutableStateFlow(DockTab.Sources)
    val dockTab: StateFlow<DockTab> = _dockTab.asStateFlow()

    /** Mensajes breves para el usuario (se consumen al mostrarse). */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _propertiesFor = MutableStateFlow<String?>(null)
    /** Fuente cuyas propiedades están abiertas. */
    val propertiesFor: StateFlow<String?> = _propertiesFor.asStateFlow()

    private val _settingsOpen = MutableStateFlow(false)
    val settingsOpen: StateFlow<Boolean> = _settingsOpen.asStateFlow()

    private val _missingPermissions = MutableStateFlow(false)
    val missingPermissions: StateFlow<Boolean> = _missingPermissions.asStateFlow()

    init {
        viewModelScope.launch { engine.events.collect { _notice.value = it } }
    }

    fun showDock(tab: DockTab) { _dockTab.value = tab }
    fun consumeNotice() { _notice.value = null }
    fun notify(message: String) { _notice.value = message }

    fun onPermissionsResult(granted: Map<String, Boolean>) {
        val essential = listOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO)
        _missingPermissions.value = essential.any { granted[it] == false }
        engine.compositor.retryFailedSources()
    }

    // ---- Vista previa --------------------------------------------------------------------

    fun setPreviewSurface(slot: PreviewSlot, surface: Surface?, width: Int, height: Int) =
        engine.setPreviewSurface(slot, surface, width, height)

    // ---- Escenas y capas ---------------------------------------------------------------

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

    // ---- Fuentes -------------------------------------------------------------------------

    fun addSource(kind: SourceKind) {
        val id = UUID.randomUUID().toString()
        val count = state.value.sources.values.count { it.kind == kind }
        val name = if (count == 0) kind.label else "${kind.label} ${count + 1}"
        val source = when (kind) {
            SourceKind.Camera -> Source.Camera(id, name, Facing.Back)
            SourceKind.UsbCamera -> Source.UsbCamera(id, name)
            SourceKind.PcInput -> Source.PcInput(id, name, port = 9000 + count)
            SourceKind.Screen -> Source.Screen(id, name)
            SourceKind.Image -> return // se añade tras elegir la imagen
            SourceKind.Text -> Source.Text(id, name, text = "Texto nuevo", backgroundArgb = 0x99000000)
            SourceKind.SolidColor -> Source.SolidColor(id, name, argb = 0xFF1B1E26)
            SourceKind.Microphone -> Source.Microphone(id, name)
            SourceKind.InternalAudio -> Source.InternalAudio(id, name)
        }
        val transform = when (kind) {
            SourceKind.Text -> Transform(x = 0.1f, y = 0.4f, width = 0.8f, height = 0.2f, fit = FitMode.Stretch)
            else -> Transform.FullCanvas
        }
        studio.addSource(source, transform)
        _dockTab.value = if (source.hasVideo) DockTab.Sources else DockTab.Mixer
        if (source.hasVideo) _propertiesFor.value = source.id
    }

    /** Copia la imagen elegida al almacenamiento privado: los permisos del selector caducan. */
    fun addImage(uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            val dir = File(app.filesDir, "images").apply { mkdirs() }
            val file = File(dir, id)
            val copied = runCatching {
                app.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } != null
            }.getOrDefault(false)
            if (!copied) {
                _notice.value = "No se pudo leer la imagen"
                return@launch
            }
            val count = state.value.sources.values.count { it is Source.Image }
            studio.addSource(
                Source.Image(id, if (count == 0) "Imagen" else "Imagen ${count + 1}", file.absolutePath),
                Transform(x = 0.3f, y = 0.3f, width = 0.4f, height = 0.4f, fit = FitMode.Contain),
            )
        }
    }

    fun openProperties(sourceId: String?) { _propertiesFor.value = sourceId }

    fun updateSource(source: Source) = studio.updateSource(source)

    fun renameSource(id: String, name: String) = studio.renameSource(id, name)

    fun deleteSource(id: String) {
        val source = state.value.sources[id]
        studio.deleteSource(id)
        _propertiesFor.value = null
        if (source is Source.Image) runCatching { File(source.uri).delete() }
    }

    // ---- Mezclador ---------------------------------------------------------------------------

    fun setGain(sourceId: String, db: Float) = studio.setGain(sourceId, db)
    fun toggleMute(sourceId: String) = studio.toggleMute(sourceId)
    fun setChannel(channel: AudioChannel) = studio.setChannel(channel)

    // ---- Salida ---------------------------------------------------------------------------------

    fun toggleRecording() {
        viewModelScope.launch(Dispatchers.Default) {
            if (engine.isRecording) engine.stopRecording()
            else engine.startRecording()?.let { _notice.value = it }
        }
    }

    /** Permiso de captura de pantalla concedido: se entrega a través del servicio en primer plano. */
    fun onScreenCaptureResult(resultCode: Int, data: Intent?) {
        if (data == null || resultCode != android.app.Activity.RESULT_OK) {
            _notice.value = "Sin permiso no se puede mostrar la pantalla ni capturar el audio del juego"
            return
        }
        val sources = state.value.sources.values
        StudioService.startWithScreenCapture(
            getApplication(), resultCode, data,
            camera = sources.any { it is Source.Camera || it is Source.UsbCamera },
            microphone = sources.any { it is Source.Microphone },
        )
    }

    fun screenCaptureIntent(): Intent = engine.projection.createPermissionIntent()

    // ---- Ajustes ----------------------------------------------------------------------------------

    fun openSettings(open: Boolean) { _settingsOpen.value = open }

    fun updateSettings(block: (StudioSettings) -> StudioSettings) {
        if ((engine.isStreaming || engine.isRecording)) {
            val before = settings.value
            val after = block(before)
            // Cambiar resolución, fps o códec a mitad de directo rompería la emisión
            if (after.video.width != before.video.width || after.video.height != before.video.height ||
                after.video.fps != before.video.fps || after.video.codec != before.video.codec ||
                after.audio.sampleRate != before.audio.sampleRate
            ) {
                _notice.value = "Detén el directo y la grabación para cambiar resolución, fps, códec o frecuencia de audio"
                return
            }
        }
        engine.settings.update(block)
    }

    fun resetSettings() = engine.settings.reset()

    companion object {
        fun factory(app: NexoApp): ViewModelProvider.Factory = viewModelFactory {
            initializer { StudioViewModel(app) }
        }
    }
}
