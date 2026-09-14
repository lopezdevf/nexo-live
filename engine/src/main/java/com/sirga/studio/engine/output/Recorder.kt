// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sirga Studio contributors

package com.sirga.studio.engine.output

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Graba el mismo vídeo y audio codificados que se emiten en un MP4 de Movies/SirgaStudio.
 * El archivo queda «pendiente» (invisible en la galería) hasta cerrarse bien.
 */
class Recorder(context: Context) {

    private val resolver = context.applicationContext.contentResolver
    private val lock = Any()

    private var uri: Uri? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var muxer: MediaMuxer? = null
    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null
    private var expectAudio = true
    private var videoTrack = -1
    private var audioTrack = -1
    private var started = false
    private var sawKeyFrame = false
    private var baseUs = -1L
    private var lastVideoUs = -1L
    private var lastAudioUs = -1L
    private var samplesWritten = 0L

    val isRecording: Boolean get() = synchronized(lock) { muxer != null }

    /**
     * Borra grabaciones que quedaron «pendientes» por un cierre inesperado: sin su índice final
     * no se pueden reproducir. Solo debe llamarse al arrancar, antes de grabar.
     */
    fun deleteAbandoned() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY)
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Video.Media.DISPLAY_NAME} LIKE 'SirgaStudio_%'")
        }
        runCatching {
            resolver.query(collection, arrayOf(MediaStore.Video.Media._ID), args, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    resolver.delete(ContentUris.withAppendedId(collection, cursor.getLong(0)), null, null)
                }
            }
        }.onFailure { Log.w(TAG, "No se pudieron limpiar grabaciones pendientes", it) }
    }

    /** Devuelve el nombre del archivo creado. Los formatos se pasan si los codificadores ya los emitieron. */
    fun start(folder: String, expectAudio: Boolean, video: MediaFormat?, audio: MediaFormat?): String = synchronized(lock) {
        check(muxer == null) { "Ya se está grabando" }
        val name = "SirgaStudio_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/$folder")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val newUri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("No se pudo crear el archivo de vídeo")
        val fd = resolver.openFileDescriptor(newUri, "rw") ?: error("No se pudo abrir el archivo de vídeo")
        uri = newUri
        descriptor = fd
        muxer = MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        this.expectAudio = expectAudio
        videoFormat = video
        audioFormat = audio
        started = false
        sawKeyFrame = false
        baseUs = -1L
        lastVideoUs = -1L
        lastAudioUs = -1L
        samplesWritten = 0
        maybeStart()
        name
    }

    fun onVideoFormat(format: MediaFormat) = synchronized(lock) {
        videoFormat = format
        maybeStart()
    }

    fun onAudioFormat(format: MediaFormat) = synchronized(lock) {
        audioFormat = format
        maybeStart()
    }

    fun writeVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo): Unit = synchronized(lock) {
        val m = muxer ?: return
        if (!started) return
        if (!sawKeyFrame) {
            if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME == 0) return
            sawKeyFrame = true
        }
        write(m, videoTrack, buffer, info, isVideo = true)
    }

    fun writeAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo): Unit = synchronized(lock) {
        val m = muxer ?: return
        // El audio espera al primer fotograma clave para que el archivo empiece sincronizado
        if (!started || audioTrack < 0 || !sawKeyFrame) return
        write(m, audioTrack, buffer, info, isVideo = false)
    }

    /** Cierra el archivo y lo publica en la galería. Si no se grabó nada, lo borra. */
    fun stop(): Uri? = synchronized(lock) {
        val m = muxer ?: return null
        val fileUri = uri
        muxer = null
        val ok = started && samplesWritten > 0 && runCatching { m.stop() }.isSuccess
        runCatching { m.release() }
        runCatching { descriptor?.close() }
        descriptor = null
        uri = null
        if (fileUri == null) return null
        if (ok) {
            resolver.update(fileUri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            fileUri
        } else {
            runCatching { resolver.delete(fileUri, null, null) }
            null
        }
    }

    private fun maybeStart() {
        val m = muxer ?: return
        if (started) return
        val video = videoFormat ?: return
        val audio = audioFormat
        if (expectAudio && audio == null) return
        try {
            videoTrack = m.addTrack(video)
            audioTrack = if (audio != null) m.addTrack(audio) else -1
            m.start()
            started = true
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo iniciar el MP4", e)
        }
    }

    private fun write(m: MediaMuxer, track: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo, isVideo: Boolean) {
        if (baseUs < 0) baseUs = info.presentationTimeUs
        val pts = info.presentationTimeUs - baseUs
        if (pts < 0) return
        if (isVideo) {
            if (pts <= lastVideoUs) return
            lastVideoUs = pts
        } else {
            if (pts <= lastAudioUs) return
            lastAudioUs = pts
        }
        val out = MediaCodec.BufferInfo().apply { set(info.offset, info.size, pts, info.flags) }
        runCatching { m.writeSampleData(track, buffer.duplicate(), out) }.onSuccess { samplesWritten++ }
    }

    private companion object {
        const val TAG = "SirgaRecorder"
    }
}
