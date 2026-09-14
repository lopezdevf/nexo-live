// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Senda Studio contributors

package com.senda.studio.engine.thermal

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

/**
 * Lee el estado térmico real: aviso del sistema al instante y, cada [pollMillis], el margen previsto
 * (Android 11+) y la temperatura de batería, que en móviles sin HAL térmico es la única pista.
 */
class ThermalMonitor(context: Context, private val pollMillis: Long = 10_000) {

    private val appContext = context.applicationContext
    private val power = appContext.getSystemService(PowerManager::class.java)

    fun readings(): Flow<ThermalReading> = callbackFlow {
        var status = map(power.currentThermalStatus)

        fun emitReading() {
            trySend(ThermalReading(status = status, headroom = headroom(), batteryTempC = batteryTemperature()))
        }

        val listener = PowerManager.OnThermalStatusChangedListener { newStatus ->
            status = map(newStatus)
            emitReading()
        }
        val mainExecutor: Executor = appContext.mainExecutor
        power.addThermalStatusListener(mainExecutor, listener)

        val poller = launch {
            while (true) {
                emitReading()
                delay(pollMillis)
            }
        }

        awaitClose {
            poller.cancel()
            power.removeThermalStatusListener(listener)
        }
    }

    private fun headroom(): Float? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        // Devuelve NaN si se consulta más de una vez por segundo o no hay soporte
        return power.getThermalHeadroom(FORECAST_SECONDS).takeIf { !it.isNaN() && it > 0f }
    }

    private fun batteryTemperature(): Float? {
        val intent: Intent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (tenths == Int.MIN_VALUE) null else tenths / 10f
    }

    private fun map(status: Int): ThermalLevel = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> ThermalLevel.None
        PowerManager.THERMAL_STATUS_LIGHT -> ThermalLevel.Light
        PowerManager.THERMAL_STATUS_MODERATE -> ThermalLevel.Moderate
        PowerManager.THERMAL_STATUS_SEVERE -> ThermalLevel.Severe
        PowerManager.THERMAL_STATUS_CRITICAL -> ThermalLevel.Critical
        else -> ThermalLevel.Emergency // EMERGENCY y SHUTDOWN
    }

    private companion object {
        const val FORECAST_SECONDS = 10
    }
}
