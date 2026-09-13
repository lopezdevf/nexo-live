// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Nexo Live contributors

package com.nexo.live.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexo.live.engine.model.Scene
import com.nexo.live.engine.studio.StudioState
import com.nexo.live.ui.theme.Nexo

/** Lista vertical de escenas (vista horizontal) o tira horizontal (vista vertical). */
@Composable
fun ScenesPanel(
    state: StudioState,
    vertical: Boolean,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Panel(modifier) {
        PanelHeader("ESCENAS") {
            ToolButton(Icons.Outlined.Add, "Añadir escena", onAdd)
        }
        val content: @Composable (Scene) -> Unit = { scene ->
            SceneCard(
                scene = scene,
                onAir = scene.id == state.programSceneId,
                inPreview = state.studioMode && scene.id == state.previewSceneId,
                onClick = { onSelect(scene.id) },
                onLongClick = { onRemove(scene.id) },
                modifier = if (vertical) Modifier.fillMaxWidth() else Modifier.width(132.dp),
            )
        }
        if (vertical) {
            LazyColumn(
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) { items(state.scenes, key = { it.id }) { content(it) } }
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) { items(state.scenes, key = { it.id }) { content(it) } }
        }
    }
}

@Composable
private fun SceneCard(
    scene: Scene,
    onAir: Boolean,
    inPreview: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Nexo.colors
    val shape = RoundedCornerShape(Nexo.metrics.radiusSmall)
    val edge = when {
        onAir -> c.live
        inPreview -> c.volt
        else -> c.line
    }
    Row(
        modifier
            .height(48.dp)
            .clip(shape)
            .background(if (onAir || inPreview) c.raised else Color.Transparent)
            .border(Nexo.metrics.hairline, edge, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Eliminar escena"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Barra lateral de estado: roja = al aire, volt = en vista previa
        Box(Modifier.width(4.dp).fillMaxHeight().background(if (onAir || inPreview) edge else Color.Transparent))
        Column(Modifier.padding(horizontal = 10.dp).weight(1f)) {
            Text(scene.name, color = c.textHigh, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    onAir -> "AL AIRE"
                    inPreview -> "PREVIO"
                    else -> "${scene.items.size} elementos"
                },
                style = Nexo.numeric,
                color = if (onAir) c.live else if (inPreview) c.volt else c.textLow,
            )
        }
    }
}
