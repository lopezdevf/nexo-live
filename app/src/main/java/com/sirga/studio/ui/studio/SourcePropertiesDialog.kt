// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sirga Studio contributors

package com.sirga.studio.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sirga.studio.engine.devices.AudioDeviceEntry
import com.sirga.studio.engine.devices.CameraEntry
import com.sirga.studio.engine.devices.UsbCameraEntry
import com.sirga.studio.engine.model.AudioChannel
import com.sirga.studio.engine.model.Crop
import com.sirga.studio.engine.model.Facing
import com.sirga.studio.engine.model.FitMode
import com.sirga.studio.engine.model.MonitoringMode
import com.sirga.studio.engine.model.SceneItem
import com.sirga.studio.engine.model.Source
import com.sirga.studio.engine.model.TextAlignment
import com.sirga.studio.engine.model.Transform
import com.sirga.studio.engine.model.kind
import com.sirga.studio.engine.model.newPairingCode
import com.sirga.studio.engine.pclink.PcLinkAddresses
import com.sirga.studio.engine.pclink.PcLinkStatus
import com.sirga.studio.ui.destinations.Callout
import com.sirga.studio.ui.destinations.ChoiceRow
import com.sirga.studio.ui.destinations.SirgaField
import com.sirga.studio.ui.destinations.PrimaryAction
import com.sirga.studio.ui.destinations.SectionLabel
import com.sirga.studio.ui.theme.Sirga
import java.util.Locale

/** Todo lo que se puede configurar de una fuente y de su capa en la escena actual. */
class SourcePropertiesModel(
    val source: Source,
    val item: SceneItem?,
    val channel: AudioChannel?,
    val cameras: List<CameraEntry>,
    val usbCameras: List<UsbCameraEntry>,
    val inputs: List<AudioDeviceEntry>,
    val screenCaptureActive: Boolean,
    val error: String?,
    val pcLink: PcLinkStatus? = null,
)

@Composable
fun SourcePropertiesDialog(
    model: SourcePropertiesModel,
    onClose: () -> Unit,
    onUpdate: (Source) -> Unit,
    onRename: (String) -> Unit,
    onTransform: (Transform) -> Unit,
    onChannel: (AudioChannel) -> Unit,
    onDelete: () -> Unit,
    onRequestScreenCapture: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val source = model.source

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Sirga.colors.ink).safeDrawingPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                ToolButton(Icons.Outlined.Close, "Cerrar", onClose)
                Text("Propiedades · ${source.kind.label}", color = Sirga.colors.textHigh, modifier = Modifier.padding(start = 8.dp).weight(1f))
                ToolButton(Icons.Outlined.DeleteOutline, "Eliminar fuente", { confirmDelete = true }, tint = Sirga.colors.live)
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SirgaField(value = source.name, onValueChange = onRename, label = "Nombre")
                model.error?.let { Callout(it, Sirga.colors.record) }

                when (source) {
                    is Source.Camera -> CameraSection(source, model.cameras, onUpdate)
                    is Source.UsbCamera -> UsbCameraSection(source, model.usbCameras, onUpdate)
                    is Source.PcInput -> PcInputSection(source, model.pcLink, onUpdate)
                    is Source.Screen -> ScreenSection(model.screenCaptureActive, onRequestScreenCapture)
                    is Source.Text -> TextSection(source, onUpdate)
                    is Source.SolidColor -> {
                        SectionLabel("COLOR")
                        ColorSwatches(source.argb, SOLID_COLORS) { onUpdate(source.copy(argb = it)) }
                    }
                    is Source.Image -> Text("La imagen se guarda dentro de la app, así que no se pierde si la borras de la galería.", color = Sirga.colors.textMid)
                    is Source.Microphone -> MicrophoneSection(source, model.inputs, onUpdate)
                    is Source.InternalAudio -> ScreenSection(model.screenCaptureActive, onRequestScreenCapture, audio = true)
                }

                model.channel?.let { AudioChannelSection(it, onChannel) }
                model.item?.let { TransformSection(it.transform, onTransform) }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("¿Eliminar «${source.name}»?") },
            text = { Text("Se quita de todas las escenas y del mezclador. Para quitarla solo de esta escena usa la papelera de la capa.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Eliminar", color = Sirga.colors.live) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
            containerColor = Sirga.colors.raised,
        )
    }
}

