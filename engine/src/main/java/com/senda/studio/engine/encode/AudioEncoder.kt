// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Senda Studio contributors

package com.senda.studio.engine.encode

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

interface AudioEncoderListener {
    fun onAudioFormat(format: MediaFormat, sampleRate: Int, stereo: Boolean)
    fun onAudioFrame(buffer: ByteBuffer, info: MediaCodec.BufferInfo)
}

/** AAC-LC. Se alimenta desde el hilo del mezclador y vacía la salida en el mismo hilo. */
class AudioEncoder(private val listener: AudioEncoderListener) {

    private var codec: MediaCodec? = null
    private val info = MediaCodec.BufferInfo()
    private var sampleRate = 48_000
    private val channels = 2

    fun start(sampleRate: Int, bitrateKbps: Int) {
        this.sampleRate = sampleRate
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
    }

    /** El mezclador codifica desde su hilo y la parada llega desde otro: nunca a la vez. */
    private val lock = Any()

    /** [pcm] estéreo entrelazado; [ptsUs] en la misma base de tiempo que el vídeo (System.nanoTime / 1000). */
    fun encode(pcm: ShortArray, ptsUs: Long): Unit = synchronized(lock) {
        val c = codec ?: return
        try {
            var offset = 0
            var pts = ptsUs
            while (offset < pcm.size) {
                val index = c.dequeueInputBuffer(5_000)
                if (index < 0) {
                    drain(c) // codificador saturado: se vacía y se descarta el resto del bloque
                    break
                }
                val buffer = c.getInputBuffer(index) ?: break
                buffer.clear()
                buffer.order(ByteOrder.nativeOrder())
                val samples = minOf((pcm.size - offset), buffer.remaining() / 2)
                buffer.asShortBuffer().put(pcm, offset, samples)
                c.queueInputBuffer(index, 0, samples * 2, pts, 0)
                offset += samples
                pts += samples.toLong() / channels * 1_000_000L / sampleRate
                drain(c)
            }
        } catch (e: IllegalStateException) {
            // El códec se detuvo o falló: se pierde este bloque, nunca la app
        }
    }

    fun stop(): Unit = synchronized(lock) {
        val c = codec ?: return
        codec = null
        runCatching { c.stop() }
        runCatching { c.release() }
    }

    private fun drain(c: MediaCodec) {
        while (true) {
            val index = c.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> listener.onAudioFormat(c.outputFormat, sampleRate, channels == 2)
                index >= 0 -> {
                    val buffer = c.getOutputBuffer(index)
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buffer != null && info.size > 0 && !isConfig) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        listener.onAudioFrame(buffer, info)
                    }
                    c.releaseOutputBuffer(index, false)
                }
                else -> return
            }
        }
    }
}
