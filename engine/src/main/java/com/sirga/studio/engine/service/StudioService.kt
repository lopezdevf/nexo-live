// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sirga Studio contributors

package com.sirga.studio.engine.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.LifecycleService
import com.sirga.studio.engine.StudioEngineHost

/**
 * Servicio en primer plano que mantiene el directo o la grabación cuando la app pasa a segundo
 * plano (por ejemplo, al abrir un juego).
 *
 * Android 14+ exige declarar qué usa: solo se piden los tipos de las fuentes activas, y el permiso
 * de captura de pantalla se entrega al motor después de iniciar el servicio con ese tipo.
 */
class StudioService : LifecycleService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val types = intent?.getIntExtra(EXTRA_TYPES, 0) ?: 0
        if (types == 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ rechaza un servicio en primer plano sin tipo
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), types)
        } catch (e: Exception) {
            // Sin permisos del tipo pedido (p. ej. cámara revocada): no se puede seguir en segundo plano
            stopSelf()
            return START_NOT_STICKY
        }

        val resultData = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_PROJECTION_DATA, Intent::class.java) }
        if (resultData != null) {
            val resultCode = intent.getIntExtra(EXTRA_PROJECTION_CODE, 0)
            (application as? StudioEngineHost)?.engine?.onScreenCapturePermission(resultCode, resultData)
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
            .setContentTitle("Sirga Studio")
            .setContentText("El estudio está activo")
            .setOngoing(true)
            .setContentIntent(openApp)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "studio"
        private const val NOTIFICATION_ID = 7001
        private const val ACTION_STOP = "com.sirga.studio.STOP_STUDIO"
        private const val EXTRA_TYPES = "types"
        private const val EXTRA_PROJECTION_CODE = "projection_code"
        private const val EXTRA_PROJECTION_DATA = "projection_data"

        fun start(context: Context, camera: Boolean, microphone: Boolean, screen: Boolean) {
            val intent = Intent(context, StudioService::class.java).putExtra(EXTRA_TYPES, types(camera, microphone, screen))
            ContextCompat.startForegroundService(context, intent)
        }

        /** Inicia el servicio con tipo mediaProjection y le pasa el permiso de captura recién concedido. */
        fun startWithScreenCapture(context: Context, resultCode: Int, data: Intent, camera: Boolean, microphone: Boolean) {
            val intent = Intent(context, StudioService::class.java)
                .putExtra(EXTRA_TYPES, types(camera, microphone, screen = true))
                .putExtra(EXTRA_PROJECTION_CODE, resultCode)
                .putExtra(EXTRA_PROJECTION_DATA, data)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, StudioService::class.java).setAction(ACTION_STOP))
        }

        private fun types(camera: Boolean, microphone: Boolean, screen: Boolean): Int {
            var types = 0
            // Los tipos cámara y micrófono existen desde Android 11; antes no hacen falta
            if (camera && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (microphone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (screen) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            return types
        }
    }
}