// ---- Secciones por tipo ---------------------------------------------------------------------

@Composable
private fun CameraSection(source: Source.Camera, cameras: List<CameraEntry>, onUpdate: (Source) -> Unit) {
    SectionLabel("CÁMARA")
    // Si el nombre sigue siendo uno automático, se actualiza con la cámara elegida
    val autoNames = setOf("Cámara", "Cámara trasera", "Cámara frontal", "Cámara externa USB") + cameras.map { it.label }
    fun named(label: String) = if (source.name in autoNames) label else source.name
    ChoiceRow(source.cameraId == null && source.facing == Facing.Back, "Trasera principal", "Se elige sola en cualquier móvil") {
        onUpdate(source.copy(cameraId = null, facing = Facing.Back, name = named("Cámara trasera")))
    }
    ChoiceRow(source.cameraId == null && source.facing == Facing.Front, "Frontal principal", null) {
        onUpdate(source.copy(cameraId = null, facing = Facing.Front, name = named("Cámara frontal")))
    }
    cameras.forEach { cam ->
        ChoiceRow(source.cameraId == cam.cameraId, cam.label, "Id ${cam.cameraId}") {
            onUpdate(source.copy(cameraId = cam.cameraId, facing = cam.facing, name = named(cam.label)))
        }
    }
    if (cameras.none { it.facing == Facing.External }) {
        Text(
            "¿Webcam o capturadora HDMI por USB? Si no aparece aquí, añade la fuente «Cámara USB / capturadora».",
            style = Sirga.numeric, color = Sirga.colors.textLow,
        )
    }
    OrientationControls(source.rotationOffset, source.mirror,
        onRotation = { onUpdate(source.copy(rotationOffset = it)) },
        onMirror = { onUpdate(source.copy(mirror = it)) })
}

@Composable
private fun UsbCameraSection(source: Source.UsbCamera, devices: List<UsbCameraEntry>, onUpdate: (Source) -> Unit) {
    SectionLabel("DISPOSITIVO USB")
    Text(
        "Webcams y capturadoras HDMI compatibles con UVC. Conéctalas con un adaptador USB-C OTG; Android pedirá permiso la primera vez.",
        color = Sirga.colors.textMid,
    )
    ChoiceRow(source.deviceName == null, "La primera que se conecte", null) { onUpdate(source.copy(deviceName = null)) }
    devices.forEach { d ->
        ChoiceRow(source.deviceName == d.deviceName, d.label, d.deviceName) { onUpdate(source.copy(deviceName = d.deviceName)) }
    }
    if (devices.isEmpty()) Callout("No hay ninguna cámara USB conectada ahora mismo.", Sirga.colors.textMid)
    OrientationControls(source.rotationOffset, source.mirror,
        onRotation = { onUpdate(source.copy(rotationOffset = it)) },
        onMirror = { onUpdate(source.copy(mirror = it)) })
}

