// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Nexo Live contributors

package com.nexo.live.engine.devices

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.nexo.live.engine.model.AudioDeviceKey
import com.nexo.live.engine.model.Facing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CameraEntry(val cameraId: String, val facing: Facing, val label: String)

data class UsbCameraEntry(val deviceName: String, val label: String)

enum class AudioDeviceKind { BuiltIn, Wired, Usb, Bluetooth, Hdmi, Other }

data class AudioDeviceEntry(val key: AudioDeviceKey, val label: String, val kind: AudioDeviceKind) {
    /** Bluetooth graba con calidad de llamada (8-16 kHz) y añade retardo. */
    val limitedQuality: Boolean get() = kind == AudioDeviceKind.Bluetooth
}

/**
 * Cámaras, cámaras USB, micrófonos y salidas de audio disponibles, actualizados al conectar o
 * desconectar dispositivos (webcams, capturadoras, audífonos con cable, USB o Bluetooth).
 */
class DeviceCatalog(context: Context) {

    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(CameraManager::class.java)
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val usbManager = appContext.getSystemService(UsbManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _cameras = MutableStateFlow<List<CameraEntry>>(emptyList())
    val cameras: StateFlow<List<CameraEntry>> = _cameras.asStateFlow()

    private val _usbCameras = MutableStateFlow<List<UsbCameraEntry>>(emptyList())
    val usbCameras: StateFlow<List<UsbCameraEntry>> = _usbCameras.asStateFlow()

    private val _inputs = MutableStateFlow<List<AudioDeviceEntry>>(emptyList())
    val inputs: StateFlow<List<AudioDeviceEntry>> = _inputs.asStateFlow()

    private val _outputs = MutableStateFlow<List<AudioDeviceEntry>>(emptyList())
    val outputs: StateFlow<List<AudioDeviceEntry>> = _outputs.asStateFlow()

    private var started = false

    private val cameraCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) = refreshCameras()
        override fun onCameraUnavailable(cameraId: String) = refreshCameras()
    }

    private val audioCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refreshAudio()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refreshAudio()
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshUsb()
            refreshCameras()
        }
    }

    fun start() {
        if (started) return
        started = true
        refreshCameras()
        refreshAudio()
        refreshUsb()
        cameraManager.registerAvailabilityCallback(cameraCallback, mainHandler)
        audioManager.registerAudioDeviceCallback(audioCallback, mainHandler)
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(appContext, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun stop() {
        if (!started) return
        started = false
        cameraManager.unregisterAvailabilityCallback(cameraCallback)
        audioManager.unregisterAudioDeviceCallback(audioCallback)
        runCatching { appContext.unregisterReceiver(usbReceiver) }
    }

    // ---- Búsqueda de dispositivos reales para capturar ---------------------------------

    fun findInput(key: AudioDeviceKey?): AudioDeviceInfo? = key?.let { k ->
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { keyOf(it) == k }
            ?: audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == k.type && it.productName?.toString() == k.productName }
    }

    fun findOutput(key: AudioDeviceKey?): AudioDeviceInfo? = key?.let { k ->
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { keyOf(it) == k }
    }

    /** Primera salida privada (audífonos con cable, USB o Bluetooth), la única segura para monitorizar. */
    fun headphones(): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type in HEADPHONE_TYPES }
            .minByOrNull { HEADPHONE_TYPES.indexOf(it.type) }

    fun findUsbCamera(deviceName: String?): UsbDevice? =
        usbManager.deviceList.values.filter(::isVideoDevice).let { list ->
            if (deviceName == null) list.firstOrNull() else list.firstOrNull { it.deviceName == deviceName }
        }

    fun cameraIdFor(facing: Facing, preferredId: String?): String? {
        val list = _cameras.value.ifEmpty { refreshCameras(); _cameras.value }
        return list.firstOrNull { it.cameraId == preferredId }?.cameraId
            ?: list.firstOrNull { it.facing == facing }?.cameraId
            ?: list.firstOrNull()?.cameraId
    }

    // ---- Enumeración ---------------------------------------------------------------------------

    private fun refreshCameras() {
        val entries = runCatching {
            val counters = HashMap<Facing, Int>()
            cameraManager.cameraIdList.mapNotNull { id ->
                val chars = runCatching { cameraManager.getCameraCharacteristics(id) }.getOrNull() ?: return@mapNotNull null
                val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> Facing.Front
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> Facing.External
                    else -> Facing.Back
                }
                val n = (counters[facing] ?: 0) + 1
                counters[facing] = n
                val base = when (facing) {
                    Facing.Front -> "Cámara frontal"
                    Facing.Back -> "Cámara trasera"
                    Facing.External -> "Cámara externa USB"
                }
                CameraEntry(id, facing, if (n == 1) base else "$base $n")
            }
        }.getOrDefault(emptyList())
        _cameras.value = entries
    }

    private fun refreshUsb() {
        _usbCameras.value = usbManager.deviceList.values.filter(::isVideoDevice).map { d ->
            val name = listOfNotNull(d.manufacturerName, d.productName).joinToString(" ").ifBlank { "Cámara USB" }
            UsbCameraEntry(d.deviceName, name)
        }
    }

    private fun refreshAudio() {
        _inputs.value = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).mapNotNull { entryFor(it, input = true) }.distinctBy { it.key }
        _outputs.value = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).mapNotNull { entryFor(it, input = false) }.distinctBy { it.key }
    }

    private fun entryFor(device: AudioDeviceInfo, input: Boolean): AudioDeviceEntry? {
        val product = device.productName?.toString().orEmpty()
        val (kind, label) = when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> AudioDeviceKind.BuiltIn to builtInMicLabel(device.address)
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioDeviceKind.BuiltIn to "Altavoz del móvil"
            AudioDeviceInfo.TYPE_WIRED_HEADSET ->
                AudioDeviceKind.Wired to if (input) "Micrófono de audífonos (cable)" else "Audífonos con micrófono (cable)"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> AudioDeviceKind.Wired to "Audífonos (cable)"
            AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL -> AudioDeviceKind.Wired to "Entrada de línea"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY ->
                AudioDeviceKind.Usb to "USB: ${product.ifBlank { "dispositivo de audio" }}"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER ->
                AudioDeviceKind.Bluetooth to "Bluetooth: ${product.ifBlank { "dispositivo" }}"
            AudioDeviceInfo.TYPE_HDMI -> AudioDeviceKind.Hdmi to "HDMI"
            else -> return null // auricular de llamada, bus interno, telefonía…
        }
        return AudioDeviceEntry(keyOf(device), label, kind)
    }

    private fun builtInMicLabel(address: String?): String = when (address?.lowercase()) {
        null, "", "bottom" -> "Micrófono del móvil"
        "back" -> "Micrófono trasero del móvil"
        "top" -> "Micrófono superior del móvil"
        else -> "Micrófono del móvil ($address)"
    }

    companion object {
        /** Orden de preferencia para monitorizar: cable (sin retardo) antes que USB y Bluetooth. */
        val HEADPHONE_TYPES = listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        )

        fun keyOf(device: AudioDeviceInfo) =
            AudioDeviceKey(device.type, device.productName?.toString().orEmpty(), device.address.orEmpty())

        /** Clase de vídeo USB (UVC) en el dispositivo o en alguna de sus interfaces. */
        fun isVideoDevice(device: UsbDevice): Boolean =
            device.deviceClass == UsbConstants.USB_CLASS_VIDEO ||
                (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO }
    }
}
