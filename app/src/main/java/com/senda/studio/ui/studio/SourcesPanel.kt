// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Senda Studio contributors

package com.senda.studio.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.senda.studio.engine.model.SourceKind
import com.senda.studio.engine.model.kind
import com.senda.studio.engine.studio.StudioState
import com.senda.studio.ui.destinations.Callout
import com.senda.studio.ui.destinations.PrimaryAction
import com.senda.studio.ui.theme.Senda

@Composable
fun SourcesPanel(
    state: StudioState,
    onAdd: (SourceKind) -> Unit,
    onSelect: (String) -> Unit,
    onToggleVisibility: (String) -> Unit,
    onToggleLock: (String) -> Unit,
    onRaise: (String) -> Unit,
    onLower: (String) -> Unit,
    onRemove: (String) -> Unit,
    onProperties: (String) -> Unit,
    needsScreenCapture: Boolean,
    onRequestScreenCapture: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // Se muestran como capas: la de arriba de la lista es la que queda encima en el lienzo
    val layers = state.editingScene?.items.orEmpty().asReversed()

    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                state.editingScene?.let { "Capas de «${it.name}»" } ?: "Sin escena",
                color = Senda.colors.textLow,
                style = Senda.numeric,
                modifier = Modifier.weight(1f),
            )
            Box {
                ToolButton(Icons.Outlined.Add, "Añadir fuente", { menuOpen = true }, tint = Senda.colors.accentText)
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    SourceKind.entries.forEach { kind ->
                        DropdownMenuItem(
                            text = { Text(kind.label) },
                            leadingIcon = { Icon(kind.icon, contentDescription = null) },
                            onClick = { menuOpen = false; onAdd(kind) },
                        )
                    }
                }
            }
        }

        if (needsScreenCapture) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Callout("La pantalla o el audio interno necesitan permiso de captura.", Senda.colors.record)
                Spacer(Modifier.height(6.dp))
                PrimaryAction("PERMITIR CAPTURA DE PANTALLA", onRequestScreenCapture)
            }
        }

        if (layers.isEmpty()) {
            Text(
                "Esta escena está vacía. Añade una cámara, la pantalla o un texto con +.",
                color = Senda.colors.textLow,
                modifier = Modifier.padding(16.dp),
            )
        }

        LazyColumn {
            items(layers, key = { it.id }) { item ->
                val source = state.sources[item.sourceId] ?: return@items
                val selected = item.id == state.selectedItemId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .background(if (selected) Senda.colors.raised else Color.Transparent)
                        .clickable { onSelect(item.id) }
                        .padding(start = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(3.dp).height(24.dp).background(if (selected) Senda.colors.accent else Color.Transparent))
                    Spacer(Modifier.width(8.dp))
                    Icon(source.kind.icon, contentDescription = null, tint = Senda.colors.textMid, modifier = Modifier.size(18.dp))
                    Text(
                        source.name,
                        color = if (item.visible) Senda.colors.textHigh else Senda.colors.textLow,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 10.dp).weight(1f),
                    )
                    if (selected) {
                        ToolButton(Icons.Outlined.Tune, "Propiedades de ${source.name}", { onProperties(source.id) }, tint = Senda.colors.accentText)
                        ToolButton(Icons.Outlined.KeyboardArrowUp, "Subir capa", { onRaise(item.id) })
                        ToolButton(Icons.Outlined.KeyboardArrowDown, "Bajar capa", { onLower(item.id) })
                        ToolButton(Icons.Outlined.DeleteOutline, "Quitar de la escena", { onRemove(item.id) })
                    }
                    ToolButton(
                        if (item.locked) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                        if (item.locked) "Desbloquear" else "Bloquear",
                        { onToggleLock(item.id) },
                        tint = if (item.locked) Senda.colors.textHigh else Senda.colors.textLow,
                    )
                    ToolButton(
                        if (item.visible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                        if (item.visible) "Ocultar" else "Mostrar",
                        { onToggleVisibility(item.id) },
                    )
                }
            }
        }
    }
}
