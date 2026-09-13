// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Nexo Live contributors

package com.nexo.live.engine.encode

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

data class VideoEncoderConfig(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateKbps: Int,
    val keyframeSec: Int,
    val hevc: Boolean,
)

interface VideoEncoderListener {
    fun onVideoConfig(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?, format: MediaFormat)
    fun onVideoFrame(buffer: ByteBuffer, info: MediaCodec.BufferInfo)
}

/**
 * Codificador por hardware con entrada por superficie: el compositor dibuja directamente en ella,
 * sin copiar píxeles por CPU (la principal fuente de calor en apps de streaming mal hechas).
 */
class VideoEncoder(private val listener: VideoEncoderListener) {

    private var codec: MediaCodec? = null
    private var drainThread: Thread? = null
    @Volatile private var running = false
    var config: VideoEncoderConfig? = null
        private set

    /** Devuelve la superficie donde dibujar. Si H.265 no está disponible, cae a H.264. */
    fun start(requested: VideoEncoderConfig): Surface {
        val config = if (requested.hevc && findEncoder(MediaFormat.MIMETYPE_VIDEO_HEVC, requested) == null) requested.copy(hevc = false) else requested
        val mime = if (config.hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val format = MediaFormat.createVideoFormat(mime, config.width, config.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrateKbps * 1000)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.keyframeSec)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // tiempo real
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0) // RTMP y latencia baja
            // Si la escena no cambia, repite el último fotograma para que la emisión no se congele
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / config.fps * 2)
        }
        val info = findEncoder(mime, config)
        info?.getCapabilitiesForType(mime)?.encoderCapabilities?.let { caps ->
            if (caps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)) {
                format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
        }
        val codec = info?.let { MediaCodec.createByCodecName(it.name) } ?: MediaCodec.createEncoderByType(mime)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()
        this.codec = codec
        this.config = config
        running = true
        drainThread = Thread({ drain(codec, config.hevc) }, "NexoVideoEncoder").apply {
            priority = Thread.MAX_PRIORITY - 1
            start()
        }
        return surface
    }

    fun setBitrate(kbps: Int) {
        runCatching { codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, kbps * 1000) }) }
    }

    fun requestKeyFrame() {
        runCatching { codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    fun stop() {
        running = false
        val c = codec ?: return
        runCatching { c.signalEndOfInputStream() }
        drainThread?.join(1_000)
        runCatching { c.stop() }
        runCatching { c.release() }
        codec = null
        drainThread = null
        config = null
    }

    private fun drain(codec: MediaCodec, hevc: Boolean) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val index = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> emitConfig(codec.outputFormat, hevc)
                    index >= 0 -> {
                        val buffer = codec.getOutputBuffer(index)
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (buffer != null && info.size > 0 && !isConfig) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            listener.onVideoFrame(buffer, info)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } catch (e: IllegalStateException) {
            // El códec se detuvo mientras se vaciaba: fin normal de la salida
        }
    }

    private fun emitConfig(format: MediaFormat, hevc: Boolean) {
        val csd0 = format.getByteBuffer("csd-0") ?: return
        if (hevc) {
            val parts = NalUnits.splitHevcConfig(NalUnits.toArray(csd0))
            if (parts == null) {
                Log.w(TAG, "Configuración HEVC incompleta")
                return
            }
            val (vps, sps, pps) = parts
            listener.onVideoConfig(sps, pps, vps, format)
        } else {
            listener.onVideoConfig(csd0.duplicate(), format.getByteBuffer("csd-1")?.duplicate(), null, format)
        }
    }

    private fun findEncoder(mime: String, config: VideoEncoderConfig): MediaCodecInfo? =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && mime in it.supportedTypes.map { t -> t.lowercase() } }
            .filter { runCatching { it.getCapabilitiesForType(mime).videoCapabilities?.isSizeSupported(config.width, config.height) == true }.getOrDefault(false) }
            // Hardware primero: el software codifica en CPU y calienta mucho más
            .sortedByDescending { it.isHardwareAccelerated }
            .firstOrNull()

    private companion object {
        const val TAG = "NexoVideoEncoder"
    }
}
