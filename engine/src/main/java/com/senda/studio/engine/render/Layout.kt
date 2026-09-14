// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Senda Studio contributors

package com.senda.studio.engine.render

import com.senda.studio.engine.model.Crop
import com.senda.studio.engine.model.FitMode

/** Rectángulo en píxeles del lienzo. */
data class PixelRect(val x: Float, val y: Float, val width: Float, val height: Float)

/** Zona de la textura a muestrear, en coordenadas de contenido ya orientado (0..1, origen arriba). */
data class UvRect(val u: Float, val v: Float, val width: Float, val height: Float) {
    companion object {
        val FULL = UvRect(0f, 0f, 1f, 1f)
    }
}

data class Placement(val quad: PixelRect, val uv: UvRect)

/**
 * Geometría pura (probada sin GPU): recorte, encaje y proporción de cada fuente dentro de su caja.
 */
object Layout {

    fun place(box: PixelRect, contentWidth: Int, contentHeight: Int, crop: Crop, fit: FitMode): Placement {
        val l = crop.left.coerceIn(0f, 0.95f)
        val t = crop.top.coerceIn(0f, 0.95f)
        val cropW = (1f - l - crop.right.coerceIn(0f, 0.95f)).coerceAtLeast(0.05f)
        val cropH = (1f - t - crop.bottom.coerceIn(0f, 0.95f)).coerceAtLeast(0.05f)

        if (contentWidth <= 0 || contentHeight <= 0 || box.width <= 0f || box.height <= 0f || fit == FitMode.Stretch) {
            return Placement(box, UvRect(l, t, cropW, cropH))
        }

        val contentAspect = (contentWidth * cropW) / (contentHeight * cropH)
        val boxAspect = box.width / box.height

        return when (fit) {
            FitMode.Contain -> {
                val quad = if (contentAspect > boxAspect) {
                    val h = box.width / contentAspect
                    PixelRect(box.x, box.y + (box.height - h) / 2f, box.width, h)
                } else {
                    val w = box.height * contentAspect
                    PixelRect(box.x + (box.width - w) / 2f, box.y, w, box.height)
                }
                Placement(quad, UvRect(l, t, cropW, cropH))
            }
            FitMode.Cover -> {
                val uv = if (contentAspect > boxAspect) {
                    val visible = boxAspect / contentAspect
                    UvRect(l + cropW * (1f - visible) / 2f, t, cropW * visible, cropH)
                } else {
                    val visible = contentAspect / boxAspect
                    UvRect(l, t + cropH * (1f - visible) / 2f, cropW, cropH * visible)
                }
                Placement(box, uv)
            }
            FitMode.Stretch -> Placement(box, UvRect(l, t, cropW, cropH))
        }
    }

    /** Encaja el lienzo en una superficie de vista previa conservando la proporción. */
    fun letterbox(canvasWidth: Int, canvasHeight: Int, surfaceWidth: Int, surfaceHeight: Int): PixelRect {
        val canvasAspect = canvasWidth.toFloat() / canvasHeight
        val surfaceAspect = surfaceWidth.toFloat() / surfaceHeight
        return if (canvasAspect > surfaceAspect) {
            val h = surfaceWidth / canvasAspect
            PixelRect(0f, (surfaceHeight - h) / 2f, surfaceWidth.toFloat(), h)
        } else {
            val w = surfaceHeight * canvasAspect
            PixelRect((surfaceWidth - w) / 2f, 0f, w, surfaceHeight.toFloat())
        }
    }

    /** Tamaño de contenido tras rotar el búfer de la cámara. */
    fun rotatedSize(width: Int, height: Int, rotationDegrees: Int): Pair<Int, Int> =
        if (rotationDegrees % 180 != 0) height to width else width to height
}