@Composable
private fun PcInputSection(source: Source.PcInput, status: PcLinkStatus?, onUpdate: (Source) -> Unit) {
    val addresses = remember { PcLinkAddresses.localAddresses() }
    val uriHandler = LocalUriHandler.current
    SectionLabel("CONEXIÓN CON EL PC")
    Text(
        "Juega en el PC y emite desde el móvil, sin capturadora ni programas de terceros. Sirga Studio PC envía la pantalla y el sonido del ordenador a esta fuente.",
        color = Sirga.colors.textMid,
    )
    when (status) {
        is PcLinkStatus.Connected -> Callout(
            buildString {
                append("Conectado a «${status.pcName}»")
                if (status.width > 0) append(" · ${status.width}×${status.height}")
                status.latencyMs?.let { append(" · retraso $it ms") }
            },
            Sirga.colors.meterLow,
            Icons.Outlined.CheckCircle,
        )
        is PcLinkStatus.Error -> Callout(status.message, Sirga.colors.record)
        is PcLinkStatus.Waiting -> status.notice?.let { Callout(it, Sirga.colors.record) }
        null -> Unit
    }

    SectionLabel("CÓDIGO PARA EL PC")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            source.code.toCharArray().joinToString(" "),
            style = Sirga.numeric.copy(fontSize = 34.sp),
            color = Sirga.colors.accentText,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onUpdate(source.copy(code = newPairingCode())) }) { Text("Cambiar", color = Sirga.colors.textMid) }
    }
    Text("Sirga Studio PC lo pide la primera vez. Cámbialo si alguien más lo conoce.", style = Sirga.numeric, color = Sirga.colors.textLow)

    SectionLabel("EN EL PC (UNA SOLA VEZ)")
    listOf(
        "1. Descarga Sirga Studio PC para Windows desde la página del proyecto.",
        "2. Ábrelo: este móvil aparece solo en la lista si está en la misma red.",
        "3. Elige la pantalla, escribe el código y pulsa «Conectar».",
    ).forEach { Text(it, color = Sirga.colors.textHigh) }
    PrimaryAction("DESCARGAR SIRGA STUDIO PC") { uriHandler.openUri(PC_DOWNLOAD_URL) }

    SectionLabel("DIRECCIÓN MANUAL")
    if (addresses.isEmpty()) {
        Callout("El móvil no tiene red. Conéctalo a la misma WiFi que el PC o activa el anclaje USB.", Sirga.colors.record)
    }
    addresses.forEach { address ->
        Column(Modifier.fillMaxWidth().background(Sirga.colors.panel, RoundedCornerShape(Sirga.metrics.radiusSmall)).padding(12.dp)) {
            Text(address.label, style = Sirga.numeric, color = Sirga.colors.textLow)
            Text("${address.ip}:${source.port}", color = Sirga.colors.accentText)
        }
    }
    Text(
        "Solo hace falta si el PC no encuentra el móvil (por ejemplo, en redes que bloquean la búsqueda).",
        style = Sirga.numeric, color = Sirga.colors.textLow,
    )
    Callout(
        "Menos retraso: conecta el móvil al PC por cable y activa Ajustes → Conexiones → Zona WiFi y anclaje → Anclaje USB. " +
            "Por WiFi, mejor con el PC conectado al router por cable.",
        Sirga.colors.textMid,
    )

    SectionLabel("PUERTO")
    Chips(listOf(9000, 9001, 9002, 9003), source.port, { "$it" }) { onUpdate(source.copy(port = it)) }
}

private const val PC_DOWNLOAD_URL = "https://github.com/lopezdevf/sirga-studio/releases/latest"

@Composable
private fun OrientationControls(rotation: Int, mirror: Boolean, onRotation: (Int) -> Unit, onMirror: (Boolean) -> Unit) {
    SectionLabel("ORIENTACIÓN")
    Chips(listOf(0, 90, 180, 270), rotation, { "$it°" }, onRotation)
    SwitchRow("Espejo horizontal", "Útil para la cámara frontal si prefieres verte como en un espejo", mirror, onMirror)
}

@Composable
private fun ScreenSection(active: Boolean, onRequest: () -> Unit, audio: Boolean = false) {
    SectionLabel(if (audio) "AUDIO INTERNO" else "PANTALLA")
    Text(
        if (audio) "Captura el sonido de juegos y apps (Android 10+). Algunas apps bloquean la captura por derechos de autor."
        else "Muestra lo que ves en el móvil. Al abrir un juego, el directo sigue en segundo plano.",
        color = Sirga.colors.textMid,
    )
    if (active) {
        Callout("Captura de pantalla permitida para esta sesión.", Sirga.colors.meterLow)
    } else {
        PrimaryAction("PERMITIR CAPTURA DE PANTALLA", onRequest)
    }
}

