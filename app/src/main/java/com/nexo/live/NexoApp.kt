// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Nexo Live contributors

package com.nexo.live

import android.app.Application
import com.nexo.live.engine.StudioEngine
import com.nexo.live.engine.StudioEngineHost
import com.nexo.live.engine.devices.DeviceCatalog
import com.nexo.live.engine.output.DestinationRepository
import com.nexo.live.engine.output.MultiStreamer
import com.nexo.live.engine.settings.SettingsRepository
import com.nexo.live.engine.studio.StudioController
import com.nexo.live.engine.studio.StudioState
import com.nexo.live.engine.studio.StudioStore
import com.nexo.live.engine.studio.seedDefaultStudio

class NexoApp : Application(), StudioEngineHost {

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
