package com.tvremocon.transport.klap

import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * KLAP v2 record layer: key derivation, encryption, decryption.
 *
 * Pure JVM on purpose — no Android imports — so it runs under plain `testDebugUnitTest`
 * against the vectors in tools/klap_vectors.json.
 *
 * **Not thread-safe, and cannot be made so by adding a lock here.** [seq] advances on every
 * [encrypt] and [decrypt] reuses it, so a request and its response are a single indivisible
 * operation. Serialising is the caller's job; KlapTransport holds a mutex across the whole
 * exchange.
 */
class KlapSession(localSeed: ByteArray, remoteSeed: ByteArray, authHash: ByteArray) {

    private val key: ByteArray = derive("lsk", localSeed, remoteSeed, authHash).copyOf(16)
    private val signingKey: ByteArray = derive("ldk", localSeed, remoteSeed, authHash).copyOf(28)
    private val ivPrefix: ByteArray
    private var sequence: Int

    /** The sequence number the next [encrypt] will use is this plus one. */
    val seq: Int get() = sequence

    init {
        val fullIv = derive("iv", localSeed, remoteSeed, authHash)
        ivPrefix = fullIv.copyOf(12)
        // Signed, big-endian, and it really can be negative — do not widen to unsigned.
        sequence = ByteBuffer.wrap(fullIv, 28, 4).int
    }

    /**
     * Advances [seq], then returns `signature || ciphertext` ready to POST, paired with the
     * sequence number to put in the query string.
     */
    fun encrypt(plaintext: ByteArray): Encrypted {
        sequence += 1
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv()))
        }
        val ciphertext = cipher.doFinal(plaintext)
        val signature = sha256(signingKey, int32be(sequence), ciphertext)
        return Encrypted(signature + ciphertext, sequence)
    }

    /**
     * Verifies the leading 32-byte signature, then decrypts with the sequence number of the
     * request this is a response to.
     *
     * The signature is checked before anything is decrypted. AES-CBC on its own detects
     * nothing: without this, someone on the same network could flip bits in a reply, or
     * substitute one wholesale, and the result would be parsed as if the hub had said it.
     * Forging a reply still needs the signing key, which is derived from the credentials.
     *
     * Throws [IllegalArgumentException] for a truncated payload, [SecurityException] for one
     * whose signature does not match, and [javax.crypto.BadPaddingException] for a body that
     * passes the signature and still fails to decrypt. None of the three is an IOException,
     * which is what makes them easy to miss in a catch.
     */
    fun decrypt(payload: ByteArray): ByteArray {
        require(payload.size > SIGNATURE_SIZE) {
            "response is ${payload.size} bytes, shorter than the signature"
        }
        val ciphertext = payload.copyOfRange(SIGNATURE_SIZE, payload.size)
        val expected = sha256(signingKey, int32be(sequence), ciphertext)
        // Constant-time: a byte-by-byte comparison that stops early leaks how much of a
        // forged signature was right, which is enough to build one a byte at a time.
        if (!MessageDigest.isEqual(expected, payload.copyOf(SIGNATURE_SIZE))) {
            throw SecurityException("response signature does not match for seq $sequence")
        }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv()))
        }
        return cipher.doFinal(ciphertext)
    }

    private fun iv(): ByteArray = ivPrefix + int32be(sequence)

    private fun derive(label: String, vararg parts: ByteArray): ByteArray =
        sha256(label.toByteArray(Charsets.UTF_8), *parts)

    data class Encrypted(val body: ByteArray, val seq: Int) {
        // ByteArray identity would make these compare by reference; content is what matters.
        override fun equals(other: Any?): Boolean =
            this === other || (other is Encrypted && seq == other.seq && body.contentEquals(other.body))

        override fun hashCode(): Int = 31 * body.contentHashCode() + seq
    }

    companion object {
        private const val TRANSFORMATION = "AES/CBC/PKCS5Padding"
        private const val SIGNATURE_SIZE = 32

        /** Handshake 1 body: the device echoes this back with its own seed and hash. */
        const val SEED_SIZE = 16

        /** Length of an authHash in bytes; 64 characters as hex. */
        const val AUTH_HASH_SIZE = 32

        /**
         * Parses an authHash typed or pasted as hex, or null if it is not one.
         *
         * Setup accepts this as an alternative to the password. The hub has no local
         * password of its own — it checks a hash derived from the TP-Link cloud account — so
         * the account cannot be avoided, but deriving the hash elsewhere means the cloud
         * password never reaches this app.
         */
        fun parseAuthHash(text: String): ByteArray? {
            val hex = text.trim().removePrefix("0x")
            if (hex.length != AUTH_HASH_SIZE * 2) return null
            if (!hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
            return ByteArray(AUTH_HASH_SIZE) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }

        /** `SHA256( SHA1(username) || SHA1(password) )`. Password-equivalent — never log it. */
        fun authHash(username: String, password: String): ByteArray =
            sha256(
                sha1(username.toByteArray(Charsets.UTF_8)),
                sha1(password.toByteArray(Charsets.UTF_8)),
            )

        /** What handshake1's response must contain for the credentials to be right. */
        fun expectedDeviceHash(localSeed: ByteArray, remoteSeed: ByteArray, authHash: ByteArray): ByteArray =
            sha256(localSeed, remoteSeed, authHash)

        /** Handshake 2 body. Note the seeds are in the opposite order to handshake 1. */
        fun handshake2Body(localSeed: ByteArray, remoteSeed: ByteArray, authHash: ByteArray): ByteArray =
            sha256(remoteSeed, localSeed, authHash)

        fun sha256(vararg parts: ByteArray): ByteArray = digest("SHA-256", parts)

        private fun sha1(vararg parts: ByteArray): ByteArray = digest("SHA-1", parts)

        private fun digest(algorithm: String, parts: Array<out ByteArray>): ByteArray =
            MessageDigest.getInstance(algorithm).apply { parts.forEach { update(it) } }.digest()

        fun int32be(value: Int): ByteArray = ByteBuffer.allocate(4).putInt(value).array()
    }
}
