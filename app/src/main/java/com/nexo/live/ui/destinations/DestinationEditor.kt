// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Nexo Live contributors

package com.nexo.live.ui.destinations

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nexo.live.engine.output.PlatformCatalog
import com.nexo.live.engine.output.StreamProtocol
import com.nexo.live.ui.studio.ToolButton
import com.nexo.live.ui.theme.Nexo
import kotlinx.coroutines.launch

@Composable
fun DestinationEditor(state: EditorState, vm: DestinationsViewModel) {
    Dialog(
        onDismissRequest = vm::closeEditor,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Nexo.colors.ink)
                .safeDrawingPadding()
                .imePadding()
        ) {
            EditorTopBar(state, vm)
            when (state.step) {
                EditorStep.PickPlatform -> PlatformPicker(state, vm)
                EditorStep.Form -> DestinationForm(state, vm)
            }
        }
    }
}

@Composable
private fun EditorTopBar(state: EditorState, vm: DestinationsViewModel) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.step == EditorStep.Form && state.editingId == null) {
            ToolButton(Icons.AutoMirrored.Outlined.ArrowBack, "Elegir otra plataforma", vm::backToPlatforms)
        } else {
            ToolButton(Icons.Outlined.Close, "Cerrar", vm::closeEditor)
        }
        Text(
            if (state.editingId == null) "Nuevo destino" else "Editar destino",
            color = Nexo.colors.textHigh,
            modifier = Modifier.padding(start = 8.dp).weight(1f),
        )
        if (state.step == EditorStep.Form) {
            Text(
                "GUARDAR",
                style = Nexo.panelLabel,
                color = Nexo.colors.onVolt,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .background(Nexo.colors.volt, RoundedCornerShape(Nexo.metrics.radiusSmall))
                    .clickable(onClick = vm::save)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun PlatformPicker(state: EditorState, vm: DestinationsViewModel) {
    val paste = rememberPaste()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 104.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("PEGA EL ENLACE DE CONEXIÓN")
                Text(
                    "Copia en tu plataforma el servidor y la clave (o el enlace completo) y pégalo aquí. " +
                        "Nexo detecta la plataforma y separa la clave.",
                    color = Nexo.colors.textMid,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NexoField(
                        value = state.linkDraft,
                        onValueChange = vm::editLinkDraft,
                        label = "rtmp://…  ·  rtmps://…  ·  srt://…",
                        error = state.linkError,
                        modifier = Modifier.weight(1f),
                        trailing = { ToolButton(Icons.Outlined.ContentPaste, "Pegar enlace", { paste(vm::applyLink) }) },
                    )
                }
                if (state.linkDraft.isNotBlank()) {
                    PrimaryAction("USAR ENLACE") { vm.applyLink(state.linkDraft) }
                }
                SectionLabel("O ELIGE LA PLATAFORMA", Modifier.padding(top = 12.dp))
            }
        }
        items(PlatformCatalog.all, key = { it.id }) { platform ->
            val shape = RoundedCornerShape(Nexo.metrics.radius)
            Column(
                Modifier
                    .border(Nexo.metrics.hairline, Nexo.colors.line, shape)
                    .background(Nexo.colors.panel, shape)
                    .clickable { vm.pickPlatform(platform.id) }
                    .padding(vertical = 14.dp, horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PlatformMonogram(platform)
                Text(platform.name, color = Nexo.colors.textHigh, textAlign = TextAlign.Center, maxLines = 2)
            }
        }
    }
}

