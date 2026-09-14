// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Senda Studio contributors

package com.senda.studio.engine.model

/**
 * Posición de un elemento en el lienzo, en coordenadas normalizadas (0..1).
 * Así una escena sirve igual a 720p, 1080p o en vertical.
 */
data class Transform(
    val x: Float = 0f,
    val y: Float = 0f,
    val width: Float = 1f,
    val height: Float = 1f,
    val rotation: Float = 0f,
    val opacity: Float = 1f,
    val crop: Crop = Crop(),
    val fit: FitMode = FitMode.Cover,
) {
    fun moveBy(dx: Float, dy: Float) = copy(x = x + dx, y = y + dy)

    /** Escala alrededor del centro, sin dejar que el elemento desaparezca. */
    fun scaleBy(factor: Float): Transform {
        val w = (width * factor).coerceIn(MIN_SIZE, MAX_SIZE)
        val h = (height * factor).coerceIn(MIN_SIZE, MAX_SIZE)
        return copy(x = x + (width - w) / 2f, y = y + (height - h) / 2f, width = w, height = h)
    }

    companion object {
        const val MIN_SIZE = 0.02f
        const val MAX_SIZE = 4f
        val FullCanvas = Transform()
    }
}

data class Crop(val left: Float = 0f, val top: Float = 0f, val right: Float = 0f, val bottom: Float = 0f)

/** Cómo encaja la imagen de la fuente en su caja. */
enum class FitMode {
    /** Llena la caja recortando lo que sobra, sin deformar. */
    Cover,
    /** Cabe entera dentro de la caja, con bandas si hace falta. */
    Contain,
    /** Estira hasta el tamaño exacto de la caja. */
    Stretch,
}

data class SceneItem(
    val id: String,
    val sourceId: String,
    val transform: Transform = Transform.FullCanvas,
    val visible: Boolean = true,
    val locked: Boolean = false,
)

/** Los elementos se dibujan en orden: el último de la lista queda encima. */
data class Scene(
    val id: String,
    val name: String,
    val items: List<SceneItem> = emptyList(),
)

enum class CanvasOrientation { Landscape, Portrait }

data class CanvasConfig(
    val width: Int = 1280,
    val height: Int = 720,
    val fps: Int = 30,
) {
    val orientation get() = if (width >= height) CanvasOrientation.Landscape else CanvasOrientation.Portrait
    val aspectRatio get() = width.toFloat() / height
    val longSide get() = maxOf(width, height)

    companion object {
        val HD_LANDSCAPE = CanvasConfig(1280, 720, 30)
        val FHD_LANDSCAPE = CanvasConfig(1920, 1080, 30)
        val HD_PORTRAIT = CanvasConfig(720, 1280, 30)
    }
}
