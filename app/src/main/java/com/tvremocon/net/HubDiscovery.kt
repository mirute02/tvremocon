package com.tvremocon.net

import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/**
 * Finds Tapo hubs on the local subnet.
 *
 * mDNS would be lighter, but receiving multicast on Android needs a lock and is unreliable
 * on Wi-Fi that has power saving on. A /24 sweep of port 80 followed by a handshake1 probe
 * is dependency-free and finished in about two seconds in practice.
 *
 * A 48-byte reply identifies a KLAP device, not specifically a hub and not specifically
 * version 2 — KLAP v1 answers with the same shape. Confirming the model needs credentials,
 * so that happens later.
 */
object HubDiscovery {

    data class Candidate(val host: String)

    suspend fun scan(network: Network): List<Candidate> = withContext(Dispatchers.IO) {
        val prefix = subnetPrefix(network) ?: return@withContext emptyList()
        coroutineScope {
            (1..254)
                .map { last -> async { probe(network, prefix + last) } }
                .awaitAll()
                .filterNotNull()
        }
    }

    private fun probe(network: Network, host: String): Candidate? {
        // The socket has to be bound to the same network the requests will use, or the scan
        // can succeed over an interface the app will not actually send on.
        val socket = network.socketFactory.createSocket() ?: return null
        try {
            socket.connect(InetSocketAddress(host, HubEndpoint.HUB_PORT), CONNECT_TIMEOUT_MS)
        } catch (e: IOException) {
            return null
        } finally {
            runCatching { socket.close() }
        }
        return if (respondsToHandshake(network, host)) Candidate(host) else null
    }

    private fun respondsToHandshake(network: Network, host: String): Boolean {
        val endpoint = HubEndpoint.of(host) ?: return false
        val client = com.tvremocon.transport.klap.KlapTransport.httpClient(network.socketFactory)
        val seed = ByteArray(SEED_SIZE).also { SecureRandom().nextBytes(it) }
        val request = okhttp3.Request.Builder()
            .url(endpoint.url(HubEndpoint.Path.HANDSHAKE1))
            .post(okhttp3.RequestBody.create(null, seed))
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                response.code == 200 &&
                    response.body?.contentLength()?.toInt() == HANDSHAKE1_SIZE &&
                    response.headers("Set-Cookie").any { it.startsWith("TP_SESSIONID=") }
            }
        } catch (e: IOException) {
            false
        }
    }

    /** The leading three octets of this device's own address on [network]. */
    private fun subnetPrefix(network: Network): String? {
        val address = network.let {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList()
                ?.flatMap { iface -> iface.inetAddresses.toList() }
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { !it.isLoopbackAddress && HubEndpoint.isPrivateIpv4(it.hostAddress.orEmpty()) }
        } ?: return null
        val text = address.hostAddress ?: return null
        return text.substringBeforeLast('.') + "."
    }

    private const val CONNECT_TIMEOUT_MS = 400
    private const val SEED_SIZE = 16
    private const val HANDSHAKE1_SIZE = 48
}
