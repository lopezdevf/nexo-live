// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Nexo Live contributors

package com.nexo.live.engine.studio

import com.nexo.live.engine.model.Facing
import com.nexo.live.engine.model.Source
import com.nexo.live.engine.model.Transform

/** Estudio inicial para que la app no arranque vacía: una escena de juego, una de cámara y una de pausa. */
fun StudioController.seedDefaultStudio() {
    if (state.value.scenes.isNotEmpty()) return

    val gaming = addScene("Juego")
    addSource(Source.Screen(id = "screen", name = "Pantalla"))
    addSource(
        Source.Camera(id = "cam-front", name = "Cámara frontal", facing = Facing.Front),
        Transform(x = 0.72f, y = 0.66f, width = 0.26f, height = 0.30f),
    )
    addSource(Source.Microphone(id = "mic", name = "Micrófono"))
    addSource(Source.InternalAudio(id = "internal", name = "Audio del juego"))

    addScene("Cámara").also { selectScene(it) }
    addSource(Source.Camera(id = "cam-back", name = "Cámara trasera", facing = Facing.Back))
    addSource(
        Source.Text(id = "title", name = "Título", text = "En directo con Nexo Live", backgroundArgb = 0x99000000),
        Transform(x = 0.04f, y = 0.84f, width = 0.5f, height = 0.1f),
    )

    addScene("Pausa").also { selectScene(it) }
    addSource(Source.SolidColor(id = "bg", name = "Fondo", argb = 0xFF101218))
    addSource(
        Source.Text(id = "brb", name = "Mensaje", text = "Volvemos enseguida"),
        Transform(x = 0.25f, y = 0.42f, width = 0.5f, height = 0.16f),
    )

    selectScene(gaming)
    selectItem(null)
}
