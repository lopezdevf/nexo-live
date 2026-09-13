// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Nexo Live contributors

package com.nexo.live

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.nexo.live.ui.destinations.DestinationsViewModel
import com.nexo.live.ui.studio.StudioScreen
import com.nexo.live.ui.studio.StudioViewModel
import com.nexo.live.ui.theme.NexoTheme

class MainActivity : ComponentActivity() {

    private val app get() = application as NexoApp

    private val studioViewModel: StudioViewModel by viewModels {
        StudioViewModel.factory(app.studio)
    }

    private val destinationsViewModel: DestinationsViewModel by viewModels {
        DestinationsViewModel.factory(app.studio, app.destinations, app.streamer)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleConnectionLink(intent)
        setContent {
            NexoTheme {
                StudioScreen(studioViewModel, destinationsViewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleConnectionLink(intent)
    }

    /**
     * Enlaces rtmp://, rtmps:// o srt:// abiertos o compartidos con la app. Solo rellenan
     * el editor: el usuario revisa servidor y clave y decide si guardar.
     */
    private fun handleConnectionLink(intent: Intent?) {
        val text = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        } ?: return
        destinationsViewModel.handleIncomingText(text)
    }
}
