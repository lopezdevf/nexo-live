// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Nexo Live contributors

package com.nexo.live.ui.studio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexo.live.engine.model.Scene
import com.nexo.live.engine.model.Source
import com.nexo.live.engine.model.SourceKind
import com.nexo.live.engine.model.StreamDestination
import com.nexo.live.engine.render.PreviewSlot
import com.nexo.live.engine.studio.StudioState
import com.nexo.live.ui.destinations.Callout
import com.nexo.live.ui.destinations.DestinationEditor
import com.nexo.live.ui.destinations.DestinationsPanel
import com.nexo.live.ui.destinations.DestinationsUi
import com.nexo.live.ui.destinations.DestinationsViewModel
import com.nexo.live.ui.destinations.PrimaryAction
import com.nexo.live.ui.settings.SettingsScreen
import com.nexo.live.ui.theme.Nexo
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

@Composable
fun StudioScreen(vm: StudioViewModel, destinationsVm: DestinationsViewModel, onRequestPermissions: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val dockTab by vm.dockTab.collectAsStateWithLifecycle()
    val destinations by destinationsVm.ui.collectAsStateWithLifecycle()
    val editor by destinationsVm.editor.collectAsStateWithLifecycle()
    val propertiesFor by vm.propertiesFor.collectAsStateWithLifecycle()
    val settingsOpen by vm.settingsOpen.collectAsStateWithLifecycle()
    val missingPermissions by vm.missingPermissions.collectAsStateWithLifecycle()
    val screenCaptureActive by vm.screenCaptureActive.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var sceneToDelete by remember { mutableStateOf<Scene?>(null) }
    var destinationToDelete by remember { mutableStateOf<StreamDestination?>(null) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(vm::addImage)
    }
    val screenCapture = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        vm.onScreenCaptureResult(result.resultCode, result.data)
    }
    val requestScreenCapture = { screenCapture.launch(vm.screenCaptureIntent()) }

    // La pantalla no se apaga sola mientras se emite o se graba
    LocalView.current.keepScreenOn = state.isLive || state.isRecording

    // Recolección secuencial: consumir el aviso no cancela el snackbar que se está mostrando
    LaunchedEffect(Unit) {
        launch {
            vm.notice.filterNotNull().collect {
                vm.consumeNotice()
                snackbar.showSnackbar(it)
            }
        }
        launch {
            destinationsVm.notice.filterNotNull().collect {
                destinationsVm.consumeNotice()
                snackbar.showSnackbar(it)
            }
        }
    }

    val needsScreenCapture = !screenCaptureActive && state.sources.values.any { it is Source.Screen || it is Source.InternalAudio }

    val actions = StudioActions(
        vm = vm,
        destinationsVm = destinationsVm,
        onRemoveScene = { id -> sceneToDelete = state.scenes.firstOrNull { it.id == id } },
        onDeleteDestination = { id -> destinationToDelete = destinations.rows.firstOrNull { it.destination.id == id }?.destination },
        onGoLive = { if (!destinationsVm.goLive()) vm.showDock(DockTab.Destinations) },
        onAddSource = { kind ->
            if (kind == SourceKind.Image) imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            else vm.addSource(kind)
        },
        needsScreenCapture = needsScreenCapture,
        onRequestScreenCapture = requestScreenCapture,
    )

    Scaffold(containerColor = Nexo.colors.ink, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (missingPermissions) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) { Callout("Sin permiso de cámara o micrófono no se puede capturar.", Nexo.colors.record) }
                    PrimaryAction("PERMITIR", onRequestPermissions)
                }
            }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                if (maxWidth > maxHeight) {
                    LandscapeStudio(state, dockTab, destinations, actions)
                } else {
                    PortraitStudio(state, dockTab, destinations, actions)
                }
            }
        }
    }

    editor?.let { DestinationEditor(it, destinationsVm) }

    propertiesFor?.let { sourceId -> SourceProperties(vm, state, sourceId, screenCaptureActive, requestScreenCapture) }

    if (settingsOpen) {
        val settings by vm.settings.collectAsStateWithLifecycle()
        val outputs by vm.audioOutputs.collectAsStateWithLifecycle()
        val thermal by vm.thermal.collectAsStateWithLifecycle()
        SettingsScreen(
            settings = settings,
            outputs = outputs,
            thermal = thermal,
            busy = state.isLive || state.isRecording,
            onUpdate = vm::updateSettings,
            onReset = vm::resetSettings,
            onClose = { vm.openSettings(false) },
        )
    }

    sceneToDelete?.let { scene ->
        ConfirmDelete(
            title = "¿Eliminar «${scene.name}»?",
            message = if (state.scenes.size <= 1) "Necesitas al menos una escena."
            else "Se quitan sus ${scene.items.size} elementos. Las fuentes siguen disponibles en otras escenas.",
            enabled = state.scenes.size > 1,
            onConfirm = { vm.removeScene(scene.id) },
            onDismiss = { sceneToDelete = null },
        )
    }

    destinationToDelete?.let { destination ->
        ConfirmDelete(
            title = "¿Eliminar «${destination.name}»?",
            message = "También se borra su clave guardada. Tendrás que volver a pegarla para usar este destino.",
            onConfirm = { destinationsVm.delete(destination.id) },
            onDismiss = { destinationToDelete = null },
        )
    }
}