@Composable
private fun TextSection(source: Source.Text, onUpdate: (Source) -> Unit) {
    SectionLabel("TEXTO")
    SirgaField(value = source.text, onValueChange = { onUpdate(source.copy(text = it)) }, label = "Contenido", singleLine = false)
    SectionLabel("COLOR DEL TEXTO")
    ColorSwatches(source.colorArgb, TEXT_COLORS) { onUpdate(source.copy(colorArgb = it)) }
    SectionLabel("FONDO")
    ColorSwatches(source.backgroundArgb, BACKGROUNDS) { onUpdate(source.copy(backgroundArgb = it)) }
    SwitchRow("Negrita", null, source.bold) { onUpdate(source.copy(bold = it)) }
    SectionLabel("ALINEACIÓN")
    Chips(TextAlignment.entries, source.alignment, {
        when (it) {
            TextAlignment.Start -> "Izquierda"
            TextAlignment.Center -> "Centro"
            TextAlignment.End -> "Derecha"
        }
    }) { onUpdate(source.copy(alignment = it)) }
}

@Composable
private fun MicrophoneSection(source: Source.Microphone, inputs: List<AudioDeviceEntry>, onUpdate: (Source) -> Unit) {
    SectionLabel("MICRÓFONO")
    ChoiceRow(source.device == null, "Predeterminado del sistema", "Cambia solo al conectar unos audífonos con micrófono") {
        onUpdate(source.copy(device = null))
    }
    inputs.forEach { entry ->
        ChoiceRow(source.device == entry.key, entry.label, if (entry.limitedQuality) "Calidad de llamada y algo de retardo" else null) {
            onUpdate(source.copy(device = entry.key))
        }
    }
    if (inputs.firstOrNull { it.key == source.device }?.limitedQuality == true) {
        Callout("Bluetooth limita el micrófono a calidad de llamada. Para un sonido limpio usa un micrófono USB o con cable.", Sirga.colors.record)
    }
    SectionLabel("PROCESADO")
    SwitchRow("Reducción de ruido", "Filtra ventiladores y ruido de fondo constante", source.noiseSuppression) { onUpdate(source.copy(noiseSuppression = it)) }
    SwitchRow("Cancelación de eco", "Evita que el micrófono capte lo que suena por el altavoz", source.echoCancellation) { onUpdate(source.copy(echoCancellation = it)) }
    SwitchRow("Estéreo", "Solo en micrófonos que graban en dos canales", source.stereo) { onUpdate(source.copy(stereo = it)) }
}

@Composable
private fun AudioChannelSection(channel: AudioChannel, onChannel: (AudioChannel) -> Unit) {
    SectionLabel("MEZCLADOR")
    LabeledSlider("Volumen", String.format(Locale.ROOT, "%+.1f dB", channel.gainDb), channel.gainDb, -60f..6f) { onChannel(channel.copy(gainDb = it)) }
    LabeledSlider("Balance", when {
        channel.balance < -0.05f -> "Izq. ${(-channel.balance * 100).toInt()} %"
        channel.balance > 0.05f -> "Der. ${(channel.balance * 100).toInt()} %"
        else -> "Centro"
    }, channel.balance, -1f..1f) { onChannel(channel.copy(balance = it)) }
    SectionLabel("MONITORIZACIÓN")
    Chips(MonitoringMode.entries, channel.monitoring, { it.label }) { onChannel(channel.copy(monitoring = it)) }
    Text("Se escucha por los audífonos conectados. Elige la salida en Ajustes → Audio.", style = Sirga.numeric, color = Sirga.colors.textLow)
}

@Composable
private fun TransformSection(t: Transform, onTransform: (Transform) -> Unit) {
    SectionLabel("CAPA EN ESTA ESCENA")
    SectionLabel("ENCAJE")
    Chips(FitMode.entries, t.fit, {
        when (it) {
            FitMode.Cover -> "Llenar"
            FitMode.Contain -> "Encajar"
            FitMode.Stretch -> "Estirar"
        }
    }) { onTransform(t.copy(fit = it)) }
    LabeledSlider("Opacidad", "${(t.opacity * 100).toInt()} %", t.opacity, 0f..1f) { onTransform(t.copy(opacity = it)) }
    LabeledSlider("Giro", "${t.rotation.toInt()}°", t.rotation, -180f..180f) { onTransform(t.copy(rotation = it)) }
    SectionLabel("RECORTE")
    LabeledSlider("Izquierda", pct(t.crop.left), t.crop.left, 0f..0.45f) { onTransform(t.copy(crop = t.crop.copy(left = it))) }
    LabeledSlider("Derecha", pct(t.crop.right), t.crop.right, 0f..0.45f) { onTransform(t.copy(crop = t.crop.copy(right = it))) }
    LabeledSlider("Arriba", pct(t.crop.top), t.crop.top, 0f..0.45f) { onTransform(t.copy(crop = t.crop.copy(top = it))) }
    LabeledSlider("Abajo", pct(t.crop.bottom), t.crop.bottom, 0f..0.45f) { onTransform(t.copy(crop = t.crop.copy(bottom = it))) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PrimaryAction("PANTALLA COMPLETA") { onTransform(Transform(fit = t.fit)) }
        PrimaryAction("QUITAR RECORTE") { onTransform(t.copy(crop = Crop())) }
    }
}

