// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Nexo Live contributors

package com.nexo.live.ui.studio

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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexo.live.engine.model.Scene
import com.nexo.live.engine.model.StreamDestination
import com.nexo.live.engine.studio.StudioState
import com.nexo.live.ui.destinations.DestinationEditor
import com.nexo.live.ui.destinations.DestinationsPanel
import com.nexo.live.ui.destinations.DestinationsUi
import com.nexo.live.ui.destinations.DestinationsViewModel
import com.nexo.live.ui.theme.Nexo
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

@Composable
fun StudioScreen(vm: StudioViewModel, destinationsVm: DestinationsViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val dockTab by vm.dockTab.collectAsStateWithLifecycle()
    val destinations by destinationsVm.ui.collectAsStateWithLifecycle()
    val editor by destinationsVm.editor.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var sceneToDelete by remember { mutableStateOf<Scene?>(null) }
    var destinationToDelete by remember { mutableStateOf<StreamDestination?>(null) }

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

    val actions = StudioActions(
        vm = vm,
        destinationsVm = destinationsVm,
        onRemoveScene = { id -> sceneToDelete = state.scenes.firstOrNull { it.id == id } },
        onDeleteDestination = { id -> destinationToDelete = destinations.rows.firstOrNull { it.destination.id == id }?.destination },
        onGoLive = { if (!destinationsVm.goLive()) vm.showDock(DockTab.Destinations) },
    )

    Scaffold(containerColor = Nexo.colors.ink, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            if (maxWidth > maxHeight) {
                LandscapeStudio(state, dockTab, destinations, actions)
            } else {
                PortraitStudio(state, dockTab, destinations, actions)
            }
        }
    }

    editor?.let { DestinationEditor(it, destinationsVm) }

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

private class StudioActions(
    val vm: StudioViewModel,
    val destinationsVm: DestinationsViewModel,
    val onRemoveScene: (String) -> Unit,
    val onDeleteDestination: (String) -> Unit,
    val onGoLive: () -> Unit,
)

/** Horizontal: escenas a la izquierda, lienzo al centro y dock a la derecha (mesa de control). */
@Composable
private fun LandscapeStudio(state: StudioState, dockTab: DockTab, destinations: DestinationsUi, actions: StudioActions) {
    val vm = actions.vm
    Column(Modifier.fillMaxSize()) {
        StatusStrip(state, compact = false)
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
            Dock(state, dockTab, destinations, actions, Modifier.width(300.dp).fillMaxHeight().padding(bottom = 8.dp))
        }
    }
}

/** Vertical: lienzo arriba, controles bajo el pulgar y dock ocupando el resto. */
@Composable
private fun PortraitStudio(state: StudioState, dockTab: DockTab, destinations: DestinationsUi, actions: StudioActions) {
    val vm = actions.vm
    Column(Modifier.fillMaxSize()) {
        StatusStrip(state, compact = true)
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
    if (!state.studioMode) {
        PreviewCanvas(
            canvas = state.canvas,
            scene = state.programScene,
            sources = state.sources,
            selectedItemId = state.selectedItemId,
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
                onSelect = {},
                onTransform = { _, _ -> },
                frameColor = Nexo.colors.live,
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
                onAdd = vm::addSource,
                onSelect = vm::selectItem,
                onToggleVisibility = vm::toggleVisibility,
                onToggleLock = vm::toggleLock,
                onRaise = vm::raise,
                onLower = vm::lower,
                onRemove = vm::removeItem,
            )
            DockTab.Mixer -> MixerPanel(state, vm::setGain, vm::toggleMute)
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
