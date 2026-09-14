// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Senda Studio contributors

package com.senda.studio.engine.thermal

import com.senda.studio.engine.settings.ThermalPolicy
import com.senda.studio.engine.settings.ThermalSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalGovernorTest {

    private val settings = ThermalSettings(recoverySeconds = 60, minFps = 24, minBitrateKbps = 1_500)
    private val governor = ThermalGovernor(settings)

    private fun at(level: ThermalLevel, t: Long, headroom: Float? = null, battery: Float? = null) =
        governor.evaluate(ThermalReading(level, headroom, battery), t)

    @Test
    fun `cool device runs at full quality`() {
        val p = at(ThermalLevel.None, 0)
        assertEquals(1f, p.bitrateScale)
        assertNull(p.fpsCap)
        assertFalse(p.throttled)
    }

    @Test
    fun `escalates immediately`() {
        at(ThermalLevel.None, 0)
        val p = at(ThermalLevel.Severe, 1_000)
        assertEquals(ThermalLevel.Severe, p.level)
        assertEquals(24, p.fpsCap)
        assertEquals(0.75f, p.resolutionScale)
        assertTrue(p.singleRender)
    }

    @Test
    fun `recovers one level at a time after cooling down`() {
        at(ThermalLevel.Severe, 0)
        assertEquals(ThermalLevel.Severe, at(ThermalLevel.None, 1_000).level)
        assertEquals(ThermalLevel.Severe, at(ThermalLevel.None, 60_000).level)
        assertEquals(ThermalLevel.Moderate, at(ThermalLevel.None, 61_000).level)
        assertEquals(ThermalLevel.Moderate, at(ThermalLevel.None, 100_000).level)
        assertEquals(ThermalLevel.Light, at(ThermalLevel.None, 121_000).level)
    }

    @Test
    fun `a new spike resets the recovery timer`() {
        at(ThermalLevel.Moderate, 0)
        at(ThermalLevel.None, 1_000)
        at(ThermalLevel.Moderate, 50_000)
        assertEquals(ThermalLevel.Moderate, at(ThermalLevel.None, 70_000).level)
    }

    @Test
    fun `headroom and battery can raise the level before the system does`() {
        assertEquals(ThermalLevel.Moderate, governor.effectiveLevel(ThermalReading(ThermalLevel.None, headroom = 0.96f)))
        assertEquals(ThermalLevel.Severe, governor.effectiveLevel(ThermalReading(ThermalLevel.Light, batteryTempC = 45.5f)))
        assertEquals(ThermalLevel.Light, governor.effectiveLevel(ThermalReading(ThermalLevel.None, batteryTempC = 40.5f)))
    }

    @Test
    fun `emergency stops everything only when configured`() {
        assertTrue(at(ThermalLevel.Emergency, 0).stopEverything)
        val lenient = ThermalGovernor(settings.copy(stopAtEmergency = false))
        assertFalse(lenient.evaluate(ThermalReading(ThermalLevel.Emergency), 0).stopEverything)
    }

    @Test
    fun `notify only never touches quality`() {
        val notify = ThermalGovernor(settings.copy(policy = ThermalPolicy.NotifyOnly))
        val p = notify.evaluate(ThermalReading(ThermalLevel.Critical), 0)
        assertEquals(1f, p.bitrateScale)
        assertTrue(p.message != null)
    }

    @Test
    fun `profile respects user floors`() {
        val p = ThermalProfile(ThermalLevel.Critical, bitrateScale = 0.4f, fpsCap = 15)
        assertEquals(1_500, p.bitrate(userKbps = 3_000, minKbps = 1_500))
        assertEquals(1_000, p.bitrate(userKbps = 1_000, minKbps = 1_500))
        assertEquals(24, p.fps(userFps = 60, minFps = 24))
        assertEquals(20, p.fps(userFps = 20, minFps = 24))
    }
}
