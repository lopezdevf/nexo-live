// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Senda Studio contributors

package com.senda.studio.ui.theme

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
 * Identidad Senda Studio: fondo azul noche, paneles planos con filetes finos y el azul eléctrico
 * de la marca para lo que está seleccionado. El cian neón da los acentos finos (textos, iconos y
 * marcos sobre el vídeo), donde el azul se leería peor. El rojo se reserva para lo que está al aire.
 */
@Immutable
data class SendaColors(
    val ink: Color = Color(0xFF0D1117),
    val panel: Color = Color(0xFF141A23),
    val raised: Color = Color(0xFF1C2430),
    val line: Color = Color(0xFF2A3441),
    val textHigh: Color = Color(0xFFFFFFFF),
    val textMid: Color = Color(0xFF9BA7B8),
    val textLow: Color = Color(0xFF5F6B7C),
    /** Azul eléctrico: rellenos, bordes y pistas de lo activo. */
    val accent: Color = Color(0xFF0066FF),
    val onAccent: Color = Color(0xFFFFFFFF),
    /** Cian neón: texto e iconos de acento sobre fondo oscuro (contraste suficiente en tamaños pequeños). */
    val accentText: Color = Color(0xFF00A3FF),
    val live: Color = Color(0xFFFF4D5E),
    val record: Color = Color(0xFFFF9F43),
    val meterLow: Color = Color(0xFF4ADE80),
    val meterMid: Color = Color(0xFFFACC15),
    val meterHigh: Color = Color(0xFFFF4D5E),
)

@Immutable
data class SendaMetrics(
    val hairline: Dp = 1.dp,
    val radiusSmall: Dp = 6.dp,
    val radius: Dp = 10.dp,
    val gap: Dp = 8.dp,
)

val LocalSendaColors = staticCompositionLocalOf { SendaColors() }
val LocalSendaMetrics = staticCompositionLocalOf { SendaMetrics() }

object Senda {
    val colors: SendaColors @Composable get() = LocalSendaColors.current
    val metrics: SendaMetrics @Composable get() = LocalSendaMetrics.current

    /** Cifras que cambian (temporizadores, kbps): monoespaciadas para que no bailen. */
    val numeric = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 12.sp)

    /** Cabeceras de panel en versalitas, estilo mesa de control. */
    val panelLabel = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 1.2.sp)
}

@Composable
fun SendaTheme(content: @Composable () -> Unit) {
    val c = SendaColors()
    val scheme = darkColorScheme(
        primary = c.accent,
        onPrimary = c.onAccent,
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
        LocalSendaColors provides c,
        LocalSendaMetrics provides SendaMetrics(),
    ) {
        MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
    }
}