@Composable
private fun SourceProperties(vm: StudioViewModel, state: StudioState, sourceId: String, screenCaptureActive: Boolean, onRequestScreenCapture: () -> Unit) {
    val source = state.sources[sourceId] ?: return
    val cameras by vm.cameras.collectAsStateWithLifecycle()
    val usbCameras by vm.usbCameras.collectAsStateWithLifecycle()
    val inputs by vm.audioInputs.collectAsStateWithLifecycle()
    val sourceStatus by vm.sourceStatus.collectAsStateWithLifecycle()
    val audioErrors by vm.audioErrors.collectAsStateWithLifecycle()
    val item = state.editingScene?.items?.firstOrNull { it.sourceId == sourceId && it.id == state.selectedItemId }
        ?: state.editingScene?.items?.firstOrNull { it.sourceId == sourceId }
    val error = (sourceStatus[sourceId] as? com.nexo.live.engine.capture.CaptureStatus.Error)?.message ?: audioErrors[sourceId]

    SourcePropertiesDialog(
        model = SourcePropertiesModel(
            source = source,
            item = item,
            channel = state.audio.firstOrNull { it.sourceId == sourceId },
            cameras = cameras,
            usbCameras = usbCameras,
            inputs = inputs,
            screenCaptureActive = screenCaptureActive,
            error = error,
        ),
        onClose = { vm.openProperties(null) },
        onUpdate = vm::updateSource,
        onRename = { vm.renameSource(sourceId, it) },
        onTransform = { t -> item?.let { vm.setTransform(it.id, t) } },
        onChannel = vm::setChannel,
        onDelete = { vm.deleteSource(sourceId) },
        onRequestScreenCapture = onRequestScreenCapture,
    )
}

private class StudioActions(
    val vm: StudioViewModel,
    val destinationsVm: DestinationsViewModel,
    val onRemoveScene: (String) -> Unit,
    val onDeleteDestination: (String) -> Unit,
    val onGoLive: () -> Unit,
    val onAddSource: (SourceKind) -> Unit,
    val needsScreenCapture: Boolean,
    val onRequestScreenCapture: () -> Unit,
)

/** Horizontal: escenas a la izquierda, lienzo al centro y dock a la derecha (mesa de control). */
@Composable
private fun LandscapeStudio(state: StudioState, dockTab: DockTab, destinations: DestinationsUi, actions: StudioActions) {
    val vm = actions.vm
    Column(Modifier.fillMaxSize()) {
        StatusStrip(state, compact = false, onSettings = { vm.openSettings(true) })
        Row(
            Modifier.weight(1f).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ScenesPanel(
                state = state,
                vertical = true,
                onSelect = vm::selectScene,
                onAdd = vm::addScene,
                onRemove = actions.onRemoveScene,
                modifier = Modifier.width(180.dp).fillMaxHeight().padding(bottom = 8.dp),
            )
            Column(Modifier.weight(1f)) {
                CanvasArea(state, vm, Modifier.weight(1f).fillMaxWidth())
                TransportBar(state, destinations.enabledCount, vm::setStudioMode, vm::transition, vm::toggleRecording, actions.onGoLive)
            }
            Dock(state, dockTab, destinations, actions, Modifier.width(320.dp).fillMaxHeight().padding(bottom = 8.dp))
        }
    }
}

