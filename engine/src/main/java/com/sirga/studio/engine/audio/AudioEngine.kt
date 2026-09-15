// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sirga Studio contributors

package com.sirga.studio.engine.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import com.sirga.studio.engine.devices.DeviceCatalog
import com.sirga.studio.engine.model.MonitoringMode
import com.sirga.studio.engine.model.Source
import com.sirga.studio.engine.pclink.PcLinkHub
import com.sirga.studio.engine.pclink.PcLinkReceiver
import com.sirga.studio.engine.pclink.PcDeviceStream
import com.sirga.studio.engine.pclink.PcDeviceKind
import com.sirga.studio.engine.settings.StudioSettings
import com.sirga.studio.engine.studio.StudioController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.locks.LockSupport

/**
 * Captura cada fuente de audio en su hilo, las mezcla en bloques de 1024 muestras (un fotograma AAC)
 * con el reloj del sistema y reparte la mezcla al codificador y a los audífonos.
 */
class AudioEngine(
    context: Context,
    private val studio: StudioController,
    private val devices: DeviceCatalog,
    private val settings: StateFlow<StudioSettings>,
    private val projection: () -> MediaProjection?,
    private val pcLink: PcLinkHub,
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)

    /** Recibe cada bloque mezclado para el aire con su marca de tiempo en microsegundos. */
    @Volatile var programSink: ((ShortArray, Long) -> Unit)? = null

    private val _levels = MutableStateFlow<Map<String, Float>>(emptyMap())
    /** Nivel de vúmetro 0..1 por fuente, ~15 veces por segundo. */
    val levels: StateFlow<Map<String, Float>> = _levels.asStateFlow()

    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors: StateFlow<Map<String, String>> = _errors.asStateFlow()

    private val _monitorStatus = MutableStateFlow<String?>(null)
    val monitorStatus: StateFlow<String?> = _monitorStatus.asStateFlow()

    private val captures = HashMap<String, Capture>()
    @Volatile private var running = false
    private var mixerThread: Thread? = null
    private var monitor: AudioTrack? = null
    private var monitorDeviceId: Int? = null

    fun start() {
        if (running) return
        running = true
        mixerThread = Thread(::mixLoop, "SirgaAudioMixer").apply { start() }
    }

    fun stop() {
        running = false
        mixerThread?.join(1_000)
        mixerThread = null
        synchronized(captures) {
            captures.values.forEach { it.stop() }
            captures.clear()
        }
        releaseMonitor()
        _levels.value = emptyMap()
    }

    private fun mixLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val sampleRate = settings.value.audio.sampleRate
        val mixer = AudioMixer(FRAMES)
        val blockNanos = FRAMES * 1_000_000_000L / sampleRate
        val startNanos = System.nanoTime()
        var blocks = 0L
        var lastSync = 0L
        var lastLevels = 0L

        while (running) {
            val now = System.nanoTime()
            if (now - lastSync > 500_000_000L) {
                syncCaptures(sampleRate)
                syncMonitor(sampleRate)
                lastSync = now
            }

            val state = studio.state.value
            val inputs = HashMap<String, PcmBlock?>()
            synchronized(captures) {
                for (channel in state.audio) inputs[channel.sourceId] = captures[channel.sourceId]?.read(FRAMES)
            }
            val result = mixer.mix(inputs, state.audio)
            val ptsUs = (startNanos + blocks * blockNanos) / 1000
            programSink?.invoke(result.program, ptsUs)
            result.monitor?.let { monitor?.write(it, 0, it.size, AudioTrack.WRITE_NON_BLOCKING) }

            if (now - lastLevels > 66_000_000L) {
                _levels.value = result.peaks.mapValues { AudioMixer.peakToMeter(it.value) }
                lastLevels = now
            }

            blocks++
            // Reloj propio: el siguiente bloque sale exactamente cuando toca, no cuando llega audio
            val deadline = startNanos + blocks * blockNanos
            val wait = deadline - System.nanoTime()
            if (wait > 0) LockSupport.parkNanos(wait)
        }
    }

    /** Abre las fuentes nuevas, cierra las borradas y reabre si cambió el dispositivo elegido. */
    private fun syncCaptures(sampleRate: Int) {
        val state = studio.state.value
        val wanted = state.audio.mapNotNull { state.sources[it.sourceId] }.filter { it.hasAudio }
        synchronized(captures) {
            val wantedIds = wanted.map { it.id }.toSet()
            captures.keys.filter { it !in wantedIds }.forEach { id ->
                captures.remove(id)?.stop()
                _errors.update { it - id }
            }
            for (source in wanted) {
                val existing = captures[source.id]
                if (existing != null && existing.source == source && existing.alive) continue
                existing?.stop()
                _errors.update { it - source.id }
                val capture = Capture(source, sampleRate)
                if (capture.open()) {
                    captures[source.id] = capture
                } else {
                    captures.remove(source.id)
                }
            }
        }
    }

    private fun syncMonitor(sampleRate: Int) {
        val state = studio.state.value
        val needed = state.audio.any { it.monitoring != MonitoringMode.Off }
        if (!needed) {
            releaseMonitor()
            _monitorStatus.value = null
            return
        }
        val audioSettings = settings.value.audio
        val device = devices.findOutput(audioSettings.monitorDevice) ?: devices.headphones()
            ?: if (audioSettings.allowSpeakerMonitoring) {
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            } else null
        if (device == null) {
            releaseMonitor()
            _monitorStatus.value = "Conecta audífonos para escuchar la monitorización"
            return
        }
        if (monitor != null && monitorDeviceId == device.id) return
        releaseMonitor()
        monitor = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(FRAMES * 2 * 2 * 4)
                .build()
                .apply {
                    setPreferredDevice(device)
                    play()
                }
        }.getOrNull()
        monitorDeviceId = device.id
        _monitorStatus.value = when {
            monitor == null -> "No se pudo abrir la salida de monitorización"
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ->
                "Monitorizando por Bluetooth: tendrá retardo. Usa audífonos con cable para escuchar en tiempo real."
            else -> null
        }
    }

    private fun releaseMonitor() {
        monitor?.let { runCatching { it.stop() }; it.release() }
        monitor = null
        monitorDeviceId = null
    }

    /** Captura de una fuente: su propio hilo llena un búfer circular que el mezclador vacía. */
    private inner class Capture(val source: Source, private val sampleRate: Int) {
        private var record: AudioRecord? = null
        private var thread: Thread? = null
        private val ring = RingBuffer(sampleRate) // medio segundo estéreo
        @Volatile var alive = false
        private var channels = 2
        private var effects = mutableListOf<android.media.audiofx.AudioEffect>()

        private var pcReceiver: PcLinkReceiver? = null
        /** Fuente PC cuyo receptor se tomó prestado (la propia o la de un micrófono del PC). */
        private var pcSourceId: String? = null
        private var pcStream: PcDeviceStream? = null

        @SuppressLint("MissingPermission")
        fun open(): Boolean {
            if (source is Source.PcInput) {
                // El audio del PC llega ya decodificado desde el receptor: no hace falta micrófono
                val receiver = pcLink.acquire(source)
                receiver.targetSampleRate = sampleRate
                receiver.audioListener = { pcm -> ring.write(pcm, pcm.size) }
                pcReceiver = receiver
                pcSourceId = source.id
                alive = true
                return true
            }
            if (source is Source.PcMicrophone) {
                // El micrófono del PC llega en su propia señal por la conexión de su fuente PC
                val pc = studio.state.value.sources[source.pcSourceId] as? Source.PcInput
                if (pc == null) {
                    fail("Falta la fuente «PC» por la que llega este micrófono")
                    return false
                }
                val receiver = pcLink.acquire(pc)
                val stream = receiver.openStream(PcDeviceKind.Microphone, source.deviceId, source.deviceName)
                receiver.setStreamAudio(stream, sampleRate) { pcm -> ring.write(pcm, pcm.size) }
                pcReceiver = receiver
                pcSourceId = pc.id
                pcStream = stream
                alive = true
                return true
            }
            if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                fail("Falta el permiso de micrófono")
                return false
            }
            val r = try {
                when (source) {
                    is Source.Microphone -> openMicrophone(source)
                    is Source.InternalAudio -> openInternal() ?: return false
                    else -> return false
                }
            } catch (e: Exception) {
                fail("No se pudo abrir el audio: ${e.message}")
                return false
            }
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                r.release()
                fail("El dispositivo de audio no está disponible")
                return false
            }
            record = r
            alive = true
            r.startRecording()
            thread = Thread({ readLoop(r) }, "SirgaAudio-${source.name}").apply { start() }
            return true
        }

        @SuppressLint("MissingPermission")
        private fun openMicrophone(mic: Source.Microphone): AudioRecord {
            val device = devices.findInput(mic.device)
            if (mic.device != null && device == null) fail("«${mic.name}»: el dispositivo elegido no está conectado; se usa el micrófono del móvil")
            val bluetooth = device != null && (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || device.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
            if (bluetooth && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { audioManager.setCommunicationDevice(device) }
            }
            channels = if (mic.stereo && !bluetooth) 2 else 1
            val audioSource = when {
                mic.echoCancellation || bluetooth -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
                device != null && device.type != AudioDeviceInfo.TYPE_BUILTIN_MIC -> MediaRecorder.AudioSource.MIC
                else -> MediaRecorder.AudioSource.CAMCORDER
            }
            val r = AudioRecord.Builder()
                .setAudioSource(audioSource)
                .setAudioFormat(format(channels))
                .setBufferSizeInBytes(bufferSize(channels))
                .build()
            device?.let { r.setPreferredDevice(it) }
            if (mic.noiseSuppression && NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(r.audioSessionId)?.let { it.enabled = true; effects += it }
            }
            if (mic.echoCancellation && AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(r.audioSessionId)?.let { it.enabled = true; effects += it }
            }
            return r
        }

        @SuppressLint("MissingPermission")
        private fun openInternal(): AudioRecord? {
            val p = projection()
            if (p == null) {
                fail("Permite la captura de pantalla para grabar el audio del juego")
                return null
            }
            val config = AudioPlaybackCaptureConfiguration.Builder(p)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            channels = 2
            return AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(format(channels))
                .setBufferSizeInBytes(bufferSize(channels))
                .build()
        }

        private fun format(channels: Int) = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO)
            .build()

        private fun bufferSize(channels: Int): Int {
            val min = AudioRecord.getMinBufferSize(sampleRate, if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            return maxOf(min, FRAMES * channels * 2 * 4)
        }

        private fun readLoop(r: AudioRecord) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buffer = ShortArray(FRAMES * channels)
            while (alive) {
                val n = r.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                when {
                    n > 0 -> if (channels == 2) ring.write(buffer, n) else ring.write(AudioMixer.monoToStereo(buffer, n), n * 2)
                    n == AudioRecord.ERROR_DEAD_OBJECT -> {
                        fail("Se perdió el dispositivo de audio; reconectando…")
                        alive = false
                    }
                    n < 0 -> {
                        Log.w(TAG, "Error de lectura $n en ${source.name}")
                        alive = false
                    }
                }
            }
        }

        fun read(frames: Int): PcmBlock? = ring.read(frames * 2)?.let { PcmBlock(it) }

        fun stop() {
            alive = false
            pcReceiver?.let { receiver ->
                val stream = pcStream
                if (stream != null) {
                    receiver.setStreamAudio(stream, sampleRate, null)
                    receiver.closeStream(stream)
                } else {
                    receiver.audioListener = null
                }
                pcLink.release(pcSourceId ?: source.id, receiver)
            }
            pcReceiver = null
            pcStream = null
            pcSourceId = null
            runCatching { record?.stop() }
            thread?.join(500)
            effects.forEach { runCatching { it.release() } }
            effects.clear()
            record?.release()
            record = null
        }

        private fun fail(message: String) {
            _errors.update { it + (source.id to message) }
        }
    }

    private companion object {
        const val TAG = "SirgaAudio"
        const val FRAMES = 1024
    }
}

