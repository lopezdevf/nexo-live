// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Nexo Live contributors

package com.nexo.live.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nexo.live.engine.devices.AudioDeviceEntry
import com.nexo.live.engine.model.BitrateMode
import com.nexo.live.engine.model.VideoCodecChoice
import com.nexo.live.engine.settings.AudioSettings
import com.nexo.live.engine.settings.StudioSettings
import com.nexo.live.engine.settings.ThermalPolicy
import com.nexo.live.engine.settings.TransitionType
import com.nexo.live.engine.settings.VideoSettings
import com.nexo.live.engine.thermal.ThermalLevel
import com.nexo.live.engine.thermal.ThermalProfile
import com.nexo.live.ui.destinations.Callout
import com.nexo.live.ui.destinations.ChoiceRow
import com.nexo.live.ui.destinations.NexoField
import com.nexo.live.ui.destinations.PrimaryAction
import com.nexo.live.ui.destinations.SectionLabel
import com.nexo.live.ui.studio.Chips
import com.nexo.live.ui.studio.LabeledSlider
import com.nexo.live.ui.studio.SwitchRow
import com.nexo.live.ui.studio.ToolButton
import com.nexo.live.ui.theme.Nexo

private const val REPO_URL = "https://github.com/lopezdevf/nexo-live"
private const val SPONSOR_URL = "https://github.com/sponsors/lopezdevf"

@Composable
fun SettingsScreen(
    settings: StudioSettings,
    outputs: List<AudioDeviceEntry>,
    thermal: ThermalProfile,
    busy: Boolean,
    onUpdate: ((StudioSettings) -> StudioSettings) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Nexo.colors.ink).safeDrawingPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                ToolButton(Icons.Outlined.Close, "Cerrar", onClose)
                Text("Ajustes", color = Nexo.colors.textHigh, modifier = Modifier.padding(start = 8.dp).weight(1f))
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (busy) Callout("En directo o grabando: resolución, fps, códec y frecuencia de audio quedan bloqueados.", Nexo.colors.record)
                VideoSection(settings, onUpdate)
                AudioSection(settings, outputs, onUpdate)
                ThermalSection(settings, thermal, onUpdate)
                RecordingSection(settings, onUpdate)
                TransitionSection(settings, onUpdate)
                AboutSection(onOpen = uriHandler::openUri)
                PrimaryAction("RESTABLECER AJUSTES", onReset)
            }
        }
    }
}

@Composable
private fun VideoSection(s: StudioSettings, onUpdate: ((StudioSettings) -> StudioSettings) -> Unit) {
    val v = s.video
    Header("VÍDEO")
    SectionLabel("RESOLUCIÓN DEL LIENZO")
    Chips(VideoSettings.RESOLUTIONS, v.width to v.height, { (w, h) -> if (w >= h) "${h}p" else "${w}×${h} vertical" }) { (w, h) ->
        onUpdate { it.copy(video = it.video.copy(width = w, height = h)) }
    }
    SectionLabel("FOTOGRAMAS POR SEGUNDO")
    Chips(VideoSettings.FPS, v.fps, { "$it fps" }) { fps -> onUpdate { it.copy(video = it.video.copy(fps = fps)) } }
    LabeledSlider("Bitrate de vídeo", "${v.bitrateKbps} kbps", v.bitrateKbps.toFloat(), VideoSettings.MIN_BITRATE.toFloat()..12_000f) { kbps ->
        onUpdate { it.copy(video = it.video.copy(bitrateKbps = (kbps / 100).toInt() * 100)) }
    }
    Text(recommendation(v), style = Nexo.numeric, color = Nexo.colors.textLow)
    LabeledSlider("Fotograma clave", "cada ${v.keyframeSec} s", v.keyframeSec.toFloat(), 1f..6f, steps = 4) { sec ->
        onUpdate { it.copy(video = it.video.copy(keyframeSec = sec.toInt())) }
    }
    SectionLabel("CÓDEC")
    Chips(VideoCodecChoice.entries, v.codec, { if (it == VideoCodecChoice.H264) "H.264 (compatible)" else "H.265 (mejor calidad)" }) { codec ->
        onUpdate { it.copy(video = it.video.copy(codec = codec)) }
    }
    if (v.codec == VideoCodecChoice.H265) {
        Callout("H.265 solo se usa si todos los destinos activos lo admiten (YouTube o servidores propios); si no, se emite en H.264.", Nexo.colors.textMid)
    }
    SectionLabel("CONTROL DE BITRATE")
    Chips(BitrateMode.entries, v.bitrateMode, { if (it == BitrateMode.Vbr) "Variable (recomendado)" else "Constante (CBR)" }) { mode ->
        onUpdate { it.copy(video = it.video.copy(bitrateMode = mode)) }
    }
    Text(
        if (v.bitrateMode == BitrateMode.Vbr) "Nítido al cambiar de escena; de media se mantiene en el bitrate elegido."
        else "Bitrate fijo: tras cada cambio de escena la imagen puede verse borrosa unos segundos.",
        style = Nexo.numeric, color = Nexo.colors.textLow,
    )
    SwitchRow("Bitrate adaptativo", "Baja la calidad si tu conexión se satura y la recupera después", v.adaptiveBitrate) { on ->
        onUpdate { it.copy(video = it.video.copy(adaptiveBitrate = on)) }
    }
    SectionLabel("FPS DE LA VISTA PREVIA")
    Chips(listOf(10, 15, 24, 30, 60), v.previewFps, { "$it" }) { fps -> onUpdate { it.copy(video = it.video.copy(previewFps = fps)) } }
    Text("Una vista previa más lenta ahorra batería y calor; no afecta al directo.", style = Nexo.numeric, color = Nexo.colors.textLow)
    SwitchRow(
        "Mantener la pantalla encendida con el estudio abierto",
        "Al emitir o grabar nunca se apaga; y si bloqueas el móvil, el directo sigue en segundo plano",
        v.keepScreenOn,
    ) { on -> onUpdate { it.copy(video = it.video.copy(keepScreenOn = on)) } }
}

