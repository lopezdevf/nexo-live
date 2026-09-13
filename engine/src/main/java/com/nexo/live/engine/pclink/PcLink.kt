// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Nexo Live contributors

package com.nexo.live.engine.pclink

import android.content.Context
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import com.nexo.live.engine.capture.CaptureFormat
import com.nexo.live.engine.capture.CaptureListener
import com.nexo.live.engine.capture.CaptureStatus
import com.nexo.live.engine.capture.SurfaceCapture
import com.nexo.live.engine.model.Source
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

sealed interface PcLinkStatus {
    data class Waiting(val port: Int) : PcLinkStatus
    data class Connected(val width: Int, val height: Int) : PcLinkStatus
    data class Error(val message: String) : PcLinkStatus
}

/** Dirección por la que el PC puede llegar al móvil. */
data class LinkAddress(val label: String, val ip: String)

/**
 * Recibe el vídeo y el audio del PC sin capturadora: OBS (o ffmpeg) envía MPEG-TS por TCP al
 * móvil, que escucha en [port]. Funciona por WiFi, por la zona WiFi del móvil o por anclaje USB.
 *
 * Media3 decodifica por hardware directamente sobre la textura del compositor, y el audio se
 * desvía al mezclador de Nexo (el reproductor está en silencio).
 */
@UnstableApi
class PcLinkReceiver(context: Context, val port: Int) {

    private val appContext = context.applicationContext
    private val thread = HandlerThread("NexoPcLink-$port").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile private var server: ServerSocket? = null
    /** Conexiones abiertas: al reiniciar se cierran, o un hilo de carga se quedaría bloqueado leyendo. */
    private val sockets = java.util.concurrent.CopyOnWriteArraySet<Socket>()
    private var player: ExoPlayer? = null
    private var surface: Surface? = null
    @Volatile private var released = false

    @Volatile var status: PcLinkStatus = PcLinkStatus.Waiting(port)
        private set

    /** Estado y formato hacia la fuente de vídeo. */
    @Volatile var videoListener: CaptureListener? = null

    /** PCM estéreo 16 bits entrelazado ya convertido a [targetSampleRate]. */
    @Volatile var audioListener: ((ShortArray) -> Unit)? = null
    @Volatile var targetSampleRate = 48_000

    fun start() = handler.post {
        if (released) return@post
        try {
            server = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
        } catch (e: Exception) {
            publish(PcLinkStatus.Error("El puerto $port está ocupado. Elige otro en las propiedades."))
            return@post
        }
        createPlayer()
    }

    fun setVideoSurface(newSurface: Surface?) = handler.post {
        surface = newSurface
        player?.setVideoSurface(newSurface)
    }

    fun release() {
        released = true
        runCatching { server?.close() } // desbloquea la espera de conexión
        closeSockets()
        handler.post {
            player?.release()
            player = null
            thread.quitSafely()
        }
    }

