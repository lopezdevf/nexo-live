// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Senda Studio contributors

package com.senda.studio

import android.app.Application
import com.senda.studio.engine.StudioEngine
import com.senda.studio.engine.StudioEngineHost
import com.senda.studio.engine.devices.DeviceCatalog
import com.senda.studio.engine.output.DestinationRepository
import com.senda.studio.engine.output.MultiStreamer
import com.senda.studio.engine.settings.SettingsRepository
import com.senda.studio.engine.studio.StudioController
import com.senda.studio.engine.studio.StudioState
import com.senda.studio.engine.studio.StudioStore
import com.senda.studio.engine.studio.seedDefaultStudio

class SendaApp : Application(), StudioEngineHost {

    /** Un único motor por proceso: lo comparten la interfaz y el servicio en primer plano. */
    override val engine: StudioEngine by lazy {
        val settings = SettingsRepository(this)
        val store = StudioStore(this)
        val saved = store.load()
        val studio = StudioController(
            StudioState(
                canvas = settings.settings.value.canvas,
                sources = saved?.sources.orEmpty(),
                scenes = saved?.scenes.orEmpty(),
                audio = saved?.audio.orEmpty(),
                programSceneId = saved?.programSceneId,
                previewSceneId = saved?.programSceneId,
            )
        ).apply { seedDefaultStudio() }
        StudioEngine(
            context = this,
            studio = studio,
            settings = settings,
            destinations = DestinationRepository(this),
            streamer = MultiStreamer(),
            devices = DeviceCatalog(this),
            store = store,
        )
    }
}
