// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sirga Studio contributors

package com.sirga.studio.engine.output

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionLinkTest {

    private fun ok(raw: String) = ConnectionLink.parse(raw) as LinkResult.Ok

    @Test
    fun `twitch full link splits app and key`() {
        val r = ok("rtmp://ingest.global-contribute.live-video.net/app/live_123_AbC")
        assertEquals("rtmp://ingest.global-contribute.live-video.net/app", r.server)
        assertEquals("live_123_AbC", r.secret)
        assertEquals("twitch", r.platform.id)
    }

    @Test
    fun `kick server without app gets app appended`() {
        val r = ok("rtmps://fa723fc1b171.global-contribute.live-video.net")
        assertEquals("rtmps://fa723fc1b171.global-contribute.live-video.net/app", r.server)
        assertNull(r.secret)
        assertEquals("kick", r.platform.id)
    }

    @Test
    fun `youtube link without explicit port matches catalog server`() {
        val r = ok("rtmps://a.rtmps.youtube.com/live2/abcd-efgh-ijkl")
        assertEquals("rtmps://a.rtmps.youtube.com:443/live2", r.server)
        assertEquals("abcd-efgh-ijkl", r.secret)
        assertEquals("youtube", r.platform.id)
    }

    @Test
    fun `facebook key keeps its query parameters`() {
        val r = ok("rtmps://live-api-s.facebook.com:443/rtmp/FB-1234-0-AbCd?s_bl=1&s_psm=1&a=Xy")
        assertEquals("rtmps://live-api-s.facebook.com:443/rtmp", r.server)
        assertEquals("FB-1234-0-AbCd?s_bl=1&s_psm=1&a=Xy", r.secret)
        assertEquals("facebook", r.platform.id)
    }

    @Test
    fun `tiktok link is detected`() {
        val r = ok("rtmp://push-rtmp-l11-sg01.tiktokcdn.com/game/stream-987?expire=1&sign=zz")
        assertEquals("rtmp://push-rtmp-l11-sg01.tiktokcdn.com/game", r.server)
        assertEquals("stream-987?expire=1&sign=zz", r.secret)
        assertEquals("tiktok", r.platform.id)
        assertTrue(r.platform.verticalFirst)
    }

    @Test
    fun `server and key pasted together`() {
        val r = ok("rtmp://live.restream.io/live\nre_12345_abc")
        assertEquals("rtmp://live.restream.io/live", r.server)
        assertEquals("re_12345_abc", r.secret)
        assertEquals("restream", r.platform.id)
    }

    @Test
    fun `unknown host falls back to custom rtmp`() {
        val r = ok("rtmp://192.168.1.20:1935/live/mesa")
        assertEquals("rtmp://192.168.1.20:1935/live", r.server)
        assertEquals("mesa", r.secret)
        assertEquals("rtmp", r.platform.id)
    }

    @Test
    fun `srt link keeps streamid and passphrase as secret`() {
        val r = ok("srt://ingest.example.com:9000?streamid=abc&passphrase=0123456789ab")
        assertEquals("srt://ingest.example.com:9000", r.server)
        assertEquals("streamid=abc&passphrase=0123456789ab", r.secret)
        assertEquals("srt", r.platform.id)
    }

    @Test
    fun `invalid inputs explain the problem`() {
        assertTrue(ConnectionLink.parse("live_123_abc") is LinkResult.Invalid)
        assertTrue(ConnectionLink.parse("https://twitch.tv/canal") is LinkResult.Invalid)
        assertTrue(ConnectionLink.parse("srt://host-sin-puerto") is LinkResult.Invalid)
        assertTrue(ConnectionLink.parse("rtmp://host") is LinkResult.Invalid)
    }

    @Test
    fun `join builds the url rootencoder expects`() {
        assertEquals("rtmps://a.rtmps.youtube.com:443/live2/key", ConnectionLink.join("rtmps://a.rtmps.youtube.com:443/live2", "key"))
        assertEquals("rtmp://livepush.trovo.live/live/key", ConnectionLink.join("rtmp://livepush.trovo.live/live/", "/key"))
        assertEquals("srt://h:9000?streamid=abc", ConnectionLink.join("srt://h:9000", "abc"))
        assertEquals("srt://h:9000?latency=200&streamid=abc", ConnectionLink.join("srt://h:9000?latency=200", "streamid=abc"))
        assertEquals("rtmp://h/live", ConnectionLink.join("rtmp://h/live", "  "))
    }

    @Test
    fun `parse then join round trips`() {
        val link = "rtmps://rtmp-api.facebook.com:443/rtmp/FB-1-2?s_bl=1"
        val r = ok(link)
        assertEquals(link, ConnectionLink.join(r.server, r.secret))
    }

    @Test
    fun `server validation`() {
        assertNull(ConnectionLink.validateServer("rtmp://live.restream.io/live", PlatformCatalog.Restream))
        assertTrue(ConnectionLink.validateServer("rtmp://h/live?token=1", PlatformCatalog.CustomRtmp) != null)
        assertTrue(ConnectionLink.validateServer("srt://h:9000", PlatformCatalog.CustomRtmp) != null)
        assertNull(ConnectionLink.validateServer("srt://h:9000", PlatformCatalog.CustomSrt))
    }

    @Test
    fun `host mismatch warns about foreign servers`() {
        assertTrue(ConnectionLink.hostMismatch("rtmp://evil.example.com/live2", PlatformCatalog.YouTube))
        assertFalse(ConnectionLink.hostMismatch("rtmp://a.rtmp.youtube.com/live2", PlatformCatalog.YouTube))
        assertFalse(ConnectionLink.hostMismatch("rtmp://anything/live", PlatformCatalog.CustomRtmp))
    }

    @Test
    fun `every catalog server parses back to its own platform`() {
        PlatformCatalog.all.flatMap { p -> p.servers.map { p to it } }.forEach { (platform, server) ->
            val r = ok(ConnectionLink.join(server.url, "k"))
            assertEquals(server.url, platform.id, r.platform.id)
            assertEquals(server.url, "k", r.secret)
            assertNull(server.url, ConnectionLink.validateServer(r.server, platform))
        }
    }
}
