// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sirga Studio contributors

package com.sirga.studio.engine.audio

import com.sirga.studio.engine.model.AudioChannel
import com.sirga.studio.engine.model.MonitoringMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AudioMixerTest {

    private val mixer = AudioMixer(framesPerBlock = 4)

    private fun block(value: Short) = PcmBlock(ShortArray(8) { value })

    @Test
    fun `unity gain passes a quiet signal unchanged`() {
        val out = mixer.mix(mapOf("mic" to block(1000)), listOf(AudioChannel("mic")))
        assertTrue(out.program.all { abs(it - 1000) <= 1 })
        assertNull(out.monitor)
    }

    @Test
    fun `muted channels are silent but missing inputs do not crash`() {
        val out = mixer.mix(
            mapOf("mic" to block(8000), "game" to null),
            listOf(AudioChannel("mic", muted = true), AudioChannel("game")),
        )
        assertTrue(out.program.all { it.toInt() == 0 })
        assertEquals(0f, out.peaks.getValue("game"))
    }

    @Test
    fun `monitor only is heard but never goes on air`() {
        val out = mixer.mix(
            mapOf("music" to block(4000)),
            listOf(AudioChannel("music", monitoring = MonitoringMode.MonitorOnly)),
        )
        assertTrue(out.program.all { it.toInt() == 0 })
        assertTrue(out.monitor!!.all { abs(it - 4000) <= 1 })
    }

    @Test
    fun `balance pans between channels`() {
        val out = mixer.mix(mapOf("mic" to block(2000)), listOf(AudioChannel("mic", balance = 1f)))
        assertEquals(0, out.program[0].toInt())
        assertTrue(abs(out.program[1] - 2000) <= 1)
    }

    @Test
    fun `summing loud sources never wraps around`() {
        val out = mixer.mix(
            mapOf("a" to block(30000), "b" to block(30000)),
            listOf(AudioChannel("a"), AudioChannel("b")),
        )
        assertTrue(out.program.all { it > 0 })
    }

    @Test
    fun `gain and meter conversions`() {
        assertEquals(1f, AudioMixer.dbToLinear(0f), 1e-4f)
        assertEquals(0f, AudioMixer.dbToLinear(-60f))
        assertEquals(1f, AudioMixer.peakToMeter(1f), 1e-4f)
        assertEquals(0.5f, AudioMixer.peakToMeter(0.001f * 31.62f), 0.01f)
        assertTrue(AudioMixer.softClip(5f) < 1f)
        assertEquals(0.5f, AudioMixer.softClip(0.5f))
    }
}
