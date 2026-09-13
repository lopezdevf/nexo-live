// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Nexo Live contributors

package com.nexo.live

import android.app.Application
import com.nexo.live.engine.output.DestinationRepository
import com.nexo.live.engine.output.MultiStreamer
import com.nexo.live.engine.studio.StudioController
import com.nexo.live.engine.studio.seedDefaultStudio

class NexoApp : Application() {

    /** Un único estudio por proceso: lo comparten la UI y el servicio en primer plano. */
    val studio: StudioController by lazy { StudioController().apply { seedDefaultStudio() } }

    val destinations: DestinationRepository by lazy { DestinationRepository(this) }

    val streamer: MultiStreamer by lazy { MultiStreamer() }
}
