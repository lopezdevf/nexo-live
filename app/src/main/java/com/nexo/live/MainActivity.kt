// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Nexo Live contributors

package com.nexo.live

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.nexo.live.ui.destinations.DestinationsViewModel
import com.nexo.live.ui.studio.StudioScreen
import com.nexo.live.ui.studio.StudioViewModel
import com.nexo.live.ui.theme.NexoTheme

class MainActivity : ComponentActivity() {

    private val engine get() = (application as NexoApp).engine

    private val studioViewModel: StudioViewModel by viewModels { StudioViewModel.factory(application as NexoApp) }

    private val destinationsViewModel: DestinationsViewModel by viewModels { DestinationsViewModel.factory(engine) }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        studioViewModel.onPermissionsResult(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            handleConnectionLink(intent)
            permissions.launch(requiredPermissions())
        }
        setContent {
            NexoTheme {
                StudioScreen(studioViewModel, destinationsViewModel, onRequestPermissions = { permissions.launch(requiredPermissions()) })
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

    private fun requiredPermissions(): Array<String> = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        // Nombres de audífonos y micrófonos Bluetooth
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
    }.toTypedArray()
}
