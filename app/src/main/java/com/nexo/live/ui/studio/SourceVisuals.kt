// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Nexo Live contributors

package com.nexo.live.ui.studio

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.FormatColorFill
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.nexo.live.engine.model.SourceKind

val SourceKind.label: String
    get() = when (this) {
        SourceKind.Camera -> "Cámara"
        SourceKind.UsbCamera -> "Cámara USB / capturadora"
        SourceKind.PcInput -> "PC (OBS por WiFi o USB)"
        SourceKind.Screen -> "Pantalla"
        SourceKind.Image -> "Imagen"
        SourceKind.Text -> "Texto"
        SourceKind.SolidColor -> "Color"
        SourceKind.Microphone -> "Micrófono"
        SourceKind.InternalAudio -> "Audio interno"
    }

val SourceKind.icon: ImageVector
    get() = when (this) {
        SourceKind.Camera -> Icons.Outlined.Videocam
        SourceKind.UsbCamera -> Icons.Outlined.Usb
        SourceKind.PcInput -> Icons.Outlined.Computer
        SourceKind.Screen -> Icons.Outlined.PhoneAndroid
        SourceKind.Image -> Icons.Outlined.Image
        SourceKind.Text -> Icons.Outlined.TextFields
        SourceKind.SolidColor -> Icons.Outlined.FormatColorFill
        SourceKind.Microphone -> Icons.Outlined.Mic
        SourceKind.InternalAudio -> Icons.AutoMirrored.Outlined.VolumeUp
    }

/** Tinte de marcador en la vista previa mientras el compositor GL no pinta la fuente real. */
val SourceKind.placeholderTint: Color
    get() = when (this) {
        SourceKind.Camera -> Color(0xFF2B4C7E)
        SourceKind.UsbCamera -> Color(0xFF2B5E7E)
        SourceKind.PcInput -> Color(0xFF3B3F7E)
        SourceKind.Screen -> Color(0xFF3A2F5B)
        SourceKind.Image -> Color(0xFF2F5B4A)
        SourceKind.Text -> Color(0x00000000)
        SourceKind.SolidColor -> Color(0xFF1B1E26)
        SourceKind.Microphone, SourceKind.InternalAudio -> Color(0x00000000)
    }
