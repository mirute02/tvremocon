package com.tvremocon.transport.klap

import com.tvremocon.net.HubEndpoint
import com.tvremocon.transport.HubAuthException
import com.tvremocon.transport.HubResponseLostException
import com.tvremocon.transport.HubUnreachableException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Exercises the transport against a hand-rolled HTTP server on loopback.
 *
 * The central test is [ir_send_that_loses_its_response_is_transmitted_exactly_once]: an IR
 * send must reach the wire once and only once, and "the TV only blinked once" is not proof
 * of that — the request could have been delivered twice with one reply lost. Counting
 * receipts at the socket is.
 */
class KlapTransportTest {

    private lateinit var hub: FakeHub

    @Before fun start() { hub = FakeHub().also(FakeHub::start) }
    @After fun stop() { hub.stop() }

    private fun transport(
        lifetimeMs: Long = KlapTransport.DEFAULT_SESSION_LIFETIME_MS,
        clock: () -> Long = { 0L },
    ) = KlapTransport(
        endpoint = HubEndpoint.loopbackForTest(hub.port),
        authHash = AUTH_HASH,
        client = KlapTransport.httpClient(javax.net.SocketFactory.getDefault()),
        sessionLifetimeMs = lifetimeMs,
        random = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(1L) },
        elapsedRealtime = clock,
    )

    @Test
    fun `handshake then request round trips`() = runBlocking {
        val response = transport().call("""{"method":"get_device_info"}""", allowRetry = true)
        assertEquals("""{"error_code":0,"result":{"model":"TH11"}}""", response)
        assertEquals(1, hub.requestCount.get())
    }

    @Test
    fun `wrong credentials raise auth and do not reach the request endpoint`() = runBlocking {
        val wrong = KlapTransport(
            endpoint = HubEndpoint.loopbackForTest(hub.port),
            authHash = ByteArray(32) { 0x7f },
            client = KlapTransport.httpClient(javax.net.SocketFactory.getDefault()),
        )
        assertThrows(HubAuthException::class.java) {
            runBlocking { wrong.call("""{"method":"get_device_info"}""", allowRetry = true) }
        }
        assertEquals(0, hub.requestCount.get())
    }

    @Test
    fun `ir send that loses its response is transmitted exactly once`() = runBlocking {
        hub.dropResponseAfterReceiving = true
        val transport = transport()

        val thrown = assertThrows(HubResponseLostException::class.java) {
            runBlocking { transport.call(IR_SEND, allowRetry = false) }
        }

        // Counted at the socket, not inferred from the hub's behaviour: OkHttp's own
        // retryOnConnectionFailure and any retry loop above would both show up here.
        assertEquals("the IR request reached the hub more than once", 1, hub.requestCount.get())
        assertTrue(thrown.message!!.contains("no response"))
    }

    @Test
    fun `a readable get may be retried after a lost response`() = runBlocking {
        hub.dropResponseAfterReceiving = true
        val transport = transport()
        assertThrows(HubResponseLostException::class.java) {
            runBlocking { transport.call("""{"method":"get_device_info"}""", allowRetry = true) }
        }
        // Even with allowRetry, a lost response is never replayed — the distinction the
        // transport draws is "may already have been delivered", and that applies to gets too.
        assertEquals(1, hub.requestCount.get())
    }

    @Test
    fun `a closed port is unreachable, not a lost response`() = runBlocking {
        // The stale-pooled-connection failure seen in the field looked like this: the write
        // fails within milliseconds because no connection was ever usable. Nothing was
        // transmitted, so calling it "outcome unknown" would be wrong and would block the
        // rediscovery path.
        val closed = ServerSocket(0).let { probe -> probe.localPort.also { probe.close() } }
        val transport = KlapTransport(
            endpoint = HubEndpoint.loopbackForTest(closed),
            authHash = AUTH_HASH,
            client = KlapTransport.httpClient(javax.net.SocketFactory.getDefault()),
        )
        assertThrows(HubUnreachableException::class.java) {
            runBlocking { transport.call(IR_SEND, allowRetry = false) }
        }
        // Nothing reached the real fake hub either; this port was never listening.
        assertEquals(0, hub.requestCount.get())
    }

    @Test
    fun `consecutive presses succeed after the hub drops the idle connection`() = runBlocking {
        // Connection reuse is disabled precisely so this works: the fake hub closes every
        // connection, and the second press must still land rather than failing on a socket
        // that was already dead.
        val transport = transport()
        transport.call("""{"method":"get_device_info"}""", allowRetry = true)
        transport.call("""{"method":"get_device_info"}""", allowRetry = true)
        assertEquals(2, hub.requestCount.get())
        // One handshake, not two: dropping the TCP connection must not drop the KLAP session.
        assertEquals(1, hub.handshakeCount.get())
    }

    @Test
    fun `undecryptable reply is reported as lost rather than failed for an ir send`() = runBlocking {
        hub.corruptResponse = true
        val transport = transport()
        assertThrows(HubResponseLostException::class.java) {
            runBlocking { transport.call(IR_SEND, allowRetry = false) }
        }
        assertEquals(1, hub.requestCount.get())
    }

    @Test
    fun `expired session handshakes again before sending and still sends once`() = runBlocking {
        val now = AtomicLong(0)
        val transport = transport(lifetimeMs = 1_000, clock = now::get)

        transport.call("""{"method":"get_device_info"}""", allowRetry = true)
        assertEquals(1, hub.handshakeCount.get())

        now.set(5_000) // past the lifetime
        transport.call(IR_SEND, allowRetry = false)

        // Re-handshaking before sending is not a retry of the send: two handshakes, two
        // requests, and the IR request itself went out once.
        assertEquals(2, hub.handshakeCount.get())
        assertEquals(2, hub.requestCount.get())
    }

    @Test
    fun `concurrent calls are serialised and sequence numbers stay contiguous`() = runBlocking {
        val transport = transport()
        val calls = (1..8).map { async { transport.call("""{"method":"get_device_info"}""", allowRetry = true) } }
        calls.awaitAll()

        // If two exchanges interleaved, one would decrypt the other's reply at the wrong IV.
        val seqs = hub.seenSequences.sorted()
        assertEquals(8, seqs.size)
        assertEquals(seqs.first() + 7, seqs.last())
        assertEquals(seqs.distinct().size, seqs.size)
    }

    // ------------------------------------------------------------------ fake hub

    private class FakeHub {
        private lateinit var server: ServerSocket
        private val pool = Executors.newCachedThreadPool()
        @Volatile private var running = true

        val port: Int get() = server.localPort
        val requestCount = AtomicInteger()
        val handshakeCount = AtomicInteger()
        val seenSequences: MutableList<Int> = Collections.synchronizedList(mutableListOf())

        @Volatile var dropResponseAfterReceiving = false
        @Volatile var corruptResponse = false

        private var session: KlapSession? = null

        fun start() {
            server = ServerSocket(0)
            pool.submit {
                while (running) {
                    val socket = try { server.accept() } catch (e: IOException) { break }
                    pool.submit { handle(socket) }
                }
            }
        }

        fun stop() { running = false; runCatching { server.close() }; pool.shutdownNow() }

        private fun handle(socket: Socket) = socket.use {
            val input = BufferedInputStream(socket.getInputStream())
            val requestLine = readLine(input) ?: return
            var contentLength = 0
            while (true) {
                val header = readLine(input) ?: return
                if (header.isEmpty()) break
                if (header.startsWith("Content-Length:", true)) {
                    contentLength = header.substringAfter(':').trim().toInt()
                }
            }
            val body = ByteArray(contentLength).also { readFully(input, it) }
            val path = requestLine.split(' ').getOrNull(1).orEmpty()

            when {
                path.startsWith("/app/handshake1") -> {
                    handshakeCount.incrementAndGet()
                    val remoteSeed = ByteArray(16) { (0xA0 + it).toByte() }
                    val hash = KlapSession.expectedDeviceHash(body, remoteSeed, AUTH_HASH)
                    session = KlapSession(body, remoteSeed, AUTH_HASH)
                    respond(socket, remoteSeed + hash, "Set-Cookie: TP_SESSIONID=abc;TIMEOUT=86400")
                }

                path.startsWith("/app/handshake2") -> respond(socket, ByteArray(0))

                path.startsWith("/app/request") -> {
                    requestCount.incrementAndGet()
                    path.substringAfter("seq=", "").toIntOrNull()?.let(seenSequences::add)
                    if (dropResponseAfterReceiving) {
                        // Received in full, then the connection dies — the case where the hub
                        // may well have acted and the app can never know.
                        socket.close()
                        return
                    }
                    val active = session ?: return
                    val reply = """{"error_code":0,"result":{"model":"TH11"}}"""
                    val encrypted = active.encrypt(reply.toByteArray())
                    val payload = if (corruptResponse) {
                        encrypted.body.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
                    } else {
                        encrypted.body
                    }
                    respond(socket, payload)
                }

                else -> respond(socket, ByteArray(0), status = "404 Not Found")
            }
        }

        /**
         * The fake hub encrypts its reply with the same seq the request used. Real hardware
         * does the same; the app relies on it, so the fake must not paper over it.
         */
        private fun respond(socket: Socket, body: ByteArray, extraHeader: String? = null, status: String = "200 OK") {
            val head = buildString {
                append("HTTP/1.1 $status\r\n")
                append("Content-Length: ${body.size}\r\n")
                extraHeader?.let { append("$it\r\n") }
                append("Connection: close\r\n\r\n")
            }
            socket.getOutputStream().apply { write(head.toByteArray()); write(body); flush() }
        }

        private fun readLine(input: BufferedInputStream): String? {
            val out = StringBuilder()
            while (true) {
                val c = input.read()
                if (c == -1) return if (out.isEmpty()) null else out.toString()
                if (c == '\n'.code) return out.removeSuffix("\r").toString()
                out.append(c.toChar())
            }
        }

        private fun readFully(input: BufferedInputStream, buffer: ByteArray) {
            var read = 0
            while (read < buffer.size) {
                val n = input.read(buffer, read, buffer.size - read)
                if (n == -1) break
                read += n
            }
        }

        private fun StringBuilder.removeSuffix(suffix: String): StringBuilder =
            if (endsWith(suffix)) also { setLength(length - suffix.length) } else this
    }

    private companion object {
        val AUTH_HASH: ByteArray = KlapSession.authHash("user@example.com", "pw")
        const val IR_SEND =
            """{"method":"control_child","params":{"device_id":"D1","requestData":{"method":"sendIrCmdById","params":{"name":"INFO"}}}}"""
    }
}
