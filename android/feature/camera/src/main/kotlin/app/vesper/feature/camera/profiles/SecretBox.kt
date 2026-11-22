package app.vesper.feature.camera.profiles

import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM envelope for face templates: `[version 1][iv length][iv][ciphertext + 16-byte tag]`.
 * [aad] (the profile id) is authenticated, so a template file copied onto another profile fails.
 * The key comes from [keyProvider]: the Android Keystore on the device (never leaves the TEE /
 * keystore daemon), a plain JCE key in unit tests.
 */
class SecretBox(private val keyProvider: () -> SecretKey) {

    fun seal(plain: ByteArray, aad: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORM)
        // The Keystore insists on generating the IV itself; JCE does too when none is given.
        c.init(Cipher.ENCRYPT_MODE, keyProvider())
        c.updateAAD(aad)
        val iv = c.iv
        val body = c.doFinal(plain)
        require(iv.size in 12..16)
        return ByteArray(2 + iv.size + body.size).also {
            it[0] = VERSION
            it[1] = iv.size.toByte()
            iv.copyInto(it, 2)
            body.copyInto(it, 2 + iv.size)
        }
    }

    /** @throws SecurityException on a wrong version, truncated data, a wrong key or tampering. */
    fun open(sealed: ByteArray, aad: ByteArray): ByteArray {
        if (sealed.size < 2) throw SecurityException("truncated")
        if (sealed[0] != VERSION) throw SecurityException("unknown version ${sealed[0]}")
        val ivLen = sealed[1].toInt() and 0xFF
        if (ivLen !in 12..16 || sealed.size < 2 + ivLen + TAG_BYTES) throw SecurityException("truncated")
        val iv = sealed.copyOfRange(2, 2 + ivLen)
        return try {
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(TAG_BYTES * 8, iv))
            c.updateAAD(aad)
            c.doFinal(sealed, 2 + ivLen, sealed.size - 2 - ivLen)
        } catch (e: Exception) {
            throw SecurityException("can't open template: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        const val VERSION: Byte = 1
        const val TAG_BYTES = 16
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val KEY_ALIAS = "vesper.camera.templates.v1"

        /** The device key: AES-256 in the Android Keystore, created on first use. */
        fun androidKeystore(): SecretBox = SecretBox {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(KEY_ALIAS, null) as? SecretKey) ?: run {
                val spec = android.security.keystore.KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                    .apply { init(spec) }.generateKey()
            }
        }

        /** Removes the device key: every stored template becomes unreadable ("forget everyone"). */
        fun deleteAndroidKey() {
            try { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS) } catch (_: Exception) {}
        }
    }
}
