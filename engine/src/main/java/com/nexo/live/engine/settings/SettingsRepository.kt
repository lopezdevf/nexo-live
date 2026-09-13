// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Nexo Live contributors

package com.nexo.live.engine.settings

import android.content.Context
import androidx.core.content.edit
import com.nexo.live.engine.model.AudioDeviceKey
import com.nexo.live.engine.model.VideoCodecChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/** Guarda [StudioSettings] como JSON. Ante datos corruptos vuelve a los valores por defecto. */
class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("nexo_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<StudioSettings> = _settings.asStateFlow()

    fun update(block: (StudioSettings) -> StudioSettings) {
        _settings.update { sanitize(block(it)) }
        prefs.edit { putString(KEY, encode(_settings.value).toString()) }
    }

    fun reset() = update { StudioSettings() }

    private fun load(): StudioSettings =
        prefs.getString(KEY, null)?.let { runCatching { sanitize(decode(JSONObject(it))) }.getOrNull() } ?: StudioSettings()

    companion object {
        private const val KEY = "settings_v1"

        /** Impide combinaciones que el codificador rechazaría. */
        fun sanitize(s: StudioSettings): StudioSettings = s.copy(
            video = s.video.copy(
                width = s.video.width.coerceIn(160, 3840) and 1.inv(),
                height = s.video.height.coerceIn(160, 3840) and 1.inv(),
                fps = s.video.fps.coerceIn(10, 60),
                bitrateKbps = s.video.bitrateKbps.coerceIn(VideoSettings.MIN_BITRATE, VideoSettings.MAX_BITRATE),
                keyframeSec = s.video.keyframeSec.coerceIn(1, 10),
                previewFps = s.video.previewFps.coerceIn(5, 60),
            ),
            audio = s.audio.copy(
                sampleRate = if (s.audio.sampleRate in AudioSettings.SAMPLE_RATES) s.audio.sampleRate else 48_000,
                bitrateKbps = s.audio.bitrateKbps.coerceIn(64, 320),
            ),
            thermal = s.thermal.copy(
                minFps = s.thermal.minFps.coerceIn(10, 60),
                minBitrateKbps = s.thermal.minBitrateKbps.coerceIn(300, 10_000),
                batteryLimitC = s.thermal.batteryLimitC.coerceIn(35f, 50f),
                recoverySeconds = s.thermal.recoverySeconds.coerceIn(15, 600),
            ),
            transition = s.transition.copy(durationMs = s.transition.durationMs.coerceIn(0, 2_000)),
        )

        internal fun encode(s: StudioSettings): JSONObject = JSONObject()
            .put("video", JSONObject()
                .put("width", s.video.width).put("height", s.video.height).put("fps", s.video.fps)
                .put("bitrate", s.video.bitrateKbps).put("keyframe", s.video.keyframeSec)
                .put("codec", s.video.codec.name).put("adaptive", s.video.adaptiveBitrate)
                .put("previewFps", s.video.previewFps))
            .put("audio", JSONObject()
                .put("sampleRate", s.audio.sampleRate).put("bitrate", s.audio.bitrateKbps)
                .put("allowSpeaker", s.audio.allowSpeakerMonitoring)
                .put("monitor", s.audio.monitorDevice?.let { encodeKey(it) } ?: JSONObject.NULL))
            .put("thermal", JSONObject()
                .put("policy", s.thermal.policy.name).put("minFps", s.thermal.minFps)
                .put("minBitrate", s.thermal.minBitrateKbps).put("allowResolutionDrop", s.thermal.allowResolutionDrop)
                .put("batteryLimit", s.thermal.batteryLimitC.toDouble()).put("stopAtEmergency", s.thermal.stopAtEmergency)
                .put("recovery", s.thermal.recoverySeconds))
            .put("recording", JSONObject()
                .put("folder", s.recording.folder).put("shareEncoder", s.recording.shareStreamEncoder))
            .put("transition", JSONObject()
                .put("type", s.transition.type.name).put("duration", s.transition.durationMs))

        internal fun decode(json: JSONObject): StudioSettings {
            val d = StudioSettings()
            val v = json.optJSONObject("video") ?: JSONObject()
            val a = json.optJSONObject("audio") ?: JSONObject()
            val t = json.optJSONObject("thermal") ?: JSONObject()
            val r = json.optJSONObject("recording") ?: JSONObject()
            val tr = json.optJSONObject("transition") ?: JSONObject()
            return StudioSettings(
                video = VideoSettings(
                    width = v.optInt("width", d.video.width),
                    height = v.optInt("height", d.video.height),
                    fps = v.optInt("fps", d.video.fps),
                    bitrateKbps = v.optInt("bitrate", d.video.bitrateKbps),
                    keyframeSec = v.optInt("keyframe", d.video.keyframeSec),
                    codec = enumOr(v.optString("codec"), d.video.codec),
                    adaptiveBitrate = v.optBoolean("adaptive", d.video.adaptiveBitrate),
                    previewFps = v.optInt("previewFps", d.video.previewFps),
                ),
                audio = AudioSettings(
                    sampleRate = a.optInt("sampleRate", d.audio.sampleRate),
                    bitrateKbps = a.optInt("bitrate", d.audio.bitrateKbps),
                    allowSpeakerMonitoring = a.optBoolean("allowSpeaker", d.audio.allowSpeakerMonitoring),
                    monitorDevice = a.optJSONObject("monitor")?.let { decodeKey(it) },
                ),
                thermal = ThermalSettings(
                    policy = enumOr(t.optString("policy"), d.thermal.policy),
                    minFps = t.optInt("minFps", d.thermal.minFps),
                    minBitrateKbps = t.optInt("minBitrate", d.thermal.minBitrateKbps),
                    allowResolutionDrop = t.optBoolean("allowResolutionDrop", d.thermal.allowResolutionDrop),
                    batteryLimitC = t.optDouble("batteryLimit", d.thermal.batteryLimitC.toDouble()).toFloat(),
                    stopAtEmergency = t.optBoolean("stopAtEmergency", d.thermal.stopAtEmergency),
                    recoverySeconds = t.optInt("recovery", d.thermal.recoverySeconds),
                ),
                recording = RecordingSettings(
                    folder = r.optString("folder", d.recording.folder).ifBlank { d.recording.folder },
                    shareStreamEncoder = r.optBoolean("shareEncoder", d.recording.shareStreamEncoder),
                ),
                transition = TransitionSettings(
                    type = enumOr(tr.optString("type"), d.transition.type),
                    durationMs = tr.optInt("duration", d.transition.durationMs),
                ),
            )
        }

        fun encodeKey(key: AudioDeviceKey): JSONObject =
            JSONObject().put("type", key.type).put("name", key.productName).put("address", key.address)

        fun decodeKey(json: JSONObject) = AudioDeviceKey(json.optInt("type"), json.optString("name"), json.optString("address"))

        private inline fun <reified E : Enum<E>> enumOr(name: String, default: E): E =
            enumValues<E>().firstOrNull { it.name == name } ?: default
    }
}
