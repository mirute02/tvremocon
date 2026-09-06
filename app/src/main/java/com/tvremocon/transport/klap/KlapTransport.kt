package com.tvremocon.transport.klap

import android.os.SystemClock
import android.util.Log
import com.tvremocon.net.HubEndpoint
import com.tvremocon.transport.HubAuthException
import com.tvremocon.transport.HubResponseLostException
import com.tvremocon.transport.HubTransport
import com.tvremocon.transport.HubUnreachableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * KLAP v2 over HTTP, one hub per instance.
 *
 * Retries are suppressed at every layer for IR sends: OkHttp is built with
 * `retryOnConnectionFailure(false)`, this class only replays when the caller passes
 * `allowRetry = true`, and there is no retry loop above it. A response that never arrives
 * surfaces as [HubResponseLostException] rather than a failure, because the hub may already
 * have transmitted.
 */
class KlapTransport(
    private val endpoint: HubEndpoint,
    private val authHash: ByteArray,
    private val client: OkHttpClient,
    private val sessionLifetimeMs: Long = DEFAULT_SESSION_LIFETIME_MS,
    private val random: SecureRandom = SecureRandom(),
    /** Monotonic clock; injectable so expiry can be tested without waiting 20 hours. */
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) : HubTransport {

    // Guards the session, its sequence number, and the exchange itself. seq advances on
    // encrypt and is reused by decrypt, so two concurrent calls would decrypt each other's
    // replies with the wrong IV.
    private val mutex = Mutex()

    private var session: KlapSession? = null
    private var sessionCookie: String? = null
    private var sessionStartedAt: Long = 0

    override fun invalidate() {
        session = null
        sessionCookie = null
    }

    override suspend fun call(json: String, allowRetry: Boolean): String =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    exchange(json)
                } catch (e: HubAuthException) {
                    throw e
                } catch (e: HubResponseLostException) {
                    // The hub may have acted on it. Replaying is exactly what must not happen.
                    invalidate()
                    throw e
                } catch (e: SecurityException) {
                    // The reply did not come from something holding the signing key. Never
                    // retried, even for a read: the request itself did go out, so the outcome
                    // is unknown rather than failed, and repeating it under someone who is
                    // evidently interfering would only give them a second attempt.
                    // SecurityException is a RuntimeException, so the GeneralSecurityException
                    // branch below does not cover it.
                    invalidate()
                    throw HubResponseLostException("response signature did not verify", e)
                } catch (e: IOException) {
                    invalidate()
                    if (!allowRetry) throw e
                    exchange(json)
                } catch (e: GeneralSecurityException) {
                    // A stale session shows up as a padding failure, which is not an
                    // IOException — the branch that is easy to leave out.
                    invalidate()
                    if (!allowRetry) throw HubResponseLostException("response did not decrypt", e)
                    exchange(json)
                } catch (e: IllegalArgumentException) {
                    invalidate()
                    if (!allowRetry) throw HubResponseLostException("response was truncated", e)
                    exchange(json)
                }
            }
        }

    /** One attempt. Establishing a session first is not a retry. */
    private fun exchange(json: String): String {
        val cached = session?.takeIf { isFresh() }
        val active = cached ?: handshake()
        val sentAt = elapsedRealtime()
        val encrypted = active.encrypt(json.toByteArray(Charsets.UTF_8))

        val response = try {
            execute(
                Request.Builder()
                    .url(endpoint.requestUrl(encrypted.seq))
                    .header("Cookie", sessionCookie ?: throw IOException("no session cookie"))
                    .post(encrypted.body.toRequestBody(OCTET_STREAM))
                    .build()
            )
        } catch (e: ConnectException) {
            // No TCP connection was ever established, so nothing was transmitted. Saying
            // "outcome unknown" here would be needlessly pessimistic and would block the
            // rediscovery path that handles a moved hub.
            throw HubUnreachableException("could not connect for seq ${encrypted.seq}", e)
        } catch (e: NoRouteToHostException) {
            throw HubUnreachableException("no route for seq ${encrypted.seq}", e)
        } catch (e: PortUnreachableException) {
            throw HubUnreachableException("port unreachable for seq ${encrypted.seq}", e)
        } catch (e: IOException) {
            // Anything else — a read timeout in particular — happened at or after the write.
            // The bytes may have left this device; whether the hub acted on them is unknowable.
            throw HubResponseLostException("no response after sending seq ${encrypted.seq}", e)
        }

        if (response.code == 403) {
            invalidate()
            throw IOException("hub rejected the session (HTTP 403)")
        }
        if (response.code != 200) {
            throw IOException("hub returned HTTP ${response.code} for seq ${encrypted.seq}")
        }
        // seq is logged so a duplicate request is visible as two sequence numbers for one tap.
        Log.d(TAG, "request seq=${encrypted.seq} handshaked=${cached == null} " +
            "${elapsedRealtime() - sentAt}ms")
        return String(active.decrypt(response.body), Charsets.UTF_8)
    }

    private fun isFresh(): Boolean = elapsedRealtime() - sessionStartedAt < sessionLifetimeMs

    private fun handshake(): KlapSession {
        val startedAt = elapsedRealtime()
        val localSeed = ByteArray(KlapSession.SEED_SIZE).also(random::nextBytes)

        // A transport failure here happened before any application request was built, so the
        // caller is free to look for the hub elsewhere and try again without that counting
        // as a resend.
        val first = try {
            execute(
                Request.Builder()
                    .url(endpoint.url(HubEndpoint.Path.HANDSHAKE1))
                    .post(localSeed.toRequestBody(null))
                    .build()
            )
        } catch (e: IOException) {
            throw HubUnreachableException("no handshake response from $endpoint", e)
        }
        if (first.code != 200) throw IOException("handshake1 returned HTTP ${first.code}")
        if (first.body.size != HANDSHAKE1_SIZE) {
            throw IOException("handshake1 returned ${first.body.size} bytes, expected $HANDSHAKE1_SIZE")
        }
        val remoteSeed = first.body.copyOfRange(0, KlapSession.SEED_SIZE)
        val deviceHash = first.body.copyOfRange(KlapSession.SEED_SIZE, HANDSHAKE1_SIZE)
        if (!MessageDigest.isEqual(KlapSession.expectedDeviceHash(localSeed, remoteSeed, authHash), deviceHash)) {
            throw HubAuthException("device hash mismatch: the account does not match this hub")
        }

        // Keep TP_SESSIONID only. TIMEOUT also comes back, but the hub does not want it echoed.
        val cookie = first.setCookies.firstNotNullOfOrNull { header ->
            header.split(';').map(String::trim).firstOrNull { it.startsWith(SESSION_COOKIE_PREFIX) }
        } ?: throw IOException("handshake1 did not set $SESSION_COOKIE_PREFIX")

        val second = execute(
            Request.Builder()
                .url(endpoint.url(HubEndpoint.Path.HANDSHAKE2))
                .header("Cookie", cookie)
                .post(KlapSession.handshake2Body(localSeed, remoteSeed, authHash).toRequestBody(null))
                .build()
        )
        if (second.code != 200) throw IOException("handshake2 returned HTTP ${second.code}")

        return KlapSession(localSeed, remoteSeed, authHash).also {
            session = it
            sessionCookie = cookie
            sessionStartedAt = elapsedRealtime()
            // Two extra round trips. If this shows up on most presses the process is being
            // killed between them, which is the difference between a snappy and a sluggish
            // button.
            Log.d(TAG, "handshake took ${sessionStartedAt - startedAt}ms")
        }
    }

    private fun execute(request: Request): Http {
        val call: Call = client.newCall(request)
        return call.execute().use { response ->
            Http(
                code = response.code,
                body = response.body?.bytes() ?: ByteArray(0),
                setCookies = response.headers("Set-Cookie"),
            )
        }
    }

    private class Http(val code: Int, val body: ByteArray, val setCookies: List<String>)

    companion object {
        private const val TAG = "TvRemocon"
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
        private const val SESSION_COOKIE_PREFIX = "TP_SESSIONID="
        private const val HANDSHAKE1_SIZE = 48

        /** The hub's own TIMEOUT cookie says 24h; re-handshake well before that. */
        const val DEFAULT_SESSION_LIFETIME_MS = 20L * 60 * 60 * 1000

        /**
         * The HTTP client this transport requires. `retryOnConnectionFailure(false)` is the
         * point: OkHttp would otherwise replay a request it believes was not delivered, and
         * that belief is not something an IR send can rely on.
         */
        fun httpClient(socketFactory: javax.net.SocketFactory): OkHttpClient =
            OkHttpClient.Builder()
                .socketFactory(socketFactory)
                // No connection reuse. The hub drops idle sockets after a few seconds, and a
                // pooled one that has already been closed fails the write immediately — seen
                // in the field as a press that did nothing 11ms after the tap. OkHttp would
                // normally paper over that by reconnecting, but retryOnConnectionFailure has
                // to stay off: it cannot tell a socket that was never usable from one that
                // died after the request went out, and guessing wrong resends an IR command.
                // Opening a fresh connection each time costs a TCP handshake on the LAN and
                // removes the whole failure mode.
                .connectionPool(ConnectionPool(0, 1, java.util.concurrent.TimeUnit.NANOSECONDS))
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(java.time.Duration.ofSeconds(2))
                .readTimeout(java.time.Duration.ofSeconds(4))
                .writeTimeout(java.time.Duration.ofSeconds(4))
                .build()
    }
}
