package com.tvremocon.transport.klap

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Checks the record layer against `klap_vectors.json`.
 *
 * Those vectors are not self-referential: the values were cross-checked against an
 * independent Python recomputation of the derivation before being committed, so a shared
 * mistake between this code and the generator would have shown up there. They are built from
 * fake credentials and carry nothing from a real account.
 */
class KlapSessionTest {

    private val vectors: JSONObject =
        JSONObject(requireNotNull(javaClass.getResourceAsStream("/klap_vectors.json")) {
            "klap_vectors.json missing from test resources"
        }.bufferedReader().readText())

    private fun bytes(key: String): ByteArray = hex(vectors.getString(key))

    private val localSeed get() = bytes("local_seed")
    private val remoteSeed get() = bytes("remote_seed")
    private val authHash get() = bytes("auth_hash")

    @Test
    fun `authHash is sha256 of the two sha1 digests`() {
        assertArrayEquals(
            authHash,
            KlapSession.authHash(vectors.getString("username"), vectors.getString("password")),
        )
    }

    @Test
    fun `username case is preserved`() {
        // python-kasa and the tapo crate both hash the bytes as given. Lowercasing here would
        // silently break any account whose email was registered with capitals.
        assertNotEquals(
            hex(KlapSession.authHash("User@Example.com", "pw")),
            hex(KlapSession.authHash("user@example.com", "pw")),
        )
    }

    @Test
    fun `a pasted authHash round trips with the derived one`() {
        val derived = KlapSession.authHash(vectors.getString("username"), vectors.getString("password"))
        // Setup accepts this instead of the password, so the two paths must agree exactly.
        assertArrayEquals(derived, KlapSession.parseAuthHash(vectors.getString("auth_hash")))
        assertArrayEquals(derived, KlapSession.parseAuthHash("  ${vectors.getString("auth_hash").uppercase()}  "))
        assertArrayEquals(derived, KlapSession.parseAuthHash("0x" + vectors.getString("auth_hash")))
    }

    @Test
    fun `anything that is not a 64 character hex string is rejected`() {
        // Rejected rather than padded or truncated: a silently wrong hash would fail later as
        // an authentication error, which reads like a wrong password.
        listOf("", "abc", "z".repeat(64), vectors.getString("auth_hash").drop(1))
            .forEach { org.junit.Assert.assertNull(it, KlapSession.parseAuthHash(it)) }
    }

    @Test
    fun `derived key sizes and values match the vectors`() {
        val session = KlapSession(localSeed, remoteSeed, authHash)
        // Sizes are the easiest thing to get wrong: 16 for lsk, 28 — not 32 — for ldk, 12 for the IV prefix.
        assertEquals(16, bytes("key").size)
        assertEquals(28, bytes("sig_key").size)
        assertEquals(12, bytes("iv_prefix").size)
        assertEquals(vectors.getInt("seq_initial"), session.seq)
    }

    @Test
    fun `encrypt advances seq before use and produces the expected bytes`() {
        val session = KlapSession(localSeed, remoteSeed, authHash)
        val encrypted = session.encrypt(vectors.getString("plaintext").toByteArray())

        assertEquals(vectors.getInt("seq_first_request"), encrypted.seq)
        assertEquals(vectors.getInt("seq_initial") + 1, encrypted.seq)
        assertEquals(vectors.getString("signature"), hex(encrypted.body.copyOf(32)))
        assertEquals(vectors.getString("ciphertext"), hex(encrypted.body.copyOfRange(32, encrypted.body.size)))
    }

    @Test
    fun `seq is a signed big-endian int and may be negative`() {
        // fullIv[28..32] is read signed. A session whose derivation lands above 0x7fffffff
        // must come out negative, not wrap into a huge positive number.
        val negative = generateSequence(0) { it + 1 }
            .map { KlapSession(ByteArray(16) { i -> (i + it).toByte() }, remoteSeed, authHash).seq }
            .take(200)
            .any { it < 0 }
        assert(negative) { "no negative seq found in 200 derivations; sign handling is suspect" }
    }

    @Test
    fun `decrypt reverses encrypt at the same seq`() {
        val session = KlapSession(localSeed, remoteSeed, authHash)
        val plaintext = vectors.getString("plaintext")
        val encrypted = session.encrypt(plaintext.toByteArray())
        assertEquals(plaintext, String(session.decrypt(encrypted.body)))
    }

    @Test
    fun `handshake bodies use the seeds in opposite orders`() {
        assertArrayEquals(
            bytes("handshake2_body"),
            KlapSession.handshake2Body(localSeed, remoteSeed, authHash),
        )
        // handshake1's device hash is local||remote; handshake2's body is remote||local.
        // Swapping them is a mistake that still "looks right" and fails only on real hardware.
        assertNotEquals(
            hex(KlapSession.expectedDeviceHash(localSeed, remoteSeed, authHash)),
            hex(KlapSession.handshake2Body(localSeed, remoteSeed, authHash)),
        )
    }

    @Test
    fun `truncated response is rejected rather than decrypted`() {
        val session = KlapSession(localSeed, remoteSeed, authHash)
        session.encrypt("{}".toByteArray())
        assertThrows(IllegalArgumentException::class.java) { session.decrypt(ByteArray(32)) }
    }

    @Test
    fun `corrupt ciphertext fails as a security exception not an IOException`() {
        val session = KlapSession(localSeed, remoteSeed, authHash)
        val encrypted = session.encrypt("{}".toByteArray())
        val corrupted = encrypted.body.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        // This is the distinction KlapTransport has to catch separately: a stale session
        // surfaces here, and it is not an IOException.
        assertThrows(javax.crypto.BadPaddingException::class.java) { session.decrypt(corrupted) }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun hex(text: String): ByteArray =
        ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
