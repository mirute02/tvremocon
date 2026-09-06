package com.tvremocon.transport

import java.io.IOException

/**
 * One request/response exchange with the hub, with the encryption scheme left open.
 *
 * KLAP v2 is what the hub speaks today. TP-Link moved some P110 firmware to TPAP, and if
 * that ever reaches this hub the replacement goes here and nothing above this line changes.
 */
interface HubTransport {

    /**
     * Sends one SMART JSON request and returns the decrypted JSON response.
     *
     * [allowRetry] means only "it is safe to replay this request if it may already have been
     * delivered". It does **not** govern establishing or refreshing a session: noticing an
     * expired session *before* sending and handshaking first is not a retry, and stays
     * allowed for IR sends.
     *
     * Pass false for anything non-idempotent — an IR send that goes out twice turns the
     * volume up two steps.
     */
    @Throws(IOException::class)
    suspend fun call(json: String, allowRetry: Boolean): String

    /** Drops any cached session so the next [call] handshakes again. */
    fun invalidate()
}

/** The credentials are wrong. Retrying cannot help. */
class HubAuthException(message: String) : IOException(message)

/**
 * The request went out but no usable response came back.
 *
 * Distinct from a plain [IOException] because it means the hub may well have acted on it —
 * the caller must report "outcome unknown", never "failed".
 */
class HubResponseLostException(message: String, cause: Throwable? = null) : IOException(message, cause)
