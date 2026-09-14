// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Senda Studio contributors

package com.senda.studio.engine.model

/**
 * Una fuente es global al estudio: varias escenas pueden
 * referenciar la misma cámara sin abrirla dos veces.
 */
sealed interface Source {
    val id: String
    val name: String
    val hasVideo: Boolean
    val hasAudio: Boolean

    /** Cámaras del sistema (Camera2), incluidas las USB que el móvil expone como externas. */
    data class Camera(
        override val id: String,
        override val name: String,
        val facing: Facing = Facing.Back,
        /** Id de Camera2 concreto; null elige la primera cámara con [facing]. */
        val cameraId: String? = null,
        /** Giro extra que el usuario añade si su cámara sale torcida (0, 90, 180, 270). */
        val rotationOffset: Int = 0,
        val mirror: Boolean = false,
    ) : Source {
        override val hasVideo = true
        override val hasAudio = false
    }

    /**
     * Webcams y capturadoras HDMI por USB (UVC) en móviles que no las exponen por Camera2.
     * [deviceName] es el nombre del dispositivo USB; null usa la primera conectada.
     */
    data class UsbCamera(
        override val id: String,
        override val name: String,
        val deviceName: String? = null,
        val rotationOffset: Int = 0,
        val mirror: Boolean = false,
    ) : Source {
        override val hasVideo = true
        override val hasAudio = false
    }

    /**
     * Pantalla y sonido del PC sin capturadora: Senda Studio PC los envía al móvil, que escucha en
     * [port] (WiFi, zona WiFi del móvil o anclaje USB). Solo acepta al PC que conoce [code].
     */
    data class PcInput(
        override val id: String,
        override val name: String,
        val port: Int = 9000,
        val code: String = newPairingCode(),
    ) : Source {
        override val hasVideo = true
        override val hasAudio = true
    }

    /** Captura de pantalla vía MediaProjection (juegos, apps). */
    data class Screen(
        override val id: String,
        override val name: String,
    ) : Source {
        override val hasVideo = true
        override val hasAudio = false
    }

    data class Image(
        override val id: String,
        override val name: String,
        /** Ruta del archivo copiado al almacenamiento privado de la app. */
        val uri: String,
    ) : Source {
        override val hasVideo = true
        override val hasAudio = false
    }

    data class Text(
        override val id: String,
        override val name: String,
        val text: String,
        val colorArgb: Long = 0xFFFFFFFF,
        val backgroundArgb: Long = 0x00000000,
        val bold: Boolean = true,
        val alignment: TextAlignment = TextAlignment.Center,
    ) : Source {
        override val hasVideo = true
        override val hasAudio = false
    }

    data class SolidColor(
        override val id: String,
        override val name: String,
        val argb: Long,
    ) : Source {
        override val hasVideo = true
        override val hasAudio = false
    }

    /** Micrófono integrado, con cable, USB o Bluetooth. */
    data class Microphone(
        override val id: String,
        override val name: String,
        /** null usa el micrófono predeterminado del sistema. */
        val device: AudioDeviceKey? = null,
        val noiseSuppression: Boolean = false,
        val echoCancellation: Boolean = false,
        val stereo: Boolean = false,
    ) : Source {
        override val hasVideo = false
        override val hasAudio = true
    }

    /** Audio interno del dispositivo (AudioPlaybackCapture, API 29+). */
    data class InternalAudio(
        override val id: String,
        override val name: String,
    ) : Source {
        override val hasVideo = false
        override val hasAudio = true
    }
}

enum class Facing { Front, Back, External }

enum class TextAlignment { Start, Center, End }

/**
 * Identifica un dispositivo de audio aunque se desconecte y vuelva: Android cambia su id
 * numérico en cada conexión, así que se empareja por tipo, nombre y dirección.
 */
data class AudioDeviceKey(val type: Int, val productName: String, val address: String = "")

enum class SourceKind { Camera, UsbCamera, PcInput, Screen, Image, Text, SolidColor, Microphone, InternalAudio }

val Source.kind: SourceKind
    get() = when (this) {
        is Source.Camera -> SourceKind.Camera
        is Source.UsbCamera -> SourceKind.UsbCamera
        is Source.PcInput -> SourceKind.PcInput
        is Source.Screen -> SourceKind.Screen
        is Source.Image -> SourceKind.Image
        is Source.Text -> SourceKind.Text
        is Source.SolidColor -> SourceKind.SolidColor
        is Source.Microphone -> SourceKind.Microphone
        is Source.InternalAudio -> SourceKind.InternalAudio
    }

fun Source.renamed(name: String): Source = when (this) {
    is Source.Camera -> copy(name = name)
    is Source.UsbCamera -> copy(name = name)
    is Source.PcInput -> copy(name = name)
    is Source.Screen -> copy(name = name)
    is Source.Image -> copy(name = name)
    is Source.Text -> copy(name = name)
    is Source.SolidColor -> copy(name = name)
    is Source.Microphone -> copy(name = name)
    is Source.InternalAudio -> copy(name = name)
}

/** Código de 4 cifras que el PC debe conocer para enviar su pantalla a una fuente. */
fun newPairingCode(): String = (java.security.SecureRandom().nextInt(9000) + 1000).toString()
