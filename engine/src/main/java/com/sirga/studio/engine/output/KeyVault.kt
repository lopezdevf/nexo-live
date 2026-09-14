// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sirga Studio contributors

package com.sirga.studio.engine.output

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Claves de emisión cifradas con AES-GCM. La llave maestra vive en Android Keystore
 * y no sale del dispositivo, así que una copia del almacenamiento no revela las claves.
 */
class KeyVault(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun put(id: String, secret: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, masterKey()) }
        val encrypted = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        prefs.edit { putString(id, encode(cipher.iv) + SEPARATOR + encode(encrypted)) }
    }

    /** null si no hay clave o si ya no se puede descifrar (p. ej. la llave se invalidó). */
    fun get(id: String): String? {
        val stored = prefs.getString(id, null) ?: return null
        val (iv, data) = stored.split(SEPARATOR).takeIf { it.size == 2 } ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(TAG_BITS, decode(iv)))
            }
            String(cipher.doFinal(decode(data)), Charsets.UTF_8)
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun has(id: String): Boolean = prefs.contains(id)

    fun remove(id: String) = prefs.edit { remove(id) }

    private fun masterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    private fun encode(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun decode(text: String) = Base64.getDecoder().decode(text)

    private companion object {
        const val PREFS = "sirga_stream_keys"
        const val ALIAS = "sirga_stream_keys_master"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val SEPARATOR = ":"
    }
}
