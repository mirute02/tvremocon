package com.tvremocon.data

import android.security.keystore.KeyPermanentlyInvalidatedException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException

/**
 * Which failures justify throwing the stored credentials away.
 *
 * Getting this wrong in the permissive direction costs the user their setup over a transient
 * Keystore hiccup, which is what the previous `catch (e: Exception) { clear() }` did.
 */
class SecretStoreTest {

    @Test
    fun `a key that can no longer decrypt is discarded`() {
        // The GCM tag failing means the wrong key or an altered file; either way this
        // ciphertext will not come back.
        assertTrue(SecretStore.shouldDiscard(AEADBadTagException("tag mismatch")))
        assertTrue(SecretStore.shouldDiscard(KeyPermanentlyInvalidatedException()))
        assertTrue(SecretStore.shouldDiscard(UnrecoverableKeyException("gone")))
        assertTrue(SecretStore.shouldDiscard(InvalidKeyException("no such key")))
    }

    @Test
    fun `a momentary failure keeps the credentials`() {
        // The Keystore can be busy during boot and a read can fail; neither says the secret
        // is lost, and re-running setup over one would be a poor trade.
        assertFalse(SecretStore.shouldDiscard(IOException("read failed")))
        assertFalse(SecretStore.shouldDiscard(IllegalStateException("keystore busy")))
        assertFalse(SecretStore.shouldDiscard(GeneralSecurityException("unspecified")))
        assertFalse(SecretStore.shouldDiscard(RuntimeException("boom")))
    }

    @Test
    fun `a padding failure that is not a tag mismatch is not enough`() {
        // AEADBadTagException extends BadPaddingException, not the other way round. Matching
        // the parent would discard on failures that say nothing about the key.
        assertFalse(SecretStore.shouldDiscard(BadPaddingException("padding")))
    }
}
