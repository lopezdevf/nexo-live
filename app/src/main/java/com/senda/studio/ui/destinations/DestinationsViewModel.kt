// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Senda Studio contributors

package com.senda.studio.ui.destinations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.senda.studio.engine.StudioEngine
import com.senda.studio.engine.model.CanvasOrientation
import com.senda.studio.engine.model.DestinationStatus
import com.senda.studio.engine.model.StreamDestination
import com.senda.studio.engine.output.ConnectionLink
import com.senda.studio.engine.output.DestinationRepository
import com.senda.studio.engine.output.LinkResult
import com.senda.studio.engine.output.MultiStreamer
import com.senda.studio.engine.output.OutputFormat
import com.senda.studio.engine.output.PlatformCatalog
import com.senda.studio.engine.output.PlatformInfo
import com.senda.studio.engine.output.StreamProtocol
import com.senda.studio.engine.studio.StudioController
import com.senda.studio.engine.studio.StudioState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

data class DestinationRow(
    val destination: StreamDestination,
    val platform: PlatformInfo,
    val hasKey: Boolean,
    val live: DestinationStatus?,
    val test: DestinationStatus?,
    val warnings: List<String>,
)

data class DestinationsUi(
    val rows: List<DestinationRow> = emptyList(),
    val enabledCount: Int = 0,
    /** Subida necesaria para todos los destinos activos, en kbps. */
    val uploadKbps: Int = 0,
)

enum class EditorStep { PickPlatform, Form }

data class EditorState(
    val editingId: String? = null,
    val step: EditorStep = EditorStep.PickPlatform,
    val platformId: String = PlatformCatalog.CustomRtmp.id,
    val name: String = "",
    val server: String = "",
    val customServer: Boolean = false,
    val secret: String = "",
    val hasStoredSecret: Boolean = false,
    val linkDraft: String = "",
    val linkError: String? = null,
    val serverError: String? = null,
    val secretError: String? = null,
) {
    val platform: PlatformInfo get() = PlatformCatalog.byId(platformId)
    val hostMismatch: Boolean get() = server.isNotBlank() && ConnectionLink.hostMismatch(server, platform)
}

class DestinationsViewModel(private val engine: StudioEngine) : ViewModel() {

    private val studio: StudioController = engine.studio
    private val repository: DestinationRepository = engine.destinations
    private val streamer: MultiStreamer = engine.streamer

