// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Nexo Live contributors

package com.nexo.live.engine.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService

/**
 * Servicio en primer plano que mantiene el directo o la grabación cuando la app
 * pasa a segundo plano (por ejemplo, al abrir un juego).
 *
 * Android 14+ exige declarar qué usa: solo se piden los tipos de las fuentes activas,
 * y mediaProjection únicamente después de que el usuario haya aceptado capturar la pantalla.
 */
class StudioService : LifecycleService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                val types = intent?.getIntExtra(EXTRA_TYPES, 0) ?: 0
                if (types == 0) {
                    // Android 14+ rechaza un servicio en primer plano sin tipo
                    stopSelf()
                    return START_NOT_STICKY
                }
                ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), types)
            }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Directo y grabación", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openApp = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Nexo Live")
            .setContentText("El estudio está activo")
            .setOngoing(true)
            .setContentIntent(openApp)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "studio"
        private const val NOTIFICATION_ID = 7001
        private const val ACTION_STOP = "com.nexo.live.STOP_STUDIO"
        private const val EXTRA_TYPES = "types"

        fun start(context: Context, camera: Boolean, microphone: Boolean, screen: Boolean) {
            var types = 0
            if (camera) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (microphone) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (screen) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            val intent = Intent(context, StudioService::class.java).putExtra(EXTRA_TYPES, types)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, StudioService::class.java).setAction(ACTION_STOP))
        }
    }
}