    private fun createPlayer() {
        val renderers = object : DefaultRenderersFactory(appContext) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(TeeAudioProcessor(audioTap)))
                    .build()
        }
        val loadControl = DefaultLoadControl.Builder()
            // Colchón mínimo: es un directo, la latencia importa más que absorber cortes largos
            .setBufferDurationsMs(250, 1_500, 100, 250)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val extractors = ExtractorsFactory {
            arrayOf(TsExtractor(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS))
        }
        val source = ProgressiveMediaSource.Factory({ SocketDataSource() }, extractors)
            .createMediaSource(MediaItem.fromUri(Uri.parse("tcp://0.0.0.0:$port")))

        player = ExoPlayer.Builder(appContext, renderers)
            .setLooper(thread.looper)
            .setLoadControl(loadControl)
            .build()
            .apply {
                volume = 0f // se oye a través del mezclador, no por el altavoz
                setVideoSurface(surface)
                addListener(playerListener)
                setMediaSource(source)
                prepare()
                playWhenReady = true
            }
        handler.postDelayed(latencyGuard, 1_000)
    }

    /** Cuando el PC se desconecta o hay un error, se vuelve a esperar otra conexión. */
    private fun restart(reason: String?) {
        if (released) return
        player?.let {
            it.removeListener(playerListener)
            it.release()
        }
        player = null
        closeSockets()
        handler.removeCallbacks(latencyGuard)
        if (reason != null) Log.i(TAG, "Reiniciando enlace con el PC: $reason")
        publish(PcLinkStatus.Waiting(port))
        handler.postDelayed({ if (!released) createPlayer() }, 500)
    }

    private fun closeSockets() {
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
    }

    private val playerListener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                videoListener?.onFormat(CaptureFormat(videoSize.width, videoSize.height))
                publish(PcLinkStatus.Connected(videoSize.width, videoSize.height))
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) restart("fin de la transmisión")
        }

        override fun onPlayerError(error: PlaybackException) = restart(error.errorCodeName)
    }

    /**
     * Si la red se atasca, el retraso se acumula. Se reproduce un poco más rápido hasta volver a
     * menos de medio segundo de colchón, algo imperceptible.
     */
    private val latencyGuard = object : Runnable {
        override fun run() {
            val p = player ?: return
            val buffered = p.totalBufferedDuration
            val speed = when {
                buffered > 1_000 -> 1.1f
                buffered > 500 -> 1.03f
                else -> 1f
            }
            if (p.playbackParameters.speed != speed) p.playbackParameters = PlaybackParameters(speed)
            handler.postDelayed(this, 1_000)
        }
    }

    private fun publish(newStatus: PcLinkStatus) {
        status = newStatus
        when (newStatus) {
            is PcLinkStatus.Waiting -> videoListener?.onStatus(CaptureStatus.Waiting(PcLinkAddresses.waitingMessage(port)))
            is PcLinkStatus.Connected -> videoListener?.onStatus(CaptureStatus.Running)
            is PcLinkStatus.Error -> videoListener?.onStatus(CaptureStatus.Error(newStatus.message))
        }
    }

    /** Fuente de datos que espera la conexión del PC y lee su flujo MPEG-TS. */
    private inner class SocketDataSource : BaseDataSource(true) {
        private var socket: Socket? = null
        private var input: InputStream? = null
        private var opened = false

        override fun open(dataSpec: DataSpec): Long {
            transferInitializing(dataSpec)
            val s = (server ?: throw java.io.IOException("Servidor cerrado")).accept()
            s.tcpNoDelay = true
            s.receiveBufferSize = 1 shl 20
            sockets += s
            socket = s
            input = BufferedInputStream(s.getInputStream(), 1 shl 16)
            opened = true
            transferStarted(dataSpec)
            return C.LENGTH_UNSET.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val n = input?.read(buffer, offset, length) ?: return C.RESULT_END_OF_INPUT
            if (n < 0) return C.RESULT_END_OF_INPUT
            bytesTransferred(n)
            return n
        }

        override fun getUri(): Uri = Uri.parse("tcp://0.0.0.0:$port")

        override fun close() {
            socket?.let { sockets -= it }
            runCatching { socket?.close() }
            socket = null
            input = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    /** Recibe el PCM decodificado antes de que llegue al altavoz (en silencio). */
    private val audioTap = object : TeeAudioProcessor.AudioBufferSink {
        private var sampleRate = 48_000
        private var channels = 2
        private var encoding = C.ENCODING_PCM_16BIT
        private val resampler = LinearResampler()

        override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
            sampleRate = sampleRateHz
            channels = channelCount
            this.encoding = encoding
            resampler.reset()
        }

        override fun handleBuffer(buffer: ByteBuffer) {
            val listener = audioListener ?: return
            if (encoding != C.ENCODING_PCM_16BIT || channels <= 0) return
            val shorts = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val frames = shorts.remaining() / channels
            if (frames == 0) return
            val stereo = ShortArray(frames * 2)
            for (f in 0 until frames) {
                val l = shorts.get(f * channels)
                val r = if (channels > 1) shorts.get(f * channels + 1) else l
                stereo[f * 2] = l
                stereo[f * 2 + 1] = r
            }
            listener(resampler.convert(stereo, sampleRate, targetSampleRate))
        }
    }

    private companion object {
        const val TAG = "NexoPcLink"
    }
}

/** Direcciones y textos de ayuda del enlace con el PC (sin dependencias de Media3). */
object PcLinkAddresses {

    fun waitingMessage(port: Int): String {
        val ips = localAddresses().joinToString(" · ") { "${it.ip}:$port" }
        return if (ips.isEmpty()) "Esperando al PC: conecta el móvil a la misma red WiFi o activa el anclaje USB"
        else "Esperando al PC en $ips"
    }

