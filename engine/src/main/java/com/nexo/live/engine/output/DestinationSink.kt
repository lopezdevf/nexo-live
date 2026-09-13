// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Nexo Live contributors

package com.nexo.live.engine.output

import android.media.MediaCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.rtmp.rtmp.RtmpClient
import com.pedro.srt.srt.SrtClient
import java.nio.ByteBuffer

interface SinkListener {
    fun onConnected()
    fun onFailed(reason: String)
    fun onAuthError()
    fun onDisconnected()
    fun onBitrate(bitsPerSecond: Long)
}

data class OutputFormat(
    val width: Int,
    val height: Int,
    val fps: Int,
    val sampleRate: Int = 48_000,
    val stereo: Boolean = true,
    val hevc: Boolean = false,
)

/** Una conexión saliente. Abstrae RootEncoder para poder probar [MultiStreamer] sin red. */
interface DestinationSink {
    fun configure(format: OutputFormat)
    fun connect(url: String)
    fun disconnect()
    fun shouldRetry(reason: String): Boolean
    fun reconnect(delayMs: Long)
    fun setVideoInfo(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?)
    fun sendVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo)
    fun sendAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo)
}

private const val MAX_RETRIES = 5

fun rootEncoderSink(protocol: StreamProtocol, listener: SinkListener): DestinationSink = when (protocol) {
    StreamProtocol.Rtmp -> RtmpSink(listener)
    StreamProtocol.Srt -> SrtSink(listener)
}

private class RtmpSink(listener: SinkListener) : DestinationSink {
    private val client = RtmpClient(listener.asConnectChecker()).apply { setReTries(MAX_RETRIES) }

    override fun configure(format: OutputFormat) {
        client.setVideoResolution(format.width, format.height)
        client.setFps(format.fps)
        client.setAudioInfo(format.sampleRate, format.stereo)
        // H.265 por RTMP requiere Enhanced RTMP; solo se activa en plataformas que lo admiten
        client.setVideoCodec(if (format.hevc) VideoCodec.H265 else VideoCodec.H264)
    }

    override fun connect(url: String) = client.connect(url)
    override fun disconnect() = client.disconnect()
    override fun shouldRetry(reason: String) = client.shouldRetry(reason)
    override fun reconnect(delayMs: Long) = client.reConnect(delayMs)
    override fun setVideoInfo(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) = client.setVideoInfo(sps, pps, vps)
    override fun sendVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo) = client.sendVideo(buffer, info)
    override fun sendAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo) = client.sendAudio(buffer, info)
}

private class SrtSink(listener: SinkListener) : DestinationSink {
    private val client = SrtClient(listener.asConnectChecker()).apply { setReTries(MAX_RETRIES) }

    override fun configure(format: OutputFormat) {
        client.setAudioInfo(format.sampleRate, format.stereo)
        client.setVideoCodec(if (format.hevc) VideoCodec.H265 else VideoCodec.H264)
    }

    override fun connect(url: String) = client.connect(url)
    override fun disconnect() = client.disconnect()
    override fun shouldRetry(reason: String) = client.shouldRetry(reason)
    override fun reconnect(delayMs: Long) = client.reConnect(delayMs)
    override fun setVideoInfo(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) = client.setVideoInfo(sps, pps, vps)
    override fun sendVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo) = client.sendVideo(buffer, info)
    override fun sendAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo) = client.sendAudio(buffer, info)
}

private fun SinkListener.asConnectChecker(): ConnectChecker {
    val listener = this
    return object : ConnectChecker {
        // La URL contiene la clave: no se usa ni se registra
        override fun onConnectionStarted(url: String) = Unit
        override fun onConnectionSuccess() = listener.onConnected()
        override fun onConnectionFailed(reason: String) = listener.onFailed(reason)
        override fun onDisconnect() = listener.onDisconnected()
        override fun onAuthError() = listener.onAuthError()
        override fun onAuthSuccess() = Unit
        override fun onNewBitrate(bitrate: Long) = listener.onBitrate(bitrate)
    }
}
