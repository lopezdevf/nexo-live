// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Nexo Live contributors

package com.nexo.live.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Identidad Nexo: consola de realización oscura, paneles planos con filetes finos
 * y un único acento "volt" para lo que está seleccionado. El rojo se reserva
 * exclusivamente para lo que está al aire.
 */
@Immutable
data class NexoColors(
    val ink: Color = Color(0xFF0B0C10),
    val panel: Color = Color(0xFF13151B),
    val raised: Color = Color(0xFF1B1E26),
    val line: Color = Color(0xFF282C36),
    val textHigh: Color = Color(0xFFF4F5F7),
    val textMid: Color = Color(0xFF9AA0AE),
    val textLow: Color = Color(0xFF5E6472),
    val volt: Color = Color(0xFFC8F547),
    val onVolt: Color = Color(0xFF0B0C10),
    val live: Color = Color(0xFFFF4D5E),
    val record: Color = Color(0xFFFF9F43),
    val meterLow: Color = Color(0xFF4ADE80),
    val meterMid: Color = Color(0xFFFACC15),
    val meterHigh: Color = Color(0xFFFF4D5E),
)

@Immutable
data class NexoMetrics(
    val hairline: Dp = 1.dp,
    val radiusSmall: Dp = 6.dp,
    val radius: Dp = 10.dp,
    val gap: Dp = 8.dp,
)

val LocalNexoColors = staticCompositionLocalOf { NexoColors() }
val LocalNexoMetrics = staticCompositionLocalOf { NexoMetrics() }

object Nexo {
    val colors: NexoColors @Composable get() = LocalNexoColors.current
    val metrics: NexoMetrics @Composable get() = LocalNexoMetrics.current

    /** Cifras que cambian (temporizadores, kbps): monoespaciadas para que no bailen. */
    val numeric = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 12.sp)

    /** Cabeceras de panel en versalitas, estilo mesa de control. */
    val panelLabel = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 1.2.sp)
}

@Composable
fun NexoTheme(content: @Composable () -> Unit) {
    val c = NexoColors()
    val scheme = darkColorScheme(
        primary = c.volt,
        onPrimary = c.onVolt,
        secondary = c.textMid,
        background = c.ink,
        onBackground = c.textHigh,
        surface = c.panel,
        onSurface = c.textHigh,
        surfaceVariant = c.raised,
        onSurfaceVariant = c.textMid,
        surfaceContainer = c.panel,
        surfaceContainerHigh = c.raised,
        outline = c.line,
        outlineVariant = c.line,
        error = c.live,
    )
    CompositionLocalProvider(
        LocalNexoColors provides c,
        LocalNexoMetrics provides NexoMetrics(),
    ) {
        MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
    }
}
