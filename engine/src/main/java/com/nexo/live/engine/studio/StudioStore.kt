// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Nexo Live contributors

package com.nexo.live.engine.studio

import android.content.Context
import androidx.core.content.edit
import com.nexo.live.engine.model.AudioChannel
import com.nexo.live.engine.model.Crop
import com.nexo.live.engine.model.Facing
import com.nexo.live.engine.model.FitMode
import com.nexo.live.engine.model.MonitoringMode
import com.nexo.live.engine.model.Scene
import com.nexo.live.engine.model.SceneItem
import com.nexo.live.engine.model.Source
import com.nexo.live.engine.model.newPairingCode
import com.nexo.live.engine.model.TextAlignment
import com.nexo.live.engine.model.Transform
import com.nexo.live.engine.settings.SettingsRepository
import org.json.JSONArray
import org.json.JSONObject

/** Lo que se conserva entre sesiones: escenas, fuentes, capas y mezclador. */
data class StudioSnapshot(
    val sources: Map<String, Source>,
    val scenes: List<Scene>,
    val audio: List<AudioChannel>,
    val programSceneId: String?,
)

fun StudioState.snapshot() = StudioSnapshot(sources, scenes, audio, programSceneId)

/** Guarda la colección de escenas como JSON. Si el archivo está dañado se empieza de cero. */
class StudioStore(context: Context) {

    private val prefs = context.getSharedPreferences("nexo_studio", Context.MODE_PRIVATE)

    fun load(): StudioSnapshot? = prefs.getString(KEY, null)?.let { raw ->
        runCatching { decode(JSONObject(raw)) }.getOrNull()?.takeIf { it.scenes.isNotEmpty() }
    }

    fun save(snapshot: StudioSnapshot) = prefs.edit { putString(KEY, encode(snapshot).toString()) }

