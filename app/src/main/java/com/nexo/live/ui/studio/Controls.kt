// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Nexo Live contributors

package com.nexo.live.ui.studio

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexo.live.engine.model.LiveStatus
import com.nexo.live.engine.studio.StudioState
import com.nexo.live.engine.thermal.ThermalLevel
import com.nexo.live.ui.settings.label
import java.util.Locale
import com.nexo.live.ui.theme.Nexo

/** Franja superior: estado de emisión y telemetría, siempre legible de un vistazo. */
@Composable
fun StatusStrip(state: StudioState, compact: Boolean, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    val c = Nexo.colors
    Row(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp),
    ) {
        // En vertical no cabe todo: se quita la marca al emitir o grabar y el termómetro va sin texto
        if (!compact || !(state.isLive || state.isRecording)) Text("NEXO", style = Nexo.panelLabel, color = c.volt)
        StatusBadge(
            label = when (val live = state.live) {
                LiveStatus.Offline -> "FUERA DE AIRE"
                LiveStatus.Connecting -> "CONECTANDO"
                is LiveStatus.Live -> "EN DIRECTO"
                is LiveStatus.Reconnecting -> "RECONECTANDO ${live.attempt}"
                is LiveStatus.Failed -> "ERROR"
            },
            color = if (state.isLive) c.live else c.textLow,
            pulsing = state.isLive,
        )
        if (state.isRecording) StatusBadge("REC", c.record, pulsing = true)
        Box(Modifier.weight(1f))
        if (!compact) Metric("${state.canvas.width}×${state.canvas.height}")
        val fpsLow = state.stats.fps > 0 && state.stats.fps < state.canvas.fps * 0.8f
        if (!compact || !state.isLive || fpsLow) {
            Metric(String.format(Locale.ROOT, "%.0f/%d fps", state.stats.fps, state.canvas.fps), warn = fpsLow)
        }
        if (state.isLive) Metric("${state.stats.bitrateKbps} kbps")
        ThermalBadge(state, iconOnly = compact && (state.isLive || state.isRecording))
        Box(Modifier.size(32.dp).clickable(onClickLabel = "Ajustes", onClick = onSettings), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Settings, contentDescription = "Ajustes", tint = c.textMid, modifier = Modifier.size(18.dp))
        }
    }
}

/** Termómetro: solo aparece cuando el móvil se calienta, con color según el nivel. */
@Composable
private fun ThermalBadge(state: StudioState, iconOnly: Boolean) {
    val level = state.thermalLevel
    if (level == ThermalLevel.None) return
    val color = when (level) {
        ThermalLevel.Light -> Nexo.colors.meterMid
        ThermalLevel.Moderate -> Nexo.colors.record
        else -> Nexo.colors.live
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Thermostat, contentDescription = "Temperatura: ${level.label}", tint = color, modifier = Modifier.size(16.dp))
        if (iconOnly) return@Row
        Text(level.label.uppercase(), style = Nexo.panelLabel, color = color)
        if (state.thermalThrottled) Text(" · AHORRO", style = Nexo.panelLabel, color = color)
    }
}

@Composable
private fun StatusBadge(label: String, color: Color, pulsing: Boolean) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulse",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).alpha(if (pulsing) pulse else 1f).background(color, CircleShape))
        Text(label, style = Nexo.panelLabel, color = color, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun Metric(text: String, warn: Boolean = false) {
    Text(text, style = Nexo.numeric, color = if (warn) Nexo.colors.record else Nexo.colors.textMid, maxLines = 1)
}

/** Botonera de realización: modo estudio, transición, grabar y emitir. */
@Composable
fun TransportBar(
    state: StudioState,
    destinationCount: Int,
    onStudioMode: (Boolean) -> Unit,
    onTransition: () -> Unit,
    onRecord: () -> Unit,
    onGoLive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Nexo.colors
    Row(
        modifier.fillMaxWidth().padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportButton(
            label = "Estudio",
            icon = { Icon(Icons.Outlined.ViewColumn, null, tint = if (state.studioMode) c.onVolt else c.textMid, modifier = Modifier.size(18.dp)) },
            background = if (state.studioMode) c.volt else Color.Transparent,
            content = if (state.studioMode) c.onVolt else c.textMid,
            onClick = { onStudioMode(!state.studioMode) },
        )
        if (state.studioMode) {
            TransportButton(
                label = "Transición",
                icon = { Icon(Icons.Outlined.SwapHoriz, null, tint = c.textHigh, modifier = Modifier.size(18.dp)) },
                background = c.raised,
                content = c.textHigh,
                onClick = onTransition,
            )
        }
        Box(Modifier.weight(1f))
        TransportButton(
            label = if (state.isRecording) "Detener" else "Grabar",
            icon = { Box(Modifier.size(10.dp).background(c.record, if (state.isRecording) RoundedCornerShape(2.dp) else CircleShape)) },
            background = Color.Transparent,
            content = c.textHigh,
            onClick = onRecord,
        )
        TransportButton(
            label = when {
                state.isLive -> "Terminar"
                destinationCount > 1 -> "Emitir · $destinationCount"
                else -> "Emitir"
            },
            icon = null,
            background = c.live,
            content = Color.White,
            onClick = onGoLive,
            emphasized = true,
        )
    }
}

@Composable
private fun TransportButton(
    label: String,
    icon: (@Composable () -> Unit)?,
    background: Color,
    content: Color,
    onClick: () -> Unit,
    emphasized: Boolean = false,
) {
    val shape = RoundedCornerShape(Nexo.metrics.radiusSmall)
    Row(
        Modifier
            .height(40.dp)
            .clip(shape)
            .background(background)
            .border(Nexo.metrics.hairline, if (background == Color.Transparent) Nexo.colors.line else background, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = if (emphasized) 22.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        icon?.invoke()
        Text(label.uppercase(), color = content, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp)
    }
}
