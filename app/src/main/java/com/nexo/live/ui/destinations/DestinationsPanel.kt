// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Nexo Live contributors

package com.nexo.live.ui.destinations

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
import com.nexo.live.engine.model.DestinationStatus
import com.nexo.live.engine.output.ConnectionLink
import com.nexo.live.engine.output.PlatformInfo
import com.nexo.live.engine.output.StreamProtocol
import com.nexo.live.ui.studio.ToolButton
import com.nexo.live.ui.theme.Nexo

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
                style = Nexo.numeric,
                color = Nexo.colors.textLow,
                modifier = Modifier.weight(1f),
            )
            ToolButton(Icons.Outlined.Add, "Añadir destino", onAdd, tint = Nexo.colors.volt)
        }

        if (ui.rows.isEmpty()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Conecta Twitch, YouTube, Kick, TikTok o cualquier servidor RTMP/SRT. " +
                        "Pega el enlace de conexión de la plataforma y Nexo separa servidor y clave.",
                    color = Nexo.colors.textMid,
                )
                Text(
                    "AÑADIR DESTINO",
                    style = Nexo.panelLabel,
                    color = Nexo.colors.onVolt,
                    modifier = Modifier
                        .background(Nexo.colors.volt, RoundedCornerShape(Nexo.metrics.radiusSmall))
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
    val c = Nexo.colors
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
                    style = Nexo.numeric, color = c.textLow, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(statusText, style = Nexo.numeric, color = statusColor, maxLines = 2)
            }
            Switch(
                checked = row.destination.enabled,
                onCheckedChange = { onToggle(id, it) },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = c.volt,
                    checkedThumbColor = c.onVolt,
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
                Text(warning, style = Nexo.numeric, color = c.record, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

@Composable
private fun statusLine(row: DestinationRow): Pair<String, Color> {
    val c = Nexo.colors
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

/** Monograma propio en lugar de logotipos de marca: coherente con la identidad Nexo. */
@Composable
fun PlatformMonogram(platform: PlatformInfo, active: Boolean = true, size: Int = 40) {
    val c = Nexo.colors
    val shape = RoundedCornerShape(Nexo.metrics.radiusSmall)
    Box(
        Modifier
            .size(size.dp)
            .background(c.raised, shape)
            .border(Nexo.metrics.hairline, if (active) c.volt.copy(alpha = 0.6f) else c.line, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            platform.monogram,
            color = if (active) c.volt else c.textLow,
            fontWeight = FontWeight.Black,
            fontSize = (size * 0.32f).sp,
            letterSpacing = 0.5.sp,
        )
    }
}