    private companion object {
        const val KEY = "studio_v1"

        fun encode(s: StudioSnapshot): JSONObject = JSONObject()
            .put("program", s.programSceneId ?: JSONObject.NULL)
            .put("sources", JSONArray().apply { s.sources.values.forEach { put(encodeSource(it)) } })
            .put("scenes", JSONArray().apply {
                s.scenes.forEach { scene ->
                    put(JSONObject().put("id", scene.id).put("name", scene.name).put("items", JSONArray().apply {
                        scene.items.forEach { item ->
                            put(JSONObject().put("id", item.id).put("source", item.sourceId).put("visible", item.visible)
                                .put("locked", item.locked).put("transform", encodeTransform(item.transform)))
                        }
                    }))
                }
            })
            .put("audio", JSONArray().apply {
                s.audio.forEach { c ->
                    put(JSONObject().put("source", c.sourceId).put("gain", c.gainDb.toDouble()).put("muted", c.muted)
                        .put("balance", c.balance.toDouble()).put("monitoring", c.monitoring.name))
                }
            })

        fun decode(json: JSONObject): StudioSnapshot {
            val sources = json.getJSONArray("sources").objects().mapNotNull { decodeSource(it) }.associateBy { it.id }
            val scenes = json.getJSONArray("scenes").objects().map { scene ->
                Scene(
                    id = scene.getString("id"),
                    name = scene.getString("name"),
                    items = scene.getJSONArray("items").objects()
                        .filter { it.getString("source") in sources }
                        .map { item ->
                            SceneItem(
                                id = item.getString("id"),
                                sourceId = item.getString("source"),
                                transform = decodeTransform(item.getJSONObject("transform")),
                                visible = item.optBoolean("visible", true),
                                locked = item.optBoolean("locked", false),
                            )
                        },
                )
            }
            val audio = json.getJSONArray("audio").objects()
                .filter { it.getString("source") in sources }
                .map { c ->
                    AudioChannel(
                        sourceId = c.getString("source"),
                        gainDb = c.optDouble("gain", 0.0).toFloat(),
                        muted = c.optBoolean("muted", false),
                        balance = c.optDouble("balance", 0.0).toFloat(),
                        monitoring = enumOr(c.optString("monitoring"), MonitoringMode.Off),
                    )
                }
            val program = json.optString("program").takeIf { id -> scenes.any { it.id == id } } ?: scenes.firstOrNull()?.id
            return StudioSnapshot(sources, scenes, audio, program)
        }

        fun encodeSource(source: Source): JSONObject {
            val o = JSONObject().put("id", source.id).put("name", source.name)
            return when (source) {
                is Source.Camera -> o.put("type", "camera").put("facing", source.facing.name).put("cameraId", source.cameraId ?: JSONObject.NULL)
                    .put("rotation", source.rotationOffset).put("mirror", source.mirror)
                is Source.UsbCamera -> o.put("type", "usb").put("device", source.deviceName ?: JSONObject.NULL)
                    .put("rotation", source.rotationOffset).put("mirror", source.mirror)
                is Source.PcInput -> o.put("type", "pc").put("port", source.port).put("code", source.code)
                is Source.Screen -> o.put("type", "screen")
                is Source.Image -> o.put("type", "image").put("uri", source.uri)
                is Source.Text -> o.put("type", "text").put("text", source.text).put("color", source.colorArgb)
                    .put("background", source.backgroundArgb).put("bold", source.bold).put("alignment", source.alignment.name)
                is Source.SolidColor -> o.put("type", "color").put("argb", source.argb)
                is Source.Microphone -> o.put("type", "mic").put("device", source.device?.let { SettingsRepository.encodeKey(it) } ?: JSONObject.NULL)
                    .put("noise", source.noiseSuppression).put("echo", source.echoCancellation).put("stereo", source.stereo)
                is Source.InternalAudio -> o.put("type", "internal")
            }
        }

        fun decodeSource(o: JSONObject): Source? {
            val id = o.getString("id")
            val name = o.getString("name")
            return when (o.getString("type")) {
                "camera" -> Source.Camera(id, name, enumOr(o.optString("facing"), Facing.Back), o.nullableString("cameraId"),
                    o.optInt("rotation", 0), o.optBoolean("mirror", false))
                "usb" -> Source.UsbCamera(id, name, o.nullableString("device"), o.optInt("rotation", 0), o.optBoolean("mirror", false))
                "pc" -> Source.PcInput(id, name, o.optInt("port", 9000), o.optString("code").takeIf { it.length == 4 } ?: newPairingCode())
                "screen" -> Source.Screen(id, name)
                "image" -> Source.Image(id, name, o.getString("uri"))
                "text" -> Source.Text(id, name, o.optString("text"), o.optLong("color", 0xFFFFFFFF), o.optLong("background", 0),
                    o.optBoolean("bold", true), enumOr(o.optString("alignment"), TextAlignment.Center))
                "color" -> Source.SolidColor(id, name, o.optLong("argb", 0xFF000000))
                "mic" -> Source.Microphone(id, name, o.optJSONObject("device")?.let { SettingsRepository.decodeKey(it) },
                    o.optBoolean("noise", false), o.optBoolean("echo", false), o.optBoolean("stereo", false))
                "internal" -> Source.InternalAudio(id, name)
                else -> null
            }
        }

        fun encodeTransform(t: Transform): JSONObject = JSONObject()
            .put("x", t.x.toDouble()).put("y", t.y.toDouble()).put("w", t.width.toDouble()).put("h", t.height.toDouble())
            .put("rotation", t.rotation.toDouble()).put("opacity", t.opacity.toDouble()).put("fit", t.fit.name)
            .put("crop", JSONArray().put(t.crop.left.toDouble()).put(t.crop.top.toDouble()).put(t.crop.right.toDouble()).put(t.crop.bottom.toDouble()))

        fun decodeTransform(o: JSONObject): Transform {
            val crop = o.optJSONArray("crop")
            return Transform(
                x = o.optDouble("x", 0.0).toFloat(),
                y = o.optDouble("y", 0.0).toFloat(),
                width = o.optDouble("w", 1.0).toFloat(),
                height = o.optDouble("h", 1.0).toFloat(),
                rotation = o.optDouble("rotation", 0.0).toFloat(),
                opacity = o.optDouble("opacity", 1.0).toFloat(),
                crop = if (crop != null && crop.length() == 4) {
                    Crop(crop.getDouble(0).toFloat(), crop.getDouble(1).toFloat(), crop.getDouble(2).toFloat(), crop.getDouble(3).toFloat())
                } else Crop(),
                fit = enumOr(o.optString("fit"), FitMode.Cover),
            )
        }

        fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

        fun JSONObject.nullableString(key: String): String? = if (isNull(key) || !has(key)) null else getString(key)

        inline fun <reified E : Enum<E>> enumOr(name: String, default: E): E = enumValues<E>().firstOrNull { it.name == name } ?: default
    }
}