    val ui: StateFlow<DestinationsUi> = combine(
        repository.destinations, streamer.live, streamer.tests, studio.state,
    ) { destinations, live, tests, studioState ->
        val rows = destinations.map { d ->
            val platform = PlatformCatalog.byId(d.platformId)
            DestinationRow(
                destination = d,
                platform = platform,
                hasKey = repository.hasSecret(d.id),
                live = live[d.id],
                test = tests[d.id],
                warnings = warningsFor(d, platform, studioState),
            )
        }
        val enabled = destinations.count { it.enabled }
        DestinationsUi(
            rows = rows,
            enabledCount = enabled,
            uploadKbps = enabled * (studioState.encoder.videoBitrateKbps + studioState.encoder.audioBitrateKbps),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DestinationsUi())

    private val _editor = MutableStateFlow<EditorState?>(null)
    val editor: StateFlow<EditorState?> = _editor.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()


    fun consumeNotice() { _notice.value = null }

    // ---- Lista ----------------------------------------------------------------

    fun setEnabled(id: String, enabled: Boolean) = repository.setEnabled(id, enabled)

    fun delete(id: String) {
        streamer.clearTest(id)
        repository.delete(id)
    }

    fun test(id: String) {
        val destination = repository.destinations.value.firstOrNull { it.id == id } ?: return
        if (!repository.hasSecret(id) && ConnectionLink.protocolOf(destination.server) == StreamProtocol.Rtmp) {
            _notice.value = "Añade la clave de ${destination.name} antes de probar."
            return
        }
        val format = currentFormat()
        viewModelScope.launch(Dispatchers.IO) {
            streamer.test(MultiStreamer.Target(destination, repository.publishUrl(destination)), format)
        }
    }

    fun endLive() {
        viewModelScope.launch(Dispatchers.Default) { engine.stopStreaming() }
    }

    /** Emite o termina. false si no hay destinos activos (la pantalla abre la pestaña de destinos). */
    fun goLive(): Boolean {
        if (engine.isStreaming) {
            viewModelScope.launch(Dispatchers.Default) { engine.stopStreaming() }
            return true
        }
        if (ui.value.enabledCount == 0) {
            _notice.value = "Añade o activa al menos un destino para emitir."
            return false
        }
        viewModelScope.launch(Dispatchers.Default) {
            engine.startStreaming()?.let { _notice.value = it }
        }
        return true
    }

    // ---- Enlaces compartidos con la app ------------------------------------------

    fun handleIncomingText(text: String) {
        val tokens = text.trim().split(Regex("""\s+"""))
        val candidate = when {
            tokens.size == 2 && ConnectionLink.protocolOf(tokens[0]) != null -> text
            else -> tokens.firstOrNull { ConnectionLink.protocolOf(it) != null }
        }
        if (candidate == null) {
            _notice.value = "El texto compartido no contiene un enlace rtmp://, rtmps:// o srt://."
            return
        }
        openNew()
        applyLink(candidate)
    }

    // ---- Editor -------------------------------------------------------------------

    fun openNew() { _editor.value = EditorState() }

    fun openEdit(id: String) {
        val d = repository.destinations.value.firstOrNull { it.id == id } ?: return
        val platform = PlatformCatalog.byId(d.platformId)
        _editor.value = EditorState(
            editingId = d.id,
            step = EditorStep.Form,
            platformId = d.platformId,
            name = d.name,
            server = d.server,
            customServer = platform.servers.none { it.url.trimEnd('/') == d.server.trimEnd('/') },
            hasStoredSecret = repository.hasSecret(d.id),
        )
    }

    fun closeEditor() { _editor.value = null }

    fun backToPlatforms() = edit { it.copy(step = EditorStep.PickPlatform) }

    fun pickPlatform(id: String) = edit {
        val platform = PlatformCatalog.byId(id)
        it.copy(
            step = EditorStep.Form,
            platformId = id,
            name = if (it.editingId == null || it.name.isBlank()) defaultName(platform) else it.name,
            server = platform.servers.firstOrNull()?.url ?: "",
            customServer = platform.needsUserServer,
            serverError = null,
        )
    }

    fun editLinkDraft(text: String) = edit { it.copy(linkDraft = text, linkError = null) }

    fun applyLink(raw: String) = edit { state ->
        when (val result = ConnectionLink.parse(raw)) {
            is LinkResult.Invalid -> state.copy(linkDraft = raw, linkError = result.reason)
            is LinkResult.Ok -> {
                val platform = result.platform
                state.copy(
                    step = EditorStep.Form,
                    platformId = platform.id,
                    name = if (state.editingId == null) defaultName(platform) else state.name,
                    server = result.server,
                    customServer = platform.servers.none { it.url.trimEnd('/') == result.server.trimEnd('/') },
                    secret = result.secret ?: state.secret,
                    linkDraft = "",
                    linkError = null,
                    serverError = null,
                    secretError = null,
                )
            }
        }
    }

    fun editName(name: String) = edit { it.copy(name = name) }

    fun editServer(server: String) = edit { it.copy(server = server, serverError = null) }

    fun selectServer(url: String) = edit { it.copy(server = url, customServer = false, serverError = null) }

    fun useCustomServer() = edit { it.copy(customServer = true, server = "", serverError = null) }

    fun editSecret(secret: String) = edit { it.copy(secret = secret, secretError = null) }

    /** Pegar un enlace completo en el campo de clave también funciona: se separa solo. */
    fun pasteIntoSecret(text: String) {
        if (ConnectionLink.protocolOf(text.trim().split(Regex("""\s+""")).first()) != null) applyLink(text)
        else editSecret(text.trim())
    }

    fun save() {
        val state = _editor.value ?: return
        var platform = state.platform
        var server = state.server.trim()
        var secret = state.secret.trim()

        // Normaliza lo escrito a mano (p. ej. añade /app en Kick o separa una clave pegada en el servidor)
        (ConnectionLink.parse(server) as? LinkResult.Ok)?.let { parsed ->
            server = parsed.server
            parsed.secret?.let { if (secret.isEmpty()) secret = it }
            if (platform.isCustom && !parsed.platform.isCustom) platform = parsed.platform
        }

        val serverError = ConnectionLink.validateServer(server, platform)
        val needsSecret = platform.protocol == StreamProtocol.Rtmp && !state.hasStoredSecret
        val secretError = if (needsSecret && secret.isEmpty()) "Falta la clave de emisión." else null
        if (serverError != null || secretError != null) {
            edit { it.copy(server = server, serverError = serverError, secretError = secretError) }
            return
        }

        val destination = StreamDestination(
            id = state.editingId ?: UUID.randomUUID().toString(),
            platformId = platform.id,
            name = state.name.trim().ifEmpty { defaultName(platform) },
            server = server,
            enabled = repository.destinations.value.firstOrNull { it.id == state.editingId }?.enabled ?: true,
        )
        _editor.value = null
        viewModelScope.launch(Dispatchers.IO) {
            streamer.clearTest(destination.id)
            repository.save(destination, secret.ifEmpty { null })
        }
    }

    // ---- Internos -----------------------------------------------------------------

    private fun edit(block: (EditorState) -> EditorState) = _editor.update { it?.let(block) }

    private fun defaultName(platform: PlatformInfo): String {
        val existing = repository.destinations.value.count { it.platformId == platform.id }
        return if (existing == 0) platform.name else "${platform.name} ${existing + 1}"
    }

    private fun currentFormat(): OutputFormat {
        val canvas = studio.state.value.canvas
        return OutputFormat(canvas.width, canvas.height, canvas.fps, studio.state.value.encoder.audioSampleRate)
    }

    private fun warningsFor(d: StreamDestination, platform: PlatformInfo, state: StudioState): List<String> = buildList {
        platform.maxVideoKbps?.let { max ->
            if (state.encoder.videoBitrateKbps > max) {
                add("Tu bitrate (${state.encoder.videoBitrateKbps} kbps) supera el máximo de ${platform.name} ($max kbps).")
            }
        }
        if (platform.verticalFirst && state.canvas.orientation == CanvasOrientation.Landscape) {
            add("${platform.name} se ve en vertical: usa un lienzo 9:16.")
        }
        if (ConnectionLink.hostMismatch(d.server, platform)) {
            add("El servidor no parece de ${platform.name}. Revisa que el enlace sea de confianza.")
        }
    }

    companion object {
        fun formatMbps(kbps: Int): String = String.format(Locale.ROOT, "%.1f Mbps", kbps / 1000f)

        fun factory(engine: StudioEngine): ViewModelProvider.Factory =
            viewModelFactory { initializer { DestinationsViewModel(engine) } }
    }
}
