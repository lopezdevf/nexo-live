// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Nexo Live contributors

package com.nexo.live.ui.studio

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nexo.live.engine.capture.CaptureStatus
import com.nexo.live.engine.model.CanvasConfig
import com.nexo.live.engine.model.Scene
import com.nexo.live.engine.model.SceneItem
import com.nexo.live.engine.model.Source
import com.nexo.live.engine.model.Transform
import com.nexo.live.engine.model.kind
import com.nexo.live.engine.render.PreviewSlot
import com.nexo.live.ui.theme.Nexo
import kotlin.math.abs

/**
 * Lienzo editable: la imagen real la pinta el compositor OpenGL en un SurfaceView y encima va
 * la capa de selección, gestos y avisos de cada fuente.
 */
@Composable
fun PreviewCanvas(
    canvas: CanvasConfig,
    scene: Scene?,
    sources: Map<String, Source>,
    selectedItemId: String?,
    slot: PreviewSlot,
    sourceStatus: Map<String, CaptureStatus>,
    onSurface: (PreviewSlot, android.view.Surface?, Int, Int) -> Unit,
    onSelect: (String?) -> Unit,
    onTransform: (String, Transform) -> Unit,
    modifier: Modifier = Modifier,
    frameColor: Color = Nexo.colors.line,
    editable: Boolean = true,
) {
    val items = scene?.items.orEmpty()
    val currentItems by rememberUpdatedState(items)
    val currentSelected by rememberUpdatedState(items.firstOrNull { it.id == selectedItemId })
    val select by rememberUpdatedState(onSelect)
    val transform by rememberUpdatedState(onTransform)
    val tracker = remember { GestureTracker() }

    Box(modifier, contentAlignment = Alignment.Center) {
        BoxWithConstraints(
            Modifier
                .aspectRatio(canvas.aspectRatio)
                .background(Color.Black)
                .border(Nexo.metrics.hairline, frameColor)
                .clipToBounds()
        ) {
            val widthPx = constraints.maxWidth.toFloat()
            val heightPx = constraints.maxHeight.toFloat()

            EngineSurface(slot, onSurface, Modifier.fillMaxSize())

            Box(
                Modifier
                    .fillMaxSize()
                    .then(
                        if (!editable) Modifier else Modifier
                            .pointerInput(Unit) {
                                detectTapGestures { tap ->
                                    val nx = tap.x / size.width
                                    val ny = tap.y / size.height
                                    val hit = currentItems.lastOrNull { it.visible && it.transform.contains(nx, ny) }
                                    select(hit?.id)
                                }
                            }
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan: Offset, zoom, _ ->
                                    val item = currentSelected ?: return@detectTransformGestures
                                    if (item.locked) return@detectTransformGestures
                                    transform(item.id, tracker.next(item, zoom, pan.x / size.width, pan.y / size.height))
                                }
                            }
                    )
            ) {
                items.forEach { item ->
                    val source = sources[item.sourceId] ?: return@forEach
                    val status = sourceStatus[item.sourceId]
                    if (item.visible && source.hasVideo && status !is CaptureStatus.Running && status != null) {
                        StatusBadgeOverlay(item, source, status, widthPx, heightPx)
                    }
                }
                if (editable) currentSelected?.let { SelectionFrame(it, widthPx, heightPx) }
            }
        }
    }
}

/** SurfaceView donde dibuja el compositor; avisa al motor al crearse, cambiar o destruirse. */
@Composable
private fun EngineSurface(slot: PreviewSlot, onSurface: (PreviewSlot, android.view.Surface?, Int, Int) -> Unit, modifier: Modifier) {
    val callback by rememberUpdatedState(onSurface)
    val holderCallback = remember(slot) {
        object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
                callback(slot, holder.surface, width, height)
            override fun surfaceDestroyed(holder: SurfaceHolder) = callback(slot, null, 0, 0)
        }
    }
    DisposableEffect(slot) {
        onDispose { callback(slot, null, 0, 0) }
    }
    AndroidView(
        factory = { context -> SurfaceView(context).apply { holder.addCallback(holderCallback) } },
        onRelease = { it.holder.removeCallback(holderCallback) },
        modifier = modifier,
    )
}

@Composable
private fun StatusBadgeOverlay(item: SceneItem, source: Source, status: CaptureStatus, widthPx: Float, heightPx: Float) {
    val t = item.transform
    val message = when (status) {
        CaptureStatus.Starting -> "Iniciando ${source.name}…"
        is CaptureStatus.Waiting -> status.message
        is CaptureStatus.Error -> status.message
        CaptureStatus.Running -> return
    }
    with(LocalDensity.current) {
        Box(
            Modifier
                .offset(x = (t.x * widthPx).toDp(), y = (t.y * heightPx).toDp())
                .size(width = (t.width * widthPx).toDp(), height = (t.height * heightPx).toDp())
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(6.dp)).padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(source.kind.icon, contentDescription = null, tint = if (status is CaptureStatus.Error) Nexo.colors.record else Color.White)
                Text(message, color = Color.White, style = Nexo.numeric, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun SelectionFrame(item: SceneItem, widthPx: Float, heightPx: Float) {
    val t = item.transform
    val accent = if (item.locked) Nexo.colors.textMid else Nexo.colors.volt
    val handle = 10.dp
    with(LocalDensity.current) {
        Box(
            Modifier
                .offset(x = (t.x * widthPx).toDp(), y = (t.y * heightPx).toDp())
                .size(width = (t.width * widthPx).toDp(), height = (t.height * heightPx).toDp())
                .border(2.dp, accent)
        ) {
            if (!item.locked) {
                listOf(Alignment.TopStart, Alignment.TopEnd, Alignment.BottomStart, Alignment.BottomEnd).forEach {
                    Box(
                        Modifier
                            .align(it)
                            .padding(1.dp)
                            .size(handle)
                            .background(accent, RoundedCornerShape(2.dp))
                    )
                }
            }
        }
    }
}

private fun Transform.contains(nx: Float, ny: Float) = nx in x..(x + width) && ny in y..(y + height)

private const val SNAP = 0.015f

/** Imán a los bordes y al centro del lienzo, como las guías de OBS. */
private fun Transform.snapped(): Transform {
    fun snap(start: Float, length: Float) =
        listOf(0f, 1f - length, 0.5f - length / 2).firstOrNull { abs(start - it) < SNAP } ?: start
    return copy(x = snap(x, width), y = snap(y, height))
}

/**
 * Acumula el gesto sin imán y solo aplica el imán a lo que se emite; si no,
 * un arrastre lento nunca podría salir de la zona de atracción.
 */
private class GestureTracker {
    private var itemId: String? = null
    private var raw: Transform? = null
    private var emitted: Transform? = null

    fun next(item: SceneItem, zoom: Float, dx: Float, dy: Float): Transform {
        val base = raw.takeIf { itemId == item.id && emitted == item.transform } ?: item.transform
        val moved = base.scaleBy(zoom).moveBy(dx, dy)
        itemId = item.id
        raw = moved
        return moved.snapped().also { emitted = it }
    }
}