@Composable
private fun DestinationForm(state: EditorState, vm: DestinationsViewModel) {
    val platform = state.platform
    val paste = rememberPaste()
    val uriHandler = LocalUriHandler.current
    var showSecret by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlatformMonogram(platform, size = 48)
            Column(Modifier.padding(start = 12.dp)) {
                Text(platform.name, color = Nexo.colors.textHigh)
                Text(
                    if (platform.protocol == StreamProtocol.Srt) "SRT" else "RTMP / RTMPS",
                    style = Nexo.numeric, color = Nexo.colors.textLow,
                )
            }
        }

        NexoField(value = state.name, onValueChange = vm::editName, label = "Nombre del destino")

        SectionLabel("SERVIDOR")
        if (platform.servers.isNotEmpty()) {
            platform.servers.forEach { server ->
                ChoiceRow(
                    selected = !state.customServer && state.server.trimEnd('/') == server.url.trimEnd('/'),
                    title = server.label,
                    subtitle = server.url,
                    onClick = { vm.selectServer(server.url) },
                )
            }
            ChoiceRow(selected = state.customServer, title = "Otro servidor", subtitle = null, onClick = vm::useCustomServer)
        }
        if (platform.servers.isEmpty() || state.customServer) {
            NexoField(
                value = state.server,
                onValueChange = vm::editServer,
                label = platform.serverHint,
                error = state.serverError,
                keyboardType = KeyboardType.Uri,
                trailing = { ToolButton(Icons.Outlined.ContentPaste, "Pegar servidor", { paste(vm::applyLink) }) },
            )
        } else {
            state.serverError?.let { Text(it, color = Nexo.colors.live) }
        }
        if (state.hostMismatch) {
            Callout(
                "Este servidor no pertenece a ${platform.name}. Continúa solo si confías en quien te dio el enlace: " +
                    "tu clave se enviaría a ese servidor.",
                color = Nexo.colors.record,
            )
        }

        SectionLabel(if (platform.protocol == StreamProtocol.Srt) "PARÁMETROS SRT" else "CLAVE DE EMISIÓN")
        NexoField(
            value = state.secret,
            onValueChange = vm::editSecret,
            label = when {
                state.hasStoredSecret -> "Clave guardada · déjala vacía para conservarla"
                platform.protocol == StreamProtocol.Srt -> "streamid=…&passphrase=…"
                else -> "Pega aquí tu clave"
            },
            error = state.secretError,
            keyboardType = KeyboardType.Password,
            visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
            trailing = {
                Row {
                    ToolButton(
                        if (showSecret) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        if (showSecret) "Ocultar clave" else "Mostrar clave",
                        { showSecret = !showSecret },
                    )
                    ToolButton(Icons.Outlined.ContentPaste, "Pegar clave", { paste(vm::pasteIntoSecret) })
                }
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Lock, null, tint = Nexo.colors.textLow, modifier = Modifier.size(14.dp))
            Text(
                "Se guarda cifrada en este móvil. Nunca la compartas: quien la tenga puede emitir en tu canal.",
                style = Nexo.numeric, color = Nexo.colors.textLow, modifier = Modifier.padding(start = 6.dp),
            )
        }

        SectionLabel("DÓNDE ENCONTRARLA")
        Text(platform.keyHelp, color = Nexo.colors.textMid)
        platform.keyLink?.let { link ->
            Row(
                Modifier.clickable { uriHandler.openUri(link) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, tint = Nexo.colors.volt, modifier = Modifier.size(18.dp))
                Text("Abrir panel de ${platform.name}", color = Nexo.colors.volt, modifier = Modifier.padding(start = 8.dp))
            }
        }

        val hints = buildList {
            platform.maxVideoKbps?.let { add("Máximo recomendado: $it kbps de vídeo" + (platform.maxAudioKbps?.let { a -> " y $a kbps de audio" } ?: "") + ".") }
            if (platform.keyframeSec != 2) add("Fotograma clave cada ${platform.keyframeSec} s.")
            if (platform.verticalFirst) add("Se ve en vertical: usa un lienzo 9:16.")
        }
        if (hints.isNotEmpty()) Callout(hints.joinToString("\n"), color = Nexo.colors.textMid)
    }
}

// ---- Piezas -------------------------------------------------------------------------

/** Lee el portapapeles solo cuando el usuario toca «Pegar». */
@Composable
private fun rememberPaste(): ((String) -> Unit) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return { onText ->
        scope.launch {
            val clip = clipboard.getClipEntry()?.clipData
            val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString() else null
            if (!text.isNullOrBlank()) onText(text)
        }
    }
}

@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Nexo.panelLabel, color = Nexo.colors.textLow, modifier = modifier)
}

@Composable
internal fun NexoField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    error: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
    singleLine: Boolean = true,
) {
    val c = Nexo.colors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        isError = error != null,
        supportingText = if (error != null) { { Text(error) } } else null,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false),
        visualTransformation = visualTransformation,
        trailingIcon = trailing,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = c.volt,
            unfocusedBorderColor = c.line,
            focusedLabelColor = c.volt,
            unfocusedLabelColor = c.textLow,
            cursorColor = c.volt,
            focusedTextColor = c.textHigh,
            unfocusedTextColor = c.textHigh,
            errorBorderColor = c.live,
            errorLabelColor = c.live,
            errorSupportingTextColor = c.live,
        ),
        modifier = modifier,
    )
}

@Composable
internal fun ChoiceRow(selected: Boolean, title: String, subtitle: String?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Nexo.metrics.radiusSmall)
    Row(
        Modifier
            .fillMaxWidth()
            .border(Nexo.metrics.hairline, if (selected) Nexo.colors.volt else Nexo.colors.line, shape)
            .clickable(onClick = onClick)
            .padding(end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = Nexo.colors.volt, unselectedColor = Nexo.colors.textLow),
        )
        Column {
            Text(title, color = Nexo.colors.textHigh)
            subtitle?.let { Text(it, style = Nexo.numeric, color = Nexo.colors.textLow) }
        }
    }
}

@Composable
internal fun Callout(text: String, color: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Nexo.colors.panel, RoundedCornerShape(Nexo.metrics.radiusSmall))
            .border(Nexo.metrics.hairline, color.copy(alpha = 0.5f), RoundedCornerShape(Nexo.metrics.radiusSmall))
            .padding(12.dp),
    ) {
        Icon(Icons.Outlined.WarningAmber, null, tint = color, modifier = Modifier.size(18.dp))
        Text(text, color = color, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
internal fun PrimaryAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = Nexo.panelLabel,
        color = Nexo.colors.onVolt,
        modifier = Modifier
            .background(Nexo.colors.volt, RoundedCornerShape(Nexo.metrics.radiusSmall))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}
