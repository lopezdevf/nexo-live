// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Nexo Live contributors

package com.nexo.live.engine.pclink

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Protocolo «Nexo Link» v1 entre Nexo Live PC y el móvil. TCP con enteros big-endian.
 *
 * Saludo del PC: `"NXL1" · u16 código · u16 longitud + nombre del PC (UTF-8)`.
 * Respuesta del móvil: `"NXL1" · u8 resultado · u16 longitud + nombre del móvil`.
 * Después, paquetes en ambos sentidos: `u8 tipo · u32 longitud · contenido`.
 *
 * Todo está pensado para el menor retraso posible: el PC envía cada fotograma en cuanto sale del
 * codificador y el móvil lo decodifica y lo muestra sin búfer de espera.
 */
object NexoLink {
    val MAGIC = byteArrayOf('N'.code.toByte(), 'X'.code.toByte(), 'L'.code.toByte(), '1'.code.toByte())
    const val DEFAULT_PORT = 9000
    const val DISCOVERY_PORT = 9750
    val DISCOVERY_QUERY = "NXL1?".toByteArray(Charsets.US_ASCII)
    val DISCOVERY_REPLY = "NXL1!".toByteArray(Charsets.US_ASCII)

    // PC → móvil
    /** u16 ancho · u16 alto · u8 fps */
    const val VIDEO_FORMAT = 0x01
    /** u8 marcas (bit 0 = fotograma clave) · u64 instante de captura en µs (reloj del PC) · H.264 Annex-B */
    const val VIDEO_FRAME = 0x02
    /** u32 frecuencia · u8 canales · u64 instante en µs · PCM 16 bits little-endian entrelazado */
    const val AUDIO_PCM = 0x03
    /** u64 hora del móvil (eco de la petición) · u64 hora del PC en µs */
    const val TIME_REPLY = 0x05
    /**
     * u32 milisegundos: retraso medido por el PC con [FRAME_SHOWN]. Es una cota superior honesta
     * (incluye la vuelta del aviso por la red) y no depende de sincronizar relojes.
     */
    const val LATENCY_REPORT = 0x07

    // móvil → PC
    /** Sin contenido: el decodificador necesita empezar por un fotograma clave. */
    const val KEYFRAME_REQUEST = 0x81
    /** u64 hora del móvil en µs; el PC responde con [TIME_REPLY]. */
    const val TIME_REQUEST = 0x82
    /** u64 instante de captura (reloj del PC) del fotograma que se acaba de mostrar. */
    const val FRAME_SHOWN = 0x83

    const val RESULT_OK = 0
    const val RESULT_WRONG_CODE = 1
    const val RESULT_UNSUPPORTED = 2

    const val MAX_PACKET = 16 * 1024 * 1024
    private const val MAX_NAME = 256

    data class Hello(val code: Int, val pcName: String)

    data class Packet(val type: Int, val payload: ByteArray)

    /** Lee el saludo del PC. null si no es un cliente de Nexo Link. */
    fun readHello(input: DataInputStream): Hello? {
        val magic = ByteArray(4)
        input.readFully(magic)
        if (!magic.contentEquals(MAGIC)) return null
        val code = input.readUnsignedShort()
        val name = readString(input)
        return Hello(code, name)
    }

    fun writeHello(out: DataOutputStream, code: Int, pcName: String) {
        out.write(MAGIC)
        out.writeShort(code)
        writeString(out, pcName)
        out.flush()
    }

    fun writeReply(out: DataOutputStream, result: Int, deviceName: String) {
        out.write(MAGIC)
        out.writeByte(result)
        writeString(out, deviceName)
        out.flush()
    }

    fun readPacket(input: DataInputStream, reuse: ByteArray? = null): Packet {
        val type = input.readUnsignedByte()
        val length = input.readInt()
        if (length < 0 || length > MAX_PACKET) throw IOException("Paquete de $length bytes")
        val payload = if (reuse != null && reuse.size == length) reuse else ByteArray(length)
        input.readFully(payload)
        return Packet(type, payload)
    }

    fun writePacket(out: DataOutputStream, type: Int, payload: ByteArray = EMPTY) {
        out.writeByte(type)
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
    }

    fun timeRequest(phoneUs: Long): ByteArray = ByteBuffer.allocate(8).putLong(phoneUs).array()

    fun frameShown(captureUs: Long): ByteArray = ByteBuffer.allocate(8).putLong(captureUs).array()

    /** Respuesta de descubrimiento: `"NXL1!" · u16 puerto · u16+nombre del móvil · u16+nombre de la fuente · u8 pide código`. */
    fun discoveryReply(port: Int, deviceName: String, sourceName: String): ByteArray {
        val device = deviceName.take(MAX_NAME).toByteArray(Charsets.UTF_8)
        val source = sourceName.take(MAX_NAME).toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(DISCOVERY_REPLY.size + 2 + 2 + device.size + 2 + source.size + 1)
            .put(DISCOVERY_REPLY)
            .putShort(port.toShort())
            .putShort(device.size.toShort()).put(device)
            .putShort(source.size.toShort()).put(source)
            .put(1)
            .array()
    }

    fun isDiscoveryQuery(data: ByteArray, length: Int): Boolean =
        length >= DISCOVERY_QUERY.size && (DISCOVERY_QUERY.indices).all { data[it] == DISCOVERY_QUERY[it] }

    private fun readString(input: DataInputStream): String {
        val length = input.readUnsignedShort()
        if (length > MAX_NAME * 4) throw IOException("Nombre de $length bytes")
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.take(MAX_NAME).toByteArray(Charsets.UTF_8)
        out.writeShort(bytes.size)
        out.write(bytes)
    }

    private val EMPTY = ByteArray(0)
}

/**
 * Traduce la hora del móvil al reloj del PC para medir el retraso real de cada fotograma
 * (desde que el PC lo captura hasta que el móvil lo muestra). Como NTP: se queda con la muestra
 * de menor ida y vuelta, que es la que menos error tiene.
 */
class ClockSync(private val window: Int = 16) {
    private class Sample(val roundTripUs: Long, val offsetUs: Long)

    private val samples = ArrayDeque<Sample>()

    /** Diferencia reloj del PC − reloj del móvil, o null si aún no hay muestras. */
    @get:Synchronized
    val offsetUs: Long?
        get() = samples.minByOrNull { it.roundTripUs }?.offsetUs

    @Synchronized
    fun add(phoneSentUs: Long, pcUs: Long, phoneReceivedUs: Long) {
        val roundTrip = phoneReceivedUs - phoneSentUs
        if (roundTrip < 0) return
        samples.addLast(Sample(roundTrip, pcUs - (phoneSentUs + roundTrip / 2)))
        while (samples.size > window) samples.removeFirst()
    }

    /** Milisegundos transcurridos desde [pcCaptureUs] hasta [phoneNowUs], o null sin sincronizar. */
    fun latencyMs(pcCaptureUs: Long, phoneNowUs: Long): Long? = offsetUs?.let { (phoneNowUs + it - pcCaptureUs) / 1000 }

    @Synchronized
    fun reset() = samples.clear()
}
