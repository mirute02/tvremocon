package com.tvremocon.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted storage for the KLAP authHash.
 *
 * The hash is password-equivalent — anything holding it can authenticate to the hub — so it
 * gets the same treatment a password would, not plain SharedPreferences. The raw password is
 * never stored at all; setup derives the hash and discards it.
 *
 * Two layers, because either alone is incomplete:
 *  - AES-GCM under a hardware-backed Android Keystore key, so the file is useless on its own.
 *  - Written to `noBackupFilesDir`, because `allowBackup=false` does not stop device-to-device
 *    transfer on every OEM build.
 *
 * androidx.security:security-crypto would do the same job, but it is deprecated as of 1.1.0
 * and this needs about forty lines.
 */
class SecretStore(context: Context) {

    private val file = File(context.noBackupFilesDir, FILE_NAME)

    fun hasAuthHash(): Boolean = file.exists()

    /** Encrypts and replaces the stored hash. */
    fun putAuthHash(authHash: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ciphertext = cipher.doFinal(authHash)
        // iv length || iv || ciphertext. GCM's IV is 12 bytes here but the length is stored
        // rather than assumed, so a provider that picks differently still round-trips.
        file.outputStream().use { out ->
            out.write(cipher.iv.size)
            out.write(cipher.iv)
            out.write(ciphertext)
        }
    }

    /**
     * Returns the stored hash, or null when there is none or it cannot be decrypted.
     *
     * Decryption failing is a real, recoverable state: the Keystore key is dropped when the
     * user's screen lock changes or the app's data is restored onto a different device, and
     * the right response is to ask for the password again, not to crash.
     */
    fun authHash(): ByteArray? {
        if (!file.exists()) return null
        return try {
            val bytes = file.readBytes()
            val ivSize = bytes[0].toInt()
            val iv = bytes.copyOfRange(1, 1 + ivSize)
            val ciphertext = bytes.copyOfRange(1 + ivSize, bytes.size)
            Cipher.getInstance(TRANSFORMATION)
                .apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv)) }
                .doFinal(ciphertext)
        } catch (e: GeneralSecurityExceptionOrIo) {
            clear()
            null
        }
    }

    fun clear() {
        file.delete()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    // Deliberately not setUserAuthenticationRequired: a home-screen widget has
                    // to work on a locked screen, which is the whole point of it being there.
                    .build()
            )
        }.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "tvremocon.authhash"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val FILE_NAME = "authhash.bin"
    }
}

/** Both failure families mean the same thing here: the stored hash is no longer usable. */
private typealias GeneralSecurityExceptionOrIo = Exception
