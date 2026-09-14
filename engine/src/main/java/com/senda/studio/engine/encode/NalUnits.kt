// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Senda Studio contributors

package com.senda.studio.engine.encode

import java.nio.ByteBuffer

/** Utilidades Annex-B para separar los parámetros que MediaCodec entrega juntos. */
object NalUnits {

    private val START_CODE = byteArrayOf(0, 0, 0, 1)

    /** Divide un búfer Annex-B en unidades NAL sin código de inicio. */
    fun split(data: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Pair<Int, Int>>() // (inicio del código, inicio del NAL)
        var i = 0
        while (i + 3 <= data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 1.toByte()) {
                    starts += i to i + 3
                    i += 3
                    continue
                }
                if (i + 4 <= data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                    starts += i to i + 4
                    i += 4
                    continue
                }
            }
            i++
        }
        return starts.mapIndexed { index, (_, nalStart) ->
            val end = if (index + 1 < starts.size) starts[index + 1].first else data.size
            data.copyOfRange(nalStart, end)
        }.filter { it.isNotEmpty() }
    }

    /** Tipo de NAL H.265 (VPS = 32, SPS = 33, PPS = 34). */
    fun hevcType(nal: ByteArray): Int = (nal[0].toInt() shr 1) and 0x3F

    /** csd-0 de HEVC trae VPS, SPS y PPS juntos; RootEncoder los quiere por separado y con código de inicio. */
    fun splitHevcConfig(csd0: ByteArray): Triple<ByteBuffer, ByteBuffer, ByteBuffer>? {
        val nals = split(csd0)
        val vps = nals.firstOrNull { hevcType(it) == 32 } ?: return null
        val sps = nals.firstOrNull { hevcType(it) == 33 } ?: return null
        val pps = nals.firstOrNull { hevcType(it) == 34 } ?: return null
        return Triple(withStartCode(vps), withStartCode(sps), withStartCode(pps))
    }

    fun withStartCode(nal: ByteArray): ByteBuffer = ByteBuffer.allocate(START_CODE.size + nal.size).apply {
        put(START_CODE)
        put(nal)
        flip()
    }

    fun toArray(buffer: ByteBuffer): ByteArray {
        val copy = buffer.duplicate()
        return ByteArray(copy.remaining()).also { copy.get(it) }
    }
}
