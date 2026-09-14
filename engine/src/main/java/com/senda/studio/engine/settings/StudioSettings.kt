// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Senda Studio contributors

package com.senda.studio.engine.settings

import com.senda.studio.engine.model.AudioDeviceKey
import com.senda.studio.engine.model.BitrateMode
import com.senda.studio.engine.model.CanvasConfig
import com.senda.studio.engine.model.EncoderConfig
import com.senda.studio.engine.model.VideoCodecChoice

/** Todo lo configurable del estudio. Valores por defecto pensados para un móvil de gama media. */
data class StudioSettings(
    val video: VideoSettings = VideoSettings(),
    val audio: AudioSettings = AudioSettings(),
    val thermal: ThermalSettings = ThermalSettings(),
    val recording: RecordingSettings = RecordingSettings(),
    val transition: TransitionSettings = TransitionSettings(),
) {
    val canvas: CanvasConfig get() = CanvasConfig(video.width, video.height, video.fps)

    val encoder: EncoderConfig
        get() = EncoderConfig(
            videoBitrateKbps = video.bitrateKbps,
            audioBitrateKbps = audio.bitrateKbps,
            audioSampleRate = audio.sampleRate,
            keyframeIntervalSec = video.keyframeSec,
            codec = video.codec,
            adaptiveBitrate = video.adaptiveBitrate,
        )
}

data class VideoSettings(
    val width: Int = 1280,
    val height: Int = 720,
    val fps: Int = 30,
    val bitrateKbps: Int = 4_000,
    val keyframeSec: Int = 2,
    val codec: VideoCodecChoice = VideoCodecChoice.H264,
    val bitrateMode: BitrateMode = BitrateMode.Vbr,
    /** Baja el bitrate si la red se congestiona y lo recupera después. */
    val adaptiveBitrate: Boolean = true,
    /** La vista previa puede ir más lenta que la salida para ahorrar batería y calor. */
    val previewFps: Int = 30,
    /** Con el estudio abierto la pantalla no se apaga (al emitir o grabar nunca se apaga). */
    val keepScreenOn: Boolean = true,
) {
    companion object {
        val RESOLUTIONS = listOf(
            640 to 360, 854 to 480, 1280 to 720, 1920 to 1080,
            360 to 640, 480 to 854, 720 to 1280, 1080 to 1920,
        )
        val FPS = listOf(24, 25, 30, 48, 50, 60)
        const val MIN_BITRATE = 500
        const val MAX_BITRATE = 20_000
    }
}

data class AudioSettings(
    val sampleRate: Int = 48_000,
    val bitrateKbps: Int = 160,
    /** Salida donde se escuchan los canales monitorizados; null = auriculares si hay, si no, nada. */
    val monitorDevice: AudioDeviceKey? = null,
    /** Permite monitorizar por el altavoz (riesgo de acople con el micrófono). */
    val allowSpeakerMonitoring: Boolean = false,
) {
    companion object {
        val SAMPLE_RATES = listOf(44_100, 48_000)
        val BITRATES = listOf(96, 128, 160, 192, 256, 320)
    }
}

enum class ThermalPolicy {
    /** Reduce calidad por pasos antes de que el sistema estrangule el procesador. */
    Automatic,
    /** Solo avisa; el usuario decide. */
    NotifyOnly,
    /** Sin intervención (no recomendado para directos largos). */
    Off,
}

data class ThermalSettings(
    val policy: ThermalPolicy = ThermalPolicy.Automatic,
    /** Suelos que la protección nunca cruza. */
    val minFps: Int = 24,
    val minBitrateKbps: Int = 1_500,
    val allowResolutionDrop: Boolean = true,
    /** Temperatura de batería a partir de la cual se actúa aunque el sistema no avise. */
    val batteryLimitC: Float = 42f,
    /** En emergencia térmica corta el directo y guarda la grabación antes de que el sistema mate la app. */
    val stopAtEmergency: Boolean = true,
    /** Segundos que el móvil debe estar más frío antes de recuperar calidad. */
    val recoverySeconds: Int = 90,
)

data class RecordingSettings(
    /** Carpeta dentro de Movies/. */
    val folder: String = "SendaStudio",
    /** Graba lo mismo que se emite (un solo codificador: menos calor). */
    val shareStreamEncoder: Boolean = true,
)

enum class TransitionType { Cut, Fade }

data class TransitionSettings(
    val type: TransitionType = TransitionType.Fade,
    val durationMs: Int = 300,
)
