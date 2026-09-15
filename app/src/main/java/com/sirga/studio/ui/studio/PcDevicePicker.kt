// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sirga Studio contributors

package com.sirga.studio.ui.studio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sirga.studio.engine.model.Source
import com.sirga.studio.engine.pclink.PcDevice
import com.sirga.studio.engine.pclink.PcDeviceKind
import com.sirga.studio.engine.pclink.PcLinkStatus
import com.sirga.studio.ui.destinations.SectionLabel
import com.sirga.studio.ui.theme.Sirga

/**
 * Elige una cámara o un micrófono conectado al PC. Los dispositivos llegan por la conexión de cada
 * fuente PC, así que primero tiene que haber una fuente PC con Sirga Studio PC conectado.
 */
@Composable
fun PcDevicePickerDialog(
    kind: PcDeviceKind,
    pcSources: List<Source.PcInput>,
    devices: Map<String, List<PcDevice>>,
    statuses: Map<String, PcLinkStatus>,
    onPick: (pcSourceId: String, device: PcDevice) -> Unit,
    onAddPcSource: () -> Unit,
    onDismiss: () -> Unit,
) {
    val camera = kind == PcDeviceKind.Camera
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (camera) "Cámara del PC" else "Micrófono del PC") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pcSources.isEmpty()) {
                    Text(
                        "Las cámaras y los micrófonos del ordenador llegan por la conexión con Sirga Studio PC. " +
                            "Primero añade la fuente «PC» y conecta el programa del ordenador.",
                        color = Sirga.colors.textMid,
                    )
                    TextButton(onClick = onAddPcSource) { Text("Añadir la fuente PC", color = Sirga.colors.accentText) }
                }
                pcSources.forEach { pc ->
                    SectionLabel(pc.name.uppercase())
                    val status = statuses[pc.id]
                    val list = devices[pc.id].orEmpty().filter { it.kind == kind }
                    when {
                        status !is PcLinkStatus.Connected -> Text(
                            "Sirga Studio PC no está conectado a esta fuente. Ábrelo en el ordenador y pulsa «Conectar» (código ${pc.code}).",
                            color = Sirga.colors.textMid,
                        )
                        list.isEmpty() -> Text(
                            if (camera) "«${status.pcName}» no tiene ninguna cámara conectada." else "«${status.pcName}» no tiene ningún micrófono conectado.",
                            color = Sirga.colors.textMid,
                        )
                        else -> list.forEach { device ->
                            Row(
                                Modifier.fillMaxWidth().clickable(onClickLabel = "Añadir ${device.name}") { onPick(pc.id, device) }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(if (camera) SourceKindIcons.pcCamera else SourceKindIcons.pcMicrophone, null, tint = Sirga.colors.textMid, modifier = Modifier.size(20.dp))
                                Text(device.name, color = Sirga.colors.textHigh, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                                Icon(Icons.Outlined.Add, contentDescription = null, tint = Sirga.colors.accentText, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
        containerColor = Sirga.colors.raised,
    )
}
