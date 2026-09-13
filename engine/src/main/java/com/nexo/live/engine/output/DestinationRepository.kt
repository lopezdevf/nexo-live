// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Nexo Live contributors

package com.nexo.live.engine.output

import android.content.Context
import androidx.core.content.edit
import com.nexo.live.engine.model.StreamDestination
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/** Destinos guardados. Los datos públicos van en preferencias; las claves, en [KeyVault]. */
class DestinationRepository(context: Context, private val vault: KeyVault = KeyVault(context)) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _destinations = MutableStateFlow(load())
    val destinations: StateFlow<List<StreamDestination>> = _destinations.asStateFlow()

    /** [secret] null conserva la clave guardada; vacío la borra. */
    fun save(destination: StreamDestination, secret: String?) {
        when {
            secret == null -> Unit
            secret.isBlank() -> vault.remove(destination.id)
            else -> vault.put(destination.id, secret.trim())
        }
        _destinations.update { list ->
            if (list.any { it.id == destination.id }) list.map { if (it.id == destination.id) destination else it }
            else list + destination
        }
        persist()
    }

    fun setEnabled(id: String, enabled: Boolean) {
        _destinations.update { list -> list.map { if (it.id == id) it.copy(enabled = enabled) else it } }
        persist()
    }

    fun delete(id: String) {
        vault.remove(id)
        _destinations.update { list -> list.filterNot { it.id == id } }
        persist()
    }

    fun hasSecret(id: String): Boolean = vault.has(id)

    /** URL de publicación completa. Solo debe pedirse justo antes de conectar. */
    fun publishUrl(destination: StreamDestination): String =
        ConnectionLink.join(destination.server, vault.get(destination.id))

    private fun load(): List<StreamDestination> {
        val raw = prefs.getString(KEY_LIST, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                array.getJSONObject(i).run {
                    StreamDestination(
                        id = getString("id"),
                        platformId = getString("platform"),
                        name = getString("name"),
                        server = getString("server"),
                        enabled = optBoolean("enabled", true),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun persist() {
        val array = JSONArray()
        _destinations.value.forEach { d ->
            array.put(
                JSONObject()
                    .put("id", d.id)
                    .put("platform", d.platformId)
                    .put("name", d.name)
                    .put("server", d.server)
                    .put("enabled", d.enabled)
            )
        }
        prefs.edit { putString(KEY_LIST, array.toString()) }
    }

    private companion object {
        const val PREFS = "nexo_destinations"
        const val KEY_LIST = "destinations"
    }
}
