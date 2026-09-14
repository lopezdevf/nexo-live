// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Senda Studio contributors

package com.senda.studio.engine.output

import android.media.MediaCodec
import com.senda.studio.engine.model.DestinationStatus
import com.senda.studio.engine.model.LiveStatus
import com.senda.studio.engine.model.StreamDestination
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Emite el mismo vídeo codificado a varias plataformas a la vez: se codifica una sola
 * vez y cada destino recibe su copia por su propia conexión, con reintentos independientes.
 */
class MultiStreamer(
    private val sinkFactory: (StreamProtocol, SinkListener) -> DestinationSink = ::rootEncoderSink,
    private val clock: () -> Long = System::currentTimeMillis,
    private val retryDelayMs: (attempt: Int) -> Long = { attempt -> 2_000L shl (attempt - 1).coerceIn(0, 4) },
) {
    /** Destino con la URL de publicación ya resuelta (incluye la clave). */
    class Target(val destination: StreamDestination, val publishUrl: String) {
        override fun toString() = "Target(${destination.id})"
    }

    private val _live = MutableStateFlow<Map<String, DestinationStatus>>(emptyMap())
    val live: StateFlow<Map<String, DestinationStatus>> = _live.asStateFlow()

    private val _tests = MutableStateFlow<Map<String, DestinationStatus>>(emptyMap())
    val tests: StateFlow<Map<String, DestinationStatus>> = _tests.asStateFlow()

    private val liveSessions = ConcurrentHashMap<String, Session>()
    private val testSessions = ConcurrentHashMap<String, Session>()

    @Volatile private var videoInfo: Triple<ByteBuffer, ByteBuffer?, ByteBuffer?>? = null

    val isActive: Boolean get() = liveSessions.isNotEmpty()

    fun start(targets: List<Target>, format: OutputFormat) {
        stop()
        targets.forEach { target ->
            val session = Session(target, isTest = false)
            liveSessions[target.destination.id] = session
            session.open(format)
        }
    }

    fun stop() {
        val sessions = liveSessions.values.toList()
        liveSessions.clear()
        _live.value = emptyMap()
        sessions.forEach { it.close() }
    }

    /** Conecta, espera a que el servidor acepte publicar y desconecta sin enviar vídeo. */
    fun test(target: Target, format: OutputFormat) {
        val id = target.destination.id
        testSessions.remove(id)?.close()
        val session = Session(target, isTest = true)
        testSessions[id] = session
        session.open(format)
    }

    fun clearTest(id: String) {
        testSessions.remove(id)?.close()
        _tests.update { it - id }
    }

    fun setVideoInfo(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
        videoInfo = Triple(sps, pps, vps)
        liveSessions.values.forEach { it.pushVideoInfo() }
    }

    fun sendVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        liveSessions.values.forEach { if (it.connected) it.sink.sendVideo(buffer.duplicate(), info) }
    }

    /** Algún destino al aire no da abasto: conviene bajar el bitrate. */
    fun anyCongested(): Boolean = liveSessions.values.any { it.connected && it.sink.hasCongestion() }

    fun sendAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        liveSessions.values.forEach { if (it.connected) it.sink.sendAudio(buffer.duplicate(), info) }
    }

    private inner class Session(val target: Target, val isTest: Boolean) : SinkListener {
        private val id = target.destination.id
        private val registry get() = if (isTest) testSessions else liveSessions
        private val statuses get() = if (isTest) _tests else _live

        val sink: DestinationSink = sinkFactory(
            ConnectionLink.protocolOf(target.publishUrl) ?: StreamProtocol.Rtmp,
            this,
        )

        @Volatile var connected = false
        @Volatile private var closing = false
        private var attempts = 0
        private var startedAt = 0L

        fun open(format: OutputFormat) {
            sink.configure(format)
            pushVideoInfo()
            publish(DestinationStatus.Connecting)
            sink.connect(target.publishUrl)
        }

        fun close() {
            closing = true
            connected = false
            sink.disconnect()
        }

        fun pushVideoInfo() {
            videoInfo?.let { (sps, pps, vps) -> sink.setVideoInfo(sps.duplicate(), pps?.duplicate(), vps?.duplicate()) }
        }

        override fun onConnected() {
            if (!isCurrent()) return
            if (isTest) {
                publish(DestinationStatus.TestPassed)
                registry.remove(id, this)
                close()
                return
            }
            connected = true
            attempts = 0
            if (startedAt == 0L) startedAt = clock()
            publish(DestinationStatus.Live(startedAt))
        }

        override fun onFailed(reason: String) {
            connected = false
            if (!isCurrent()) return
            if (!isTest && sink.shouldRetry(reason)) {
                attempts++
                publish(DestinationStatus.Reconnecting(attempts))
                sink.reconnect(retryDelayMs(attempts))
            } else {
                publish(DestinationStatus.Failed(FailureMessages.describe(reason)))
                registry.remove(id, this)
                close()
            }
        }

        override fun onAuthError() {
            connected = false
            if (!isCurrent()) return
            publish(DestinationStatus.Failed(FailureMessages.AUTH))
            registry.remove(id, this)
            close()
        }

        // Las caídas inesperadas llegan por onFailed; aquí solo terminan los cierres pedidos
        override fun onDisconnected() {
            connected = false
        }

        override fun onBitrate(bitsPerSecond: Long) {
            if (!isCurrent() || isTest) return
            statuses.update { map ->
                val current = map[id] as? DestinationStatus.Live ?: return@update map
                map + (id to current.copy(bitrateKbps = (bitsPerSecond / 1000).toInt()))
            }
        }

        private fun isCurrent() = !closing && registry[id] === this

        private fun publish(status: DestinationStatus) = statuses.update { it + (id to status) }
    }

    companion object {
        /** Estado global para la franja superior: basta un destino al aire para estar «en directo». */
        fun aggregate(statuses: Collection<DestinationStatus>): LiveStatus {
            if (statuses.isEmpty()) return LiveStatus.Offline
            statuses.filterIsInstance<DestinationStatus.Live>().minOfOrNull { it.startedAtMillis }
                ?.let { return LiveStatus.Live(it) }
            statuses.filterIsInstance<DestinationStatus.Reconnecting>().maxOfOrNull { it.attempt }
                ?.let { return LiveStatus.Reconnecting(it) }
            if (statuses.any { it is DestinationStatus.Connecting }) return LiveStatus.Connecting
            statuses.filterIsInstance<DestinationStatus.Failed>().firstOrNull()
                ?.takeIf { statuses.all { s -> s is DestinationStatus.Failed } }
                ?.let { return LiveStatus.Failed(it.reason) }
            return LiveStatus.Offline
        }

        fun totalBitrateKbps(statuses: Collection<DestinationStatus>): Int =
            statuses.sumOf { (it as? DestinationStatus.Live)?.bitrateKbps ?: 0 }
    }
}
