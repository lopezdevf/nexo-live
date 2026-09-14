// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sirga Studio contributors

package com.sirga.studio.engine.thermal

import com.sirga.studio.engine.settings.ThermalPolicy
import com.sirga.studio.engine.settings.ThermalSettings

/** Niveles equivalentes a PowerManager.THERMAL_STATUS_*. */
enum class ThermalLevel { None, Light, Moderate, Severe, Critical, Emergency }

data class ThermalReading(
    val status: ThermalLevel,
    /** PowerManager.getThermalHeadroom: 1.0 = umbral de estrangulamiento. null si no hay dato. */
    val headroom: Float? = null,
    val batteryTempC: Float? = null,
)

/** Qué hace el motor para enfriar el móvil. Los factores se aplican sobre la configuración del usuario. */
data class ThermalProfile(
    val level: ThermalLevel = ThermalLevel.None,
    val bitrateScale: Float = 1f,
    val fpsCap: Int? = null,
    val resolutionScale: Float = 1f,
    val previewFps: Int? = null,
    /** En modo estudio, deja de renderizar la vista previa aparte del programa. */
    val singleRender: Boolean = false,
    val stopEverything: Boolean = false,
    val message: String? = null,
) {
    val throttled: Boolean get() = level > ThermalLevel.None && (bitrateScale < 1f || fpsCap != null || resolutionScale < 1f || previewFps != null)

    fun bitrate(userKbps: Int, minKbps: Int): Int = (userKbps * bitrateScale).toInt().coerceAtLeast(minOf(minKbps, userKbps))

    fun fps(userFps: Int, minFps: Int): Int = fpsCap?.let { minOf(userFps, maxOf(it, minOf(minFps, userFps))) } ?: userFps
}

/**
 * Decide cuánto recortar según el estado térmico. Sube de nivel al instante y solo baja
 * cuando el móvil lleva [ThermalSettings.recoverySeconds] más frío, para no oscilar.
 *
 * Actúa antes que el sistema: el estrangulamiento del SoC tira los fps sin avisar, y es
 * mejor bajar bitrate de forma controlada que perder fotogramas.
 */
class ThermalGovernor(private var settings: ThermalSettings = ThermalSettings()) {

    private var current = ThermalLevel.None
    private var coolerSinceMillis: Long? = null

    fun updateSettings(settings: ThermalSettings) {
        this.settings = settings
    }

    fun evaluate(reading: ThermalReading, nowMillis: Long): ThermalProfile {
        val target = effectiveLevel(reading)
        when {
            target >= current -> {
                current = target
                coolerSinceMillis = null
            }
            else -> {
                val since = coolerSinceMillis ?: nowMillis.also { coolerSinceMillis = it }
                if (nowMillis - since >= settings.recoverySeconds * 1000L) {
                    // Baja de uno en uno: la recuperación también es gradual
                    current = ThermalLevel.entries[current.ordinal - 1]
                    coolerSinceMillis = if (current > target) nowMillis else null
                }
            }
        }
        return profileFor(current)
    }

    /** Combina el estado del sistema, el margen previsto y la temperatura de batería. */
    internal fun effectiveLevel(reading: ThermalReading): ThermalLevel {
        var level = reading.status
        reading.headroom?.let { h ->
            val byHeadroom = when {
                h >= 1.0f -> ThermalLevel.Severe
                h >= 0.95f -> ThermalLevel.Moderate
                h >= 0.85f -> ThermalLevel.Light
                else -> ThermalLevel.None
            }
            if (byHeadroom > level) level = byHeadroom
        }
        reading.batteryTempC?.let { t ->
            val limit = settings.batteryLimitC
            val byBattery = when {
                t >= limit + 6 -> ThermalLevel.Critical
                t >= limit + 3 -> ThermalLevel.Severe
                t >= limit -> ThermalLevel.Moderate
                t >= limit - 2 -> ThermalLevel.Light
                else -> ThermalLevel.None
            }
            if (byBattery > level) level = byBattery
        }
        return level
    }

    private fun profileFor(level: ThermalLevel): ThermalProfile {
        if (settings.policy == ThermalPolicy.Off) return ThermalProfile(level = level)
        val advice = message(level)
        if (settings.policy == ThermalPolicy.NotifyOnly) return ThermalProfile(level = level, message = advice)

        return when (level) {
            ThermalLevel.None -> ThermalProfile()
            ThermalLevel.Light -> ThermalProfile(level, bitrateScale = 0.9f, previewFps = 24, message = advice)
            ThermalLevel.Moderate -> ThermalProfile(
                level, bitrateScale = 0.75f, fpsCap = 30, previewFps = 15, singleRender = true, message = advice,
            )
            ThermalLevel.Severe -> ThermalProfile(
                level, bitrateScale = 0.55f, fpsCap = settings.minFps,
                resolutionScale = if (settings.allowResolutionDrop) 0.75f else 1f,
                previewFps = 10, singleRender = true, message = advice,
            )
            ThermalLevel.Critical -> ThermalProfile(
                level, bitrateScale = 0.4f, fpsCap = settings.minFps,
                resolutionScale = if (settings.allowResolutionDrop) 0.5f else 1f,
                previewFps = 5, singleRender = true, message = advice,
            )
            ThermalLevel.Emergency -> ThermalProfile(
                level, bitrateScale = 0.4f, fpsCap = settings.minFps,
                resolutionScale = if (settings.allowResolutionDrop) 0.5f else 1f,
                previewFps = 5, singleRender = true,
                stopEverything = settings.stopAtEmergency, message = advice,
            )
        }
    }

    private fun message(level: ThermalLevel): String? = when (level) {
        ThermalLevel.None -> null
        ThermalLevel.Light -> "El móvil empieza a calentarse. Quita la funda y evita cargarlo durante el directo."
        ThermalLevel.Moderate -> "Temperatura alta: se reduce un poco la calidad para evitar cortes."
        ThermalLevel.Severe -> "Temperatura muy alta: calidad reducida. Busca sombra o ventilación."
        ThermalLevel.Critical -> "Temperatura crítica: calidad mínima. Considera terminar el directo pronto."
        ThermalLevel.Emergency ->
            if (settings.stopAtEmergency) "Emergencia térmica: se detiene el directo y se guarda la grabación para proteger el móvil."
            else "Emergencia térmica: el sistema puede cerrar la app en cualquier momento."
    }
}
