// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Nexo Live contributors

package com.nexo.live.engine.output

import android.media.MediaCodec
import com.nexo.live.engine.model.DestinationStatus
import com.nexo.live.engine.model.LiveStatus
import com.nexo.live.engine.model.StreamDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class MultiStreamerTest {

    private class FakeSink(val listener: SinkListener) : DestinationSink {
        var connectedUrl: String? = null
        var disconnects = 0
        var retriesLeft = 0
        val reconnectDelays = mutableListOf<Long>()

        override fun configure(format: OutputFormat) = Unit
        override fun connect(url: String) { connectedUrl = url }
        override fun disconnect() { disconnects++ }
        override fun shouldRetry(reason: String) = retriesLeft-- > 0
        override fun reconnect(delayMs: Long) { reconnectDelays += delayMs }
        override fun setVideoInfo(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) = Unit
        override fun sendVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo) = Unit
        override fun sendAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo) = Unit
    }

    private val sinks = mutableListOf<FakeSink>()
    private var retries = 0
    private val streamer = MultiStreamer(
        sinkFactory = { _, listener -> FakeSink(listener).also { it.retriesLeft = retries; sinks += it } },
        clock = { 1_000L },
        retryDelayMs = { it * 100L },
    )
    private val format = OutputFormat(1280, 720, 30)

    private fun target(id: String, url: String = "rtmp://h/live/$id") =
        MultiStreamer.Target(StreamDestination(id, "rtmp", id, "rtmp://h/live"), url)

    @Test
    fun `streams to every destination independently`() {
        streamer.start(listOf(target("a"), target("b")), format)
        assertEquals(2, sinks.size)
        assertEquals(DestinationStatus.Connecting, streamer.live.value["a"])

        sinks[0].listener.onConnected()
        sinks[1].listener.onFailed("Endpoint malformed")

        assertEquals(DestinationStatus.Live(1_000L), streamer.live.value["a"])
        assertTrue(streamer.live.value["b"] is DestinationStatus.Failed)
        assertEquals(LiveStatus.Live(1_000L), MultiStreamer.aggregate(streamer.live.value.values))
    }

    @Test
    fun `retries with growing delay then gives up`() {
        retries = 2
        streamer.start(listOf(target("a")), format)
        val sink = sinks.single()

        sink.listener.onFailed("timeout")
        assertEquals(DestinationStatus.Reconnecting(1), streamer.live.value["a"])
        sink.listener.onFailed("timeout")
        assertEquals(DestinationStatus.Reconnecting(2), streamer.live.value["a"])
        sink.listener.onFailed("timeout")

        assertEquals(listOf(100L, 200L), sink.reconnectDelays)
        assertTrue(streamer.live.value["a"] is DestinationStatus.Failed)
    }

    @Test
    fun `connection test disconnects on success and never counts as live`() {
        streamer.test(target("a"), format)
        val sink = sinks.single()
        sink.listener.onConnected()

        assertEquals(DestinationStatus.TestPassed, streamer.tests.value["a"])
        assertEquals(1, sink.disconnects)
        assertTrue(streamer.live.value.isEmpty())
        assertFalse(streamer.isActive)
    }

    @Test
    fun `auth errors are final even when retries remain`() {
        retries = 5
        streamer.start(listOf(target("a")), format)
        sinks.single().listener.onAuthError()
        assertEquals(DestinationStatus.Failed(FailureMessages.AUTH), streamer.live.value["a"])
    }

    @Test
    fun `callbacks after stop are ignored`() {
        streamer.start(listOf(target("a")), format)
        val sink = sinks.single()
        streamer.stop()
        sink.listener.onConnected()
        assertTrue(streamer.live.value.isEmpty())
        assertEquals(1, sink.disconnects)
    }

    @Test
    fun `bitrate updates only live destinations`() {
        streamer.start(listOf(target("a"), target("b")), format)
        sinks[0].listener.onConnected()
        sinks[0].listener.onBitrate(4_500_000)
        sinks[1].listener.onBitrate(3_000_000)
        assertEquals(4_500, MultiStreamer.totalBitrateKbps(streamer.live.value.values))
    }

    @Test
    fun `aggregate reports failure only when everything failed`() {
        val failed = DestinationStatus.Failed("x")
        assertEquals(LiveStatus.Offline, MultiStreamer.aggregate(emptyList()))
        assertEquals(LiveStatus.Failed("x"), MultiStreamer.aggregate(listOf(failed, failed)))
        assertEquals(LiveStatus.Connecting, MultiStreamer.aggregate(listOf(failed, DestinationStatus.Connecting)))
        assertEquals(LiveStatus.Reconnecting(3), MultiStreamer.aggregate(listOf(DestinationStatus.Reconnecting(3), failed)))
    }

    @Test
    fun `failure messages never leak the publish url`() {
        val message = FailureMessages.describe("weird error at rtmp://h/live/secret-key")
        assertFalse(message.contains("secret-key"))
    }
}