@Composable
private fun AudioSection(s: StudioSettings, outputs: List<AudioDeviceEntry>, onUpdate: ((StudioSettings) -> StudioSettings) -> Unit) {
    val a = s.audio
    Header("AUDIO")
    SectionLabel("FRECUENCIA")
    Chips(AudioSettings.SAMPLE_RATES, a.sampleRate, { "${it / 1000.0} kHz" }) { rate -> onUpdate { it.copy(audio = it.audio.copy(sampleRate = rate)) } }
    SectionLabel("BITRATE DE AUDIO")
    Chips(AudioSettings.BITRATES, a.bitrateKbps, { "$it kbps" }) { kbps -> onUpdate { it.copy(audio = it.audio.copy(bitrateKbps = kbps)) } }
    SectionLabel("SALIDA DE MONITORIZACIÓN")
    ChoiceRow(a.monitorDevice == null, "Automática", "Audífonos con cable, USB o Bluetooth, en ese orden") {
        onUpdate { it.copy(audio = it.audio.copy(monitorDevice = null)) }
    }
    outputs.forEach { out ->
        ChoiceRow(a.monitorDevice == out.key, out.label, if (out.limitedQuality) "Con retardo" else null) {
            onUpdate { it.copy(audio = it.audio.copy(monitorDevice = out.key)) }
        }
    }
    SwitchRow("Permitir monitorizar por el altavoz", "Puede causar acople (pitido) con el micrófono", a.allowSpeakerMonitoring) { on ->
        onUpdate { it.copy(audio = it.audio.copy(allowSpeakerMonitoring = on)) }
    }
}

@Composable
private fun ThermalSection(s: StudioSettings, thermal: ThermalProfile, onUpdate: ((StudioSettings) -> StudioSettings) -> Unit) {
    val t = s.thermal
    Header("TEMPERATURA")
    Callout("Estado actual: ${thermal.level.label}" + (thermal.message?.let { "\n$it" } ?: ""), if (thermal.throttled) Nexo.colors.record else Nexo.colors.textMid)
    SectionLabel("PROTECCIÓN")
    ChoiceRow(t.policy == ThermalPolicy.Automatic, "Automática (recomendada)", "Reduce calidad por pasos antes de que el móvil se estrangule") {
        onUpdate { it.copy(thermal = it.thermal.copy(policy = ThermalPolicy.Automatic)) }
    }
    ChoiceRow(t.policy == ThermalPolicy.NotifyOnly, "Solo avisar", "Tú decides cuándo bajar la calidad") {
        onUpdate { it.copy(thermal = it.thermal.copy(policy = ThermalPolicy.NotifyOnly)) }
    }
    ChoiceRow(t.policy == ThermalPolicy.Off, "Desactivada", "El sistema puede cortar fps o cerrar la app si se calienta") {
        onUpdate { it.copy(thermal = it.thermal.copy(policy = ThermalPolicy.Off)) }
    }
    LabeledSlider("Fps mínimos", "${t.minFps} fps", t.minFps.toFloat(), 15f..30f, steps = 14) { v ->
        onUpdate { it.copy(thermal = it.thermal.copy(minFps = v.toInt())) }
    }
    LabeledSlider("Bitrate mínimo", "${t.minBitrateKbps} kbps", t.minBitrateKbps.toFloat(), 500f..4_000f) { v ->
        onUpdate { it.copy(thermal = it.thermal.copy(minBitrateKbps = (v / 100).toInt() * 100)) }
    }
    LabeledSlider("Límite de batería", "${t.batteryLimitC.toInt()} °C", t.batteryLimitC, 38f..46f, steps = 7) { v ->
        onUpdate { it.copy(thermal = it.thermal.copy(batteryLimitC = v)) }
    }
    LabeledSlider("Espera para recuperar calidad", "${t.recoverySeconds} s", t.recoverySeconds.toFloat(), 30f..300f) { v ->
        onUpdate { it.copy(thermal = it.thermal.copy(recoverySeconds = (v / 10).toInt() * 10)) }
    }
    SwitchRow("Permitir bajar la resolución del lienzo", "Alivia la GPU cuando el calor es severo", t.allowResolutionDrop) { on ->
        onUpdate { it.copy(thermal = it.thermal.copy(allowResolutionDrop = on)) }
    }
    SwitchRow("Detener en emergencia térmica", "Corta el directo y guarda la grabación antes de que Android cierre la app", t.stopAtEmergency) { on ->
        onUpdate { it.copy(thermal = it.thermal.copy(stopAtEmergency = on)) }
    }
    Text(
        "Consejos: quita la funda, no cargues el móvil mientras emites, baja el brillo y evita el sol directo.",
        style = Nexo.numeric, color = Nexo.colors.textLow,
    )
}

