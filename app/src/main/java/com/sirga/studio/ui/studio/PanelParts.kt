// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sirga Studio contributors

package com.sirga.studio.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.sirga.studio.ui.theme.Sirga

/** Panel plano con filete: la pieza base de la mesa de control. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(Sirga.metrics.radius)
    Column(
        modifier
            .clip(shape)
            .background(Sirga.colors.panel)
            .border(Sirga.metrics.hairline, Sirga.colors.line, shape),
        content = content,
    )
}

@Composable
fun PanelHeader(title: String, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = Sirga.panelLabel, color = Sirga.colors.textMid, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), content = actions)
    }
}

@Composable
fun ToolButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Sirga.colors.textMid,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .size(36.dp)
            .clip(RoundedCornerShape(Sirga.metrics.radiusSmall))
            .clickable(enabled = enabled, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = if (enabled) tint else Sirga.colors.textLow, modifier = Modifier.size(20.dp))
    }
}

/** Pestañas del dock como segmentos planos; la activa se marca con subrayado azul. */
@Composable
fun <T> DockTabs(tabs: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().height(40.dp)) {
        tabs.forEach { tab ->
            val active = tab == selected
            Column(
                Modifier
                    .weight(1f)
                    .clickable { onSelect(tab) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    label(tab),
                    style = Sirga.panelLabel,
                    color = if (active) Sirga.colors.textHigh else Sirga.colors.textLow,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                Box(
                    Modifier
                        .height(2.dp)
                        .fillMaxWidth(0.4f)
                        .background(if (active) Sirga.colors.accent else Color.Transparent)
                )
            }
        }
    }
}
