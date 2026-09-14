// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sirga Studio contributors

package com.sirga.studio.engine.pclink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer

class SirgaLinkTest {

    private fun bytes(block: (DataOutputStream) -> Unit): ByteArray =
        ByteArrayOutputStream().also { block(DataOutputStream(it)) }.toByteArray()

    private fun input(data: ByteArray) = DataInputStream(ByteArrayInputStream(data))

    @Test
    fun helloRoundTrip() {
        val data = bytes { SirgaLink.writeHello(it, 4821, "PC de Miguel") }
        assertEquals(SirgaLink.Hello(4821, "PC de Miguel"), SirgaLink.readHello(input(data)))
    }

    @Test
    fun helloWithLeadingZeroCode() {
        val data = bytes { SirgaLink.writeHello(it, "0421".toInt(), "PC") }
        assertEquals(421, SirgaLink.readHello(input(data))!!.code)
    }

    @Test
    fun foreignClientIsNotAHello() {
        // Un flujo MPEG-TS o cualquier otra cosa no se confunde con Sirga Link
        val ts = byteArrayOf(0x47, 0x40, 0x00, 0x10, 0, 0, 0, 0)
        assertNull(SirgaLink.readHello(input(ts)))
    }

    @Test
    fun packetRoundTrip() {
        val payload = ByteArray(1000) { it.toByte() }
        val data = bytes { SirgaLink.writePacket(it, SirgaLink.VIDEO_FRAME, payload) }
        val packet = SirgaLink.readPacket(input(data))
        assertEquals(SirgaLink.VIDEO_FRAME, packet.type)
        assertArrayEquals(payload, packet.payload)
    }

    @Test(expected = IOException::class)
    fun oversizedPacketIsRejected() {
        val data = bytes {
            it.writeByte(SirgaLink.VIDEO_FRAME)
            it.writeInt(SirgaLink.MAX_PACKET + 1)
        }
        SirgaLink.readPacket(input(data))
    }

    @Test
    fun replyFormat() {
        val data = bytes { SirgaLink.writeReply(it, SirgaLink.RESULT_WRONG_CODE, "Galaxy") }
        val buffer = ByteBuffer.wrap(data)
        val magic = ByteArray(4).also { buffer.get(it) }
        assertArrayEquals(SirgaLink.MAGIC, magic)
        assertEquals(SirgaLink.RESULT_WRONG_CODE, buffer.get().toInt())
        assertEquals(6, buffer.short.toInt())
    }

    @Test
    fun discoveryQueryAndReply() {
        val query = "SGL1?".toByteArray()
        assertTrue(SirgaLink.isDiscoveryQuery(query, query.size))
        assertFalse(SirgaLink.isDiscoveryQuery("HELLO".toByteArray(), 5))
        assertFalse(SirgaLink.isDiscoveryQuery(query, 3))

        val reply = ByteBuffer.wrap(SirgaLink.discoveryReply(9001, "Galaxy S25", "PC"))
        val magic = ByteArray(5).also { reply.get(it) }
        assertArrayEquals(SirgaLink.DISCOVERY_REPLY, magic)
        assertEquals(9001, reply.short.toInt() and 0xFFFF)
        val device = ByteArray(reply.short.toInt()).also { reply.get(it) }
        assertEquals("Galaxy S25", String(device))
        val source = ByteArray(reply.short.toInt()).also { reply.get(it) }
        assertEquals("PC", String(source))
        assertEquals(1, reply.get().toInt())
    }

    @Test
    fun clockSyncUsesLowestRoundTrip() {
        val sync = ClockSync()
        assertNull(sync.offsetUs)
        // El PC va 5 s por delante. Una muestra con 200 ms de ida y vuelta asimétrica, otra limpia de 2 ms
        sync.add(phoneSentUs = 1_000_000, pcUs = 6_150_000, phoneReceivedUs = 1_200_000)
        sync.add(phoneSentUs = 2_000_000, pcUs = 7_001_000, phoneReceivedUs = 2_002_000)
        assertEquals(5_000_000L, sync.offsetUs)
        // Fotograma capturado en el PC a los 7,000 s y mostrado en el móvil a los 2,045 s → 45 ms
        assertEquals(45L, sync.latencyMs(pcCaptureUs = 7_000_000, phoneNowUs = 2_045_000))
    }

    @Test
    fun clockSyncIgnoresNegativeRoundTrip() {
        val sync = ClockSync()
        sync.add(phoneSentUs = 2_000, pcUs = 10_000, phoneReceivedUs = 1_000)
        assertNull(sync.offsetUs)
    }
}
