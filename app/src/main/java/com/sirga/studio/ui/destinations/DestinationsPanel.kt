// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sirga Studio contributors

package com.sirga.studio.ui.destinations

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sirga.studio.engine.model.DestinationStatus
import com.sirga.studio.engine.output.ConnectionLink
import com.sirga.studio.engine.output.PlatformInfo
import com.sirga.studio.engine.output.StreamProtocol
import com.sirga.studio.ui.studio.ToolButton
import com.sirga.studio.ui.theme.Sirga

@Composable
fun DestinationsPanel(
    ui: DestinationsUi,
    onAdd: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onTest: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (ui.enabledCount == 0) "Ningún destino activo"
                else "${ui.enabledCount} activos · subida ≈ ${DestinationsViewModel.formatMbps(ui.uploadKbps)}",
                style = Sirga.numeric,
                color = Sirga.colors.textLow,
                modifier = Modifier.weight(1f),
            )
            ToolButton(Icons.Outlined.Add, "Añadir destino", onAdd, tint = Sirga.colors.accentText)
        }

        if (ui.rows.isEmpty()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Conecta Twitch, YouTube, Kick, TikTok o cualquier servidor RTMP/SRT. " +
                        "Pega el enlace de conexión de la plataforma y Sirga Studio separa servidor y clave.",
                    color = Sirga.colors.textMid,
                )
                Text(
                    "AÑADIR DESTINO",
                    style = Sirga.panelLabel,
                    color = Sirga.colors.onAccent,
                    modifier = Modifier
                        .background(Sirga.colors.accent, RoundedCornerShape(Sirga.metrics.radiusSmall))
                        .clickable(onClick = onAdd)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }

        LazyColumn(contentPadding = PaddingValues(bottom = 8.dp)) {
            items(ui.rows, key = { it.destination.id }) { row ->
                DestinationRowView(row, onToggle, onTest, onEdit, onDelete)
            }
        }
    }
}

@Composable
private fun DestinationRowView(
    row: DestinationRow,
    onToggle: (String, Boolean) -> Unit,
    onTest: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val c = Sirga.colors
    val id = row.destination.id
    var menuOpen by remember { mutableStateOf(false) }
    val (statusText, statusColor) = statusLine(row)

    Column(Modifier.fillMaxWidth().clickable { onEdit(id) }.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlatformMonogram(row.platform, active = row.destination.enabled)
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text(row.destination.name, color = c.textHigh, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    ConnectionLink.hostOf(row.destination.server) ?: row.destination.server,
                    style = Sirga.numeric, color = c.textLow, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(statusText, style = Sirga.numeric, color = statusColor, maxLines = 2)
            }
            Switch(
                checked = row.destination.enabled,
                onCheckedChange = { onToggle(id, it) },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = c.accent,
                    checkedThumbColor = c.onAccent,
                    uncheckedTrackColor = c.raised,
                    uncheckedThumbColor = c.textLow,
                    uncheckedBorderColor = c.line,
                ),
            )
            Box {
                ToolButton(Icons.Outlined.MoreVert, "Opciones de ${row.destination.name}", { menuOpen = true })
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Probar conexión") },
                        leadingIcon = { Icon(Icons.Outlined.NetworkCheck, null) },
                        onClick = { menuOpen = false; onTest(id) },
                    )
                    DropdownMenuItem(
                        text = { Text("Editar") },
                        leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                        onClick = { menuOpen = false; onEdit(id) },
                    )
                    DropdownMenuItem(
                        text = { Text("Eliminar", color = c.live) },
                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = c.live) },
                        onClick = { menuOpen = false; onDelete(id) },
                    )
                }
            }
        }
        row.warnings.forEach { warning ->
            Row(Modifier.padding(start = 50.dp, top = 4.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.WarningAmber, null, tint = c.record, modifier = Modifier.size(14.dp).padding(top = 2.dp))
                Text(warning, style = Sirga.numeric, color = c.record, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

@Composable
private fun statusLine(row: DestinationRow): Pair<String, Color> {
    val c = Sirga.colors
    return when (val live = row.live) {
        is DestinationStatus.Live -> "AL AIRE" + (if (live.bitrateKbps > 0) " · ${DestinationsViewModel.formatMbps(live.bitrateKbps)}" else "") to c.live
        DestinationStatus.Connecting -> "Conectando…" to c.textMid
        is DestinationStatus.Reconnecting -> "Reconectando (intento ${live.attempt})" to c.record
        is DestinationStatus.Failed -> live.reason to c.live
        else -> when (val test = row.test) {
            DestinationStatus.Connecting -> "Probando conexión…" to c.textMid
            DestinationStatus.TestPassed -> "Conexión aceptada por el servidor" to c.meterLow
            is DestinationStatus.Failed -> test.reason to c.live
            else -> if (!row.hasKey && row.platform.protocol == StreamProtocol.Rtmp) {
                "Falta la clave" to c.record
            } else {
                "Listo" to c.textLow
            }
        }
    }
}

/** Monograma propio en lugar de logotipos de marca: coherente con la identidad Sirga. */
@Composable
fun PlatformMonogram(platform: PlatformInfo, active: Boolean = true, size: Int = 40) {
    val c = Sirga.colors
    val shape = RoundedCornerShape(Sirga.metrics.radiusSmall)
    Box(
        Modifier
            .size(size.dp)
            .background(c.raised, shape)
            .border(Sirga.metrics.hairline, if (active) c.accent.copy(alpha = 0.6f) else c.line, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            platform.monogram,
            color = if (active) c.accentText else c.textLow,
            fontWeight = FontWeight.Black,
            fontSize = (size * 0.32f).sp,
            letterSpacing = 0.5.sp,
        )
    }
}
