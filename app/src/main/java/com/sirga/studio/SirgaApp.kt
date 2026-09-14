// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sirga Studio contributors

package com.sirga.studio

import android.app.Application
import com.sirga.studio.engine.StudioEngine
import com.sirga.studio.engine.StudioEngineHost
import com.sirga.studio.engine.devices.DeviceCatalog
import com.sirga.studio.engine.output.DestinationRepository
import com.sirga.studio.engine.output.MultiStreamer
import com.sirga.studio.engine.settings.SettingsRepository
import com.sirga.studio.engine.studio.StudioController
import com.sirga.studio.engine.studio.StudioState
import com.sirga.studio.engine.studio.StudioStore
import com.sirga.studio.engine.studio.seedDefaultStudio

class SirgaApp : Application(), StudioEngineHost {

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
