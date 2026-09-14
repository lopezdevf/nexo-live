// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sirga Studio contributors

package com.sirga.studio.engine.model

data class AudioChannel(
    val sourceId: String,
    /** Ganancia en dB, de -60 (silencio práctico) a +6. */
    val gainDb: Float = 0f,
    val muted: Boolean = false,
    /** -1 izquierda, 0 centro, +1 derecha. */
    val balance: Float = 0f,
    val monitoring: MonitoringMode = MonitoringMode.Off,
)

/** Monitorización de audio: qué se oye por los auriculares y qué va a la emisión. */
enum class MonitoringMode {
    /** Solo va a la emisión y la grabación. */
    Off,
    /** Se escucha por los audífonos pero no sale al aire (útil para música de cabina). */
    MonitorOnly,
    /** Se escucha y además sale al aire. */
    MonitorAndOutput,
}

/**
 * Un destino de emisión. La clave nunca vive en este modelo: se guarda cifrada
 * con Android Keystore y solo se une al servidor en el momento de conectar.
 */
data class StreamDestination(
    val id: String,
    /** Id de [com.sirga.studio.engine.output.PlatformCatalog]. */
    val platformId: String,
    val name: String,
    val server: String,
    val enabled: Boolean = true,
)

sealed interface DestinationStatus {
    data object Idle : DestinationStatus
    data object Connecting : DestinationStatus
    data class Live(val startedAtMillis: Long, val bitrateKbps: Int = 0) : DestinationStatus
    data class Reconnecting(val attempt: Int) : DestinationStatus
    data class Failed(val reason: String) : DestinationStatus
    /** Resultado de «Probar conexión»: el servidor aceptó publicar. */
    data object TestPassed : DestinationStatus
}

data class EncoderConfig(
    val videoBitrateKbps: Int = 4_000,
    val audioBitrateKbps: Int = 160,
    val audioSampleRate: Int = 48_000,
    val keyframeIntervalSec: Int = 2,
    val codec: VideoCodecChoice = VideoCodecChoice.H264,
    val adaptiveBitrate: Boolean = true,
)

enum class VideoCodecChoice { H264, H265 }

/**
 * VBR: el codificador reparte los bits según lo que cambia la imagen. CBR: bitrate fijo, pero los
 * codificadores de los móviles lo cumplen dejando la imagen borrosa varios segundos tras cada
 * cambio brusco (cambio de escena, fundido).
 */
enum class BitrateMode { Vbr, Cbr }

sealed interface LiveStatus {
    data object Offline : LiveStatus
    data object Connecting : LiveStatus
    data class Live(val startedAtMillis: Long) : LiveStatus
    data class Reconnecting(val attempt: Int) : LiveStatus
    data class Failed(val reason: String) : LiveStatus
}

sealed interface RecordStatus {
    data object Idle : RecordStatus
    data class Recording(val startedAtMillis: Long, val fileName: String) : RecordStatus
}

data class StreamStats(
    val bitrateKbps: Int = 0,
    val fps: Float = 0f,
    val droppedFrames: Long = 0,
    val totalFrames: Long = 0,
)