@Composable
private fun RecordingSection(s: StudioSettings, onUpdate: ((StudioSettings) -> StudioSettings) -> Unit) {
    Header("GRABACIÓN")
    NexoField(
        value = s.recording.folder,
        onValueChange = { folder -> onUpdate { it.copy(recording = it.recording.copy(folder = folder.filter { c -> c.isLetterOrDigit() || c in " _-" }.take(40))) } },
        label = "Carpeta dentro de Movies",
    )
    Text("La grabación usa el mismo codificador que el directo: calidad idéntica y sin calor extra.", style = Nexo.numeric, color = Nexo.colors.textLow)
}

@Composable
private fun TransitionSection(s: StudioSettings, onUpdate: ((StudioSettings) -> StudioSettings) -> Unit) {
    Header("TRANSICIONES")
    Chips(TransitionType.entries, s.transition.type, { if (it == TransitionType.Cut) "Corte" else "Fundido" }) { type ->
        onUpdate { it.copy(transition = it.transition.copy(type = type)) }
    }
    if (s.transition.type == TransitionType.Fade) {
        LabeledSlider("Duración", "${s.transition.durationMs} ms", s.transition.durationMs.toFloat(), 100f..1_500f) { v ->
            onUpdate { it.copy(transition = it.transition.copy(durationMs = (v / 50).toInt() * 50)) }
        }
    }
}

@Composable
private fun AboutSection(onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    Header("ACERCA DE")
    Text("Nexo Live $version", color = Nexo.colors.textHigh)
    Text(
        "Software libre bajo la GNU GPL versión 2 o posterior. Puedes usarlo, estudiarlo, modificarlo y compartirlo. " +
            "Se distribuye sin ninguna garantía.",
        color = Nexo.colors.textMid,
    )
    LinkRow("Código fuente en GitHub", REPO_URL, onOpen)
    LinkRow("Licencias y software de terceros", "$REPO_URL/blob/main/NOTICE.md", onOpen)
    LinkRow("Reportar un error", "$REPO_URL/issues", onOpen)
    Row(
        Modifier
            .fillMaxWidth()
            .background(Nexo.colors.panel, RoundedCornerShape(Nexo.metrics.radius))
            .clickable { onOpen(SPONSOR_URL) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Favorite, null, tint = Nexo.colors.live, modifier = Modifier.size(24.dp))
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text("Apoyar el proyecto", color = Nexo.colors.textHigh)
            Text("Nexo Live es gratis y sin anuncios. Tu apoyo en GitHub Sponsors mantiene el desarrollo.", style = Nexo.numeric, color = Nexo.colors.textLow)
        }
    }
}

@Composable
private fun LinkRow(title: String, url: String, onOpen: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onOpen(url) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Nexo.colors.volt, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, tint = Nexo.colors.volt, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun Header(text: String) {
    Text(text, style = Nexo.panelLabel, color = Nexo.colors.volt, modifier = Modifier.padding(top = 12.dp))
}

private fun recommendation(v: VideoSettings): String {
    val (low, high) = when (minOf(v.width, v.height)) {
        in 0..480 -> 1_000 to 2_000
        in 481..720 -> if (v.fps > 30) 4_500 to 6_000 else 2_500 to 4_500
        else -> if (v.fps > 30) 6_000 to 9_000 else 4_500 to 6_000
    }
    return "Recomendado para ${minOf(v.width, v.height)}p a ${v.fps} fps: $low–$high kbps. Tu subida debe ser al menos 1,5 veces el total."
}

val ThermalLevel.label: String
    get() = when (this) {
        ThermalLevel.None -> "normal"
        ThermalLevel.Light -> "templado"
        ThermalLevel.Moderate -> "caliente"
        ThermalLevel.Severe -> "muy caliente"
        ThermalLevel.Critical -> "crítico"
        ThermalLevel.Emergency -> "emergencia"
    }

