// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Nexo Live contributors

package com.nexo.live.engine.audio

import com.nexo.live.engine.model.AudioChannel
import com.nexo.live.engine.model.MonitoringMode
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/** Bloque de audio PCM 16 bits entrelazado en estéreo. */
class PcmBlock(val samples: ShortArray) {
    val frames: Int get() = samples.size / 2
}

/** Resultado de mezclar un bloque: lo que sale al aire, lo que va a los audífonos y los niveles. */
class MixResult(
    val program: ShortArray,
    val monitor: ShortArray?,
    /** Pico por fuente tras ganancia, 0..1. */
    val peaks: Map<String, Float>,
)

/**
 * Mezclador puro (sin Android): ganancia en dB, balance, silencio y rutas de monitorización.
 * Satura con recorte suave para que varias fuentes altas no distorsionen de golpe.
 */
class AudioMixer(private val framesPerBlock: Int) {

    private val programBus = FloatArray(framesPerBlock * 2)
    private val monitorBus = FloatArray(framesPerBlock * 2)

    fun mix(inputs: Map<String, PcmBlock?>, channels: List<AudioChannel>): MixResult {
        programBus.fill(0f)
        monitorBus.fill(0f)
        var anyMonitor = false
        val peaks = HashMap<String, Float>(channels.size)

        for (channel in channels) {
            val block = inputs[channel.sourceId]
            if (block == null) {
                peaks[channel.sourceId] = 0f
                continue
            }
            val gain = if (channel.muted) 0f else dbToLinear(channel.gainDb)
            val balance = channel.balance.coerceIn(-1f, 1f)
            val leftGain = gain * if (balance > 0f) 1f - balance else 1f
            val rightGain = gain * if (balance < 0f) 1f + balance else 1f
            val toProgram = channel.monitoring != MonitoringMode.MonitorOnly
            val toMonitor = channel.monitoring != MonitoringMode.Off
            if (toMonitor) anyMonitor = true

            var peak = 0f
            val frames = minOf(block.frames, framesPerBlock)
            for (f in 0 until frames) {
                val l = block.samples[f * 2] / 32768f * leftGain
                val r = block.samples[f * 2 + 1] / 32768f * rightGain
                peak = max(peak, max(abs(l), abs(r)))
                if (toProgram) {
                    programBus[f * 2] += l
                    programBus[f * 2 + 1] += r
                }
                if (toMonitor) {
                    monitorBus[f * 2] += l
                    monitorBus[f * 2 + 1] += r
                }
            }
            peaks[channel.sourceId] = peak.coerceAtMost(1f)
        }

        return MixResult(
            program = toPcm(programBus),
            monitor = if (anyMonitor) toPcm(monitorBus) else null,
            peaks = peaks,
        )
    }

    private fun toPcm(bus: FloatArray): ShortArray = ShortArray(bus.size) { i -> (softClip(bus[i]) * 32767f).toInt().toShort() }

    companion object {
        fun dbToLinear(db: Float): Float = if (db <= -60f) 0f else 10f.pow(db / 20f)

        /** Nivel para el vúmetro: -60 dBFS → 0, 0 dBFS → 1. */
        fun peakToMeter(peak: Float): Float {
            if (peak <= 0.001f) return 0f
            return ((20f * log10(peak) + 60f) / 60f).coerceIn(0f, 1f)
        }

        /** Lineal hasta -3 dBFS y compresión suave por encima, sin superar nunca 1. */
        internal fun softClip(x: Float): Float {
            val threshold = 0.7f
            val a = abs(x)
            if (a <= threshold) return x
            val over = (a - threshold) / (1f - threshold)
            val shaped = threshold + (1f - threshold) * (over / (1f + over))
            return if (x < 0) -shaped else shaped
        }

        /** Convierte mono a estéreo duplicando canales. */
        fun monoToStereo(mono: ShortArray, count: Int = mono.size): ShortArray {
            val out = ShortArray(count * 2)
            for (i in 0 until count) {
                out[i * 2] = mono[i]
                out[i * 2 + 1] = mono[i]
            }
            return out
        }
    }
}
