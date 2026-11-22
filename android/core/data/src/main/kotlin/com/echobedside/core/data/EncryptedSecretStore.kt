package com.echobedside.core.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.echobedside.core.model.SecretStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [SecretStore] encrypted with AES-256-GCM under an Android Keystore key (never leaves the
 * keystore). Ciphertexts live in a private no-backup prefs file; the key name is the AAD, so a
 * value can't be moved to another key. A value that fails to decrypt reads as not set.
 */
class EncryptedSecretStore(context: Context) : SecretStore {
    private val prefs = context.getSharedPreferences("echo_secrets", Context.MODE_PRIVATE)
    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 16)
    override val changes: SharedFlow<String> = _changes.asSharedFlow()
    private val cache = HashMap<String, String?>()

    override fun get(key: String): String? = synchronized(cache) {
        cache.getOrPut(key) { prefs.getString(key, null)?.let { decrypt(key, it) } }
    }

    override fun set(key: String, value: String?) {
        synchronized(cache) {
            if (value.isNullOrBlank()) { prefs.edit().remove(key).apply(); cache[key] = null }
            else { prefs.edit().putString(key, encrypt(key, value)).apply(); cache[key] = value }
        }
        _changes.tryEmit(key)
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    private fun encrypt(name: String, value: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        c.updateAAD(name.toByteArray())
        val ct = c.doFinal(value.toByteArray())
        return Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)
    }

    private fun decrypt(name: String, stored: String): String? = try {
        val b = Base64.decode(stored, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12)) }
        c.updateAAD(name.toByteArray())
        String(c.doFinal(b, 12, b.size - 12))
    } catch (e: Exception) { null }

    private companion object { const val ALIAS = "echo_secrets_v1" }
}