/** Vertical: lienzo arriba, controles bajo el pulgar y dock ocupando el resto. */
@Composable
private fun PortraitStudio(state: StudioState, dockTab: DockTab, destinations: DestinationsUi, actions: StudioActions) {
    val vm = actions.vm
    Column(Modifier.fillMaxSize()) {
        StatusStrip(state, compact = true, onSettings = { vm.openSettings(true) })
        CanvasArea(state, vm, Modifier.fillMaxWidth().padding(horizontal = 8.dp))
        TransportBar(state, destinations.enabledCount, vm::setStudioMode, vm::transition, vm::toggleRecording, actions.onGoLive)
        ScenesPanel(
            state = state,
            vertical = false,
            onSelect = vm::selectScene,
            onAdd = vm::addScene,
            onRemove = actions.onRemoveScene,
            modifier = Modifier.fillMaxWidth().height(104.dp).padding(horizontal = 8.dp),
        )
        Dock(state, dockTab, destinations, actions, Modifier.weight(1f).fillMaxWidth().padding(8.dp))
    }
}

/** Fuera del modo estudio hay un solo lienzo; dentro, previo (editable) y programa lado a lado. */
@Composable
private fun CanvasArea(state: StudioState, vm: StudioViewModel, modifier: Modifier) {
    val sourceStatus by vm.sourceStatus.collectAsStateWithLifecycle()
    if (!state.studioMode) {
        PreviewCanvas(
            canvas = state.canvas,
            scene = state.programScene,
            sources = state.sources,
            selectedItemId = state.selectedItemId,
            slot = PreviewSlot.Edit,
            sourceStatus = sourceStatus,
            onSurface = vm::setPreviewSurface,
            onSelect = vm::selectItem,
            onTransform = vm::setTransform,
            modifier = modifier,
        )
        return
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("PREVIO", style = Nexo.panelLabel, color = Nexo.colors.volt, modifier = Modifier.padding(bottom = 4.dp))
            PreviewCanvas(
                canvas = state.canvas,
                scene = state.previewScene,
                sources = state.sources,
                selectedItemId = state.selectedItemId,
                slot = PreviewSlot.Edit,
                sourceStatus = sourceStatus,
                onSurface = vm::setPreviewSurface,
                onSelect = vm::selectItem,
                onTransform = vm::setTransform,
                frameColor = Nexo.colors.volt,
            )
        }
        Column(Modifier.weight(1f)) {
            Text("PROGRAMA", style = Nexo.panelLabel, color = Nexo.colors.live, modifier = Modifier.padding(bottom = 4.dp))
            PreviewCanvas(
                canvas = state.canvas,
                scene = state.programScene,
                sources = state.sources,
                selectedItemId = null,
                slot = PreviewSlot.Program,
                sourceStatus = sourceStatus,
                onSurface = vm::setPreviewSurface,
                onSelect = {},
                onTransform = { _, _ -> },
                frameColor = Nexo.colors.live,
                editable = false,
            )
        }
    }
}

@Composable
private fun Dock(state: StudioState, tab: DockTab, destinations: DestinationsUi, actions: StudioActions, modifier: Modifier) {
    val vm = actions.vm
    val dvm = actions.destinationsVm
    Panel(modifier) {
        DockTabs(DockTab.entries, tab, { it.label }, vm::showDock)
        when (tab) {
            DockTab.Sources -> SourcesPanel(
                state = state,
                onAdd = actions.onAddSource,
                onSelect = vm::selectItem,
                onToggleVisibility = vm::toggleVisibility,
                onToggleLock = vm::toggleLock,
                onRaise = vm::raise,
                onLower = vm::lower,
                onRemove = vm::removeItem,
                onProperties = vm::openProperties,
                needsScreenCapture = actions.needsScreenCapture,
                onRequestScreenCapture = actions.onRequestScreenCapture,
            )
            DockTab.Mixer -> {
                val levels by vm.levels.collectAsStateWithLifecycle()
                val errors by vm.audioErrors.collectAsStateWithLifecycle()
                val inputs by vm.audioInputs.collectAsStateWithLifecycle()
                val monitorStatus by vm.monitorStatus.collectAsStateWithLifecycle()
                MixerPanel(state, levels, errors, inputs, monitorStatus, vm::setGain, vm::toggleMute, vm::openProperties)
            }
            DockTab.Destinations -> DestinationsPanel(
                ui = destinations,
                onAdd = dvm::openNew,
                onToggle = dvm::setEnabled,
                onTest = dvm::test,
                onEdit = dvm::openEdit,
                onDelete = actions.onDeleteDestination,
            )
        }
    }
}

@Composable
private fun ConfirmDelete(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    enabled: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(enabled = enabled, onClick = { onConfirm(); onDismiss() }) {
                Text("Eliminar", color = Nexo.colors.live)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        containerColor = Nexo.colors.raised,
    )
}