// ---- Piezas reutilizables -----------------------------------------------------------------------

val MonitoringMode.label: String
    get() = when (this) {
        MonitoringMode.Off -> "Sin monitorizar"
        MonitoringMode.MonitorOnly -> "Solo escuchar"
        MonitoringMode.MonitorAndOutput -> "Escuchar y emitir"
    }

@Composable
internal fun <T> Chips(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option ->
            val active = option == selected
            val shape = RoundedCornerShape(Sirga.metrics.radiusSmall)
            Text(
                label(option),
                color = if (active) Sirga.colors.onAccent else Sirga.colors.textMid,
                modifier = Modifier
                    .background(if (active) Sirga.colors.accent else Color.Transparent, shape)
                    .border(Sirga.metrics.hairline, if (active) Sirga.colors.accent else Sirga.colors.line, shape)
                    .clickable { onSelect(option) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
internal fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Sirga.colors.textHigh)
            subtitle?.let { Text(it, style = Sirga.numeric, color = Sirga.colors.textLow) }
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Sirga.colors.accent,
                checkedThumbColor = Sirga.colors.onAccent,
                uncheckedTrackColor = Sirga.colors.raised,
                uncheckedThumbColor = Sirga.colors.textLow,
                uncheckedBorderColor = Sirga.colors.line,
            ),
        )
    }
}

@Composable
internal fun LabeledSlider(title: String, value: String, current: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, onChange: (Float) -> Unit) {
    Column {
        Row {
            Text(title, color = Sirga.colors.textHigh, modifier = Modifier.weight(1f))
            Text(value, style = Sirga.numeric, color = Sirga.colors.textMid)
        }
        Slider(
            value = current.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(thumbColor = Sirga.colors.textHigh, activeTrackColor = Sirga.colors.accent, inactiveTrackColor = Sirga.colors.line),
        )
    }
}

@Composable
private fun ColorSwatches(selected: Long, colors: List<Long>, onSelect: (Long) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        colors.forEach { argb ->
            val active = argb == selected
            Box(
                Modifier
                    .size(36.dp)
                    .border(2.dp, if (active) Sirga.colors.accent else Sirga.colors.line, CircleShape)
                    .padding(4.dp)
                    .background(checker(argb), CircleShape)
                    .clickable { onSelect(argb) }
            )
        }
    }
}

/** Los colores transparentes se muestran sobre gris para que se distingan. */
private fun checker(argb: Long): Color {
    val c = Color(argb)
    return if (c.alpha < 0.1f) Color(0xFF3A3F4B) else c
}

private fun pct(v: Float) = "${(v * 100).toInt()} %"

private val TEXT_COLORS = listOf(0xFFFFFFFF, 0xFF0D1117, 0xFF0066FF, 0xFF00A3FF, 0xFFFF4D5E, 0xFFFF9F43, 0xFFFACC15, 0xFF4ADE80, 0xFFA78BFA, 0xFFF472B6)
private val BACKGROUNDS = listOf(0x00000000, 0x99000000, 0xCC000000, 0xFF0D1117, 0xCCFFFFFF, 0xFF0066FF, 0xFFFF4D5E, 0xFF1E3A8A)
private val SOLID_COLORS = listOf(0xFF0D1117, 0xFF1C2430, 0xFF000000, 0xFFFFFFFF, 0xFF00B140, 0xFF0066FF, 0xFF00A3FF, 0xFFFF4D5E, 0xFF7C3AED, 0xFFFACC15)