    /** Direcciones IPv4 útiles: WiFi, zona WiFi del móvil y anclaje USB. */
    fun localAddresses(): List<LinkAddress> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { nif ->
                    nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { addr ->
                        val name = nif.name.lowercase()
                        val label = when {
                            name.startsWith("wlan") -> "WiFi"
                            name.startsWith("swlan") || name.startsWith("ap") -> "Zona WiFi del móvil"
                            name.startsWith("rndis") || name.startsWith("usb") || name.startsWith("ncm") -> "Cable USB (anclaje)"
                            name.startsWith("eth") -> "Ethernet"
                            else -> null
                        }
                        label?.let { LinkAddress(it, addr.hostAddress.orEmpty()) }
                    }
                }
                .filterNotNull()
        }.getOrDefault(emptyList())
}

/** Remuestreo lineal estéreo, suficiente para pasar de 44,1 kHz a 48 kHz sin artefactos audibles en voz y juegos. */
internal class LinearResampler {
    private var position = 0.0
    private var lastL = 0
    private var lastR = 0

    fun reset() {
        position = 0.0
        lastL = 0
        lastR = 0
    }

    fun convert(input: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (fromRate == toRate || fromRate <= 0 || toRate <= 0) return input
        val inFrames = input.size / 2
        val step = fromRate.toDouble() / toRate
        val out = ArrayList<Short>((inFrames * toRate / fromRate + 2) * 2)
        while (position < inFrames) {
            val i = position.toInt()
            val frac = position - i
            val l0 = if (i == 0) lastL else input[(i - 1) * 2].toInt()
            val r0 = if (i == 0) lastR else input[(i - 1) * 2 + 1].toInt()
            val l1 = input[i * 2].toInt()
            val r1 = input[i * 2 + 1].toInt()
            out += (l0 + (l1 - l0) * frac).toInt().toShort()
            out += (r0 + (r1 - r0) * frac).toInt().toShort()
            position += step
        }
        position -= inFrames
        lastL = input[(inFrames - 1) * 2].toInt()
        lastR = input[(inFrames - 1) * 2 + 1].toInt()
        return out.toShortArray()
    }
}

/**
 * Comparte un receptor por fuente entre el compositor (vídeo) y el mezclador (audio): el puerto
 * solo se abre una vez aunque las dos partes lo usen.
 */
@UnstableApi
class PcLinkHub(private val context: Context) {
    private class Entry(val receiver: PcLinkReceiver, var users: Int)

    private val entries = HashMap<String, Entry>()

    @Synchronized
    fun acquire(source: Source.PcInput): PcLinkReceiver {
        entries[source.id]?.let { entry ->
            if (entry.receiver.port == source.port) {
                entry.users++
                return entry.receiver
            }
            entry.receiver.release()
            entries.remove(source.id)
        }
        val receiver = PcLinkReceiver(context, source.port).also { it.start() }
        entries[source.id] = Entry(receiver, 1)
        return receiver
    }

    @Synchronized
    fun release(sourceId: String, receiver: PcLinkReceiver) {
        val entry = entries[sourceId] ?: return
        if (entry.receiver !== receiver) {
            receiver.release()
            return
        }
        entry.users--
        if (entry.users <= 0) {
            entry.receiver.release()
            entries.remove(sourceId)
        }
    }
}

/** Parte de vídeo de la fuente «PC»: el decodificador escribe en la textura del compositor. */
@UnstableApi
class PcCapture(private val hub: PcLinkHub, private val source: Source.PcInput) : SurfaceCapture {
    private var receiver: PcLinkReceiver? = null
    private var surface: Surface? = null

    override fun start(texture: SurfaceTexture, listener: CaptureListener) {
        val r = hub.acquire(source)
        receiver = r
        r.videoListener = listener
        listener.onStatus(
            when (val s = r.status) {
                is PcLinkStatus.Connected -> CaptureStatus.Running.also { listener.onFormat(CaptureFormat(s.width, s.height)) }
                is PcLinkStatus.Error -> CaptureStatus.Error(s.message)
                is PcLinkStatus.Waiting -> CaptureStatus.Waiting(PcLinkAddresses.waitingMessage(source.port))
            }
        )
        val s = Surface(texture)
        surface = s
        r.setVideoSurface(s)
    }

    override fun stop() {
        receiver?.let {
            it.videoListener = null
            it.setVideoSurface(null)
            hub.release(source.id, it)
        }
        receiver = null
        surface?.release()
        surface = null
    }
}