/** Búfer circular de muestras estéreo. Si el productor va más rápido que el reloj, descarta lo más viejo. */
internal class RingBuffer(capacitySamples: Int) {
    private val data = ShortArray(capacitySamples)
    private var readPos = 0
    private var size = 0
    private var primed = false

    @Synchronized
    fun write(samples: ShortArray, count: Int) {
        for (i in 0 until count) {
            data[(readPos + size) % data.size] = samples[i]
            if (size < data.size) size++ else readPos = (readPos + 1) % data.size
        }
        // Mantiene la latencia acotada: más de ~200 ms acumulados se recortan a ~100 ms
        val maxBacklog = data.size * 2 / 5
        if (size > maxBacklog) {
            val drop = size - data.size / 5
            readPos = (readPos + drop) % data.size
            size -= drop
        }
    }

    /** null mientras no hay suficiente audio (tras un corte espera ~40 ms de colchón). */
    @Synchronized
    fun read(count: Int): ShortArray? {
        if (!primed) {
            if (size < count * 2) return null
            primed = true
        }
        if (size < count) {
            primed = false
            return null
        }
        val out = ShortArray(count)
        for (i in 0 until count) out[i] = data[(readPos + i) % data.size]
        readPos = (readPos + count) % data.size
        size -= count
        return out
    }
}
