// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Nexo Live contributors

package com.nexo.live.engine.encode

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NalUnitsTest {

    // Cabeceras HEVC: el tipo va en los bits 1..6 del primer byte
    private val vps = byteArrayOf((32 shl 1).toByte(), 1, 0x0C)
    private val sps = byteArrayOf((33 shl 1).toByte(), 1, 0x01, 0x60)
    private val pps = byteArrayOf((34 shl 1).toByte(), 1, 0xC1.toByte())

    @Test
    fun `splits units with 3 and 4 byte start codes`() {
        val data = byteArrayOf(0, 0, 0, 1) + vps + byteArrayOf(0, 0, 1) + sps + byteArrayOf(0, 0, 0, 1) + pps
        val nals = NalUnits.split(data)
        assertEquals(3, nals.size)
        assertArrayEquals(vps, nals[0])
        assertArrayEquals(sps, nals[1])
        assertArrayEquals(pps, nals[2])
    }

    @Test
    fun `hevc config is split by type and keeps start codes`() {
        val csd = byteArrayOf(0, 0, 0, 1) + sps + byteArrayOf(0, 0, 0, 1) + vps + byteArrayOf(0, 0, 0, 1) + pps
        val (v, s, p) = NalUnits.splitHevcConfig(csd)!!
        assertArrayEquals(byteArrayOf(0, 0, 0, 1) + vps, NalUnits.toArray(v))
        assertArrayEquals(byteArrayOf(0, 0, 0, 1) + sps, NalUnits.toArray(s))
        assertArrayEquals(byteArrayOf(0, 0, 0, 1) + pps, NalUnits.toArray(p))
    }

    @Test
    fun `incomplete hevc config is rejected`() {
        assertNull(NalUnits.splitHevcConfig(byteArrayOf(0, 0, 0, 1) + sps))
    }
}
