// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Nexo Live contributors

package com.nexo.live.engine.studio

import com.nexo.live.engine.model.Source
import com.nexo.live.engine.model.Transform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioControllerTest {

    private var counter = 0
    private val controller = StudioController(newId = { "id${counter++}" })
    private val state get() = controller.state.value

    @Test
    fun `first scene goes on air automatically`() {
        val id = controller.addScene("Juego")
        assertEquals(id, state.programSceneId)
        assertEquals(id, state.previewSceneId)
    }

    @Test
    fun `audio sources go to mixer and not to the scene`() {
        controller.addScene("A")
        val itemId = controller.addSource(Source.Microphone("mic", "Mic"))
        assertNull(itemId)
        assertTrue(state.programScene!!.items.isEmpty())
        assertEquals(listOf("mic"), state.audio.map { it.sourceId })
    }

    @Test
    fun `studio mode edits preview without touching program`() {
        val a = controller.addScene("A")
        val b = controller.addScene("B")
        controller.setStudioMode(true)
        controller.selectScene(b)
        controller.addSource(Source.Screen("screen", "Pantalla"))

        assertEquals(a, state.programSceneId)
        assertTrue(state.programScene!!.items.isEmpty())
        assertEquals(1, state.previewScene!!.items.size)

        controller.transition()
        assertEquals(b, state.programSceneId)
        assertEquals(a, state.previewSceneId)
    }

    @Test
    fun `moveLayer clamps to list bounds`() {
        controller.addScene("A")
        val bottom = controller.addSource(Source.Screen("s", "Pantalla"))!!
        val top = controller.addSource(Source.SolidColor("c", "Color", 0xFF000000))!!

        controller.moveLayer(bottom, +5)
        assertEquals(listOf(top, bottom), state.programScene!!.items.map { it.id })
    }

    @Test
    fun `locked items ignore transforms`() {
        controller.addScene("A")
        val item = controller.addSource(Source.Screen("s", "Pantalla"))!!
        controller.toggleLock(item)
        controller.setTransform(item, Transform(x = 0.5f))
        assertEquals(0f, state.selectedItem!!.transform.x)
    }

    @Test
    fun `last scene cannot be removed`() {
        val a = controller.addScene("A")
        controller.removeScene(a)
        assertEquals(1, state.scenes.size)
    }

    @Test
    fun `gain is clamped`() {
        controller.addScene("A")
        controller.addSource(Source.Microphone("mic", "Mic"))
        controller.setGain("mic", 40f)
        assertEquals(StudioController.MAX_GAIN_DB, state.audio.single().gainDb)
        controller.toggleMute("mic")
        assertTrue(state.audio.single().muted)
        assertFalse(state.isLive)
    }

    @Test
    fun `scaleBy keeps the item centered`() {
        val t = Transform(x = 0.25f, y = 0.25f, width = 0.5f, height = 0.5f).scaleBy(0.5f)
        assertEquals(0.375f, t.x, 1e-5f)
        assertEquals(0.25f, t.width, 1e-5f)
    }
}
