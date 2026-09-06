package com.tvremocon.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
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
     * Returns the stored hash, or null when there is none or it could not be read.
     *
     * Two different failures are deliberately not treated alike. Some mean the key is gone
     * for good — the user changed their screen lock, or the data landed on another device —
     * and the stored ciphertext will never decrypt again, so keeping it only delays asking
     * for the password. Others are momentary: the Keystore can be busy during boot, and a
     * read can fail. Discarding the credentials for one of those would make the user set the
     * app up again over a hiccup, which is what this used to do — it caught Exception.
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
        } catch (e: Exception) {
            if (shouldDiscard(e)) {
                Log.w(TAG, "stored credentials are unusable (${e.javaClass.simpleName}); clearing")
                clear()
            } else {
                // Kept. The next attempt may well succeed, and setup is not re-run for it.
                Log.w(TAG, "could not read stored credentials (${e.javaClass.simpleName})")
            }
            null
        }
    }

    fun clear() {
        file.delete()
    }

    companion object {
        private const val TAG = "TvRemocon"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "tvremocon.authhash"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val FILE_NAME = "authhash.bin"

        /**
         * Whether a failure means the stored ciphertext is permanently unreadable.
         *
         * Pure and visible for tests: the alternative is discovering the boundary by having
         * someone's setup wiped. Only the exception type is used — never its message, which
         * providers are free to word however they like.
         */
        @androidx.annotation.VisibleForTesting
        internal fun shouldDiscard(e: Throwable): Boolean = when (e) {
            // The GCM tag did not check out: wrong key, or the file was altered.
            is AEADBadTagException -> true
            // The Keystore entry no longer authorises this use, or is gone.
            is KeyPermanentlyInvalidatedException -> true
            is UnrecoverableKeyException -> true
            is InvalidKeyException -> true
            // Everything else — IO, a busy Keystore, a malformed-but-rereadable file — is
            // treated as this attempt failing rather than the secret being lost.
            else -> false
        }
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

}
