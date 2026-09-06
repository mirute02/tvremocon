package com.tvremocon.widget

import android.content.Context
import android.net.Network
import android.os.SystemClock
import android.util.Log
import com.tvremocon.data.SecretStore
import com.tvremocon.data.Settings
import com.tvremocon.ir.SendResult
import com.tvremocon.ir.TapoIrHub
import com.tvremocon.net.HubDiscovery
import com.tvremocon.net.HubEndpoint
import com.tvremocon.net.LocalNetworkAccess
import com.tvremocon.net.RediscoveryGate
import com.tvremocon.transport.klap.KlapTransport
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns a widget press into one IR send, and keeps the hub's address current.
 *
 * Holds one [TapoIrHub] per host for the life of the process, because the KLAP session and
 * its sequence number belong to that connection: a second client for the same hub would
 * handshake again on every press and the two would interleave.
 */
object RemoteSender {

    private val hubs = ConcurrentHashMap<String, TapoIrHub>()

    suspend fun send(context: Context, appWidgetId: Int, rawKeyName: String): SendResult {
        val app = context.applicationContext

        // Checked before anything is sent, and reported as distinct reasons: a revoked
        // permission must not read as an unreachable hub.
        when (LocalNetworkAccess.blockedReason(app)) {
            LocalNetworkAccess.Blocked.PERMISSION -> return SendResult.NotSent(SendResult.Reason.NO_PERMISSION)
            LocalNetworkAccess.Blocked.NO_WIFI -> return SendResult.NotSent(SendResult.Reason.NO_WIFI)
            null -> Unit
        }

        val settings = Settings(app)
        val host = settings.host
        val remoteDeviceId = settings.remoteDeviceId(appWidgetId)
        val authHash = SecretStore(app).authHash()
        if (host == null || remoteDeviceId == null || authHash == null) {
            return SendResult.NotSent(SendResult.Reason.NOT_CONFIGURED)
        }
        val network = LocalNetworkAccess.wifiNetwork(app) ?: return SendResult.NotSent(SendResult.Reason.NO_WIFI)

        val result = sendVia(host, authHash, network, remoteDeviceId, rawKeyName)
            ?: return SendResult.NotSent(SendResult.Reason.NOT_CONFIGURED)

        // Only this one failure permits a second attempt: HUB_UNREACHABLE means the handshake
        // never completed, so nothing was transmitted and sending once at a newly found
        // address is a first attempt rather than a resend. Every other outcome — including
        // "outcome unknown" — is returned untouched.
        if (result != SendResult.NotSent(SendResult.Reason.HUB_UNREACHABLE)) return result

        val moved = rediscover(app, settings, authHash, network) ?: return result
        Log.i(TAG, "hub moved to $moved; retrying the press there")
        return sendVia(moved, authHash, network, remoteDeviceId, rawKeyName) ?: result
    }

    private suspend fun sendVia(
        host: String,
        authHash: ByteArray,
        network: Network,
        remoteDeviceId: String,
        rawKeyName: String,
    ): SendResult? {
        val hub = hubFor(host, authHash, network) ?: return null
        return hub.sendKey(remoteDeviceId, rawKeyName)
    }

    /**
     * Looks for the configured hub at a new address after DHCP moved it.
     *
     * Candidates are confirmed by `device_id`, not by answering on port 80: something else
     * taking over the old address must not quietly become the thing this app authenticates
     * to and sends commands at. On a match the stored host is updated so later presses go
     * straight there.
     */
    private suspend fun rediscover(
        context: Context,
        settings: Settings,
        authHash: ByteArray,
        network: Network,
    ): String? {
        val expectedId = settings.hubDeviceId ?: return null

        // Rate limited on purpose. Away from home this would otherwise sweep a stranger's
        // network on every press against a hub that is not there.
        val now = SystemClock.elapsedRealtime()
        if (!RediscoveryGate.shouldScan(settings.lastRediscoveryAt, now)) {
            Log.d(TAG, "rediscovery skipped: last sweep ${now - settings.lastRediscoveryAt}ms ago")
            return null
        }
        // Recorded before the sweep, and whether or not it finds anything, so a failing scan
        // cannot turn into a scan on every press.
        settings.lastRediscoveryAt = now

        val candidates = HubDiscovery.scan(network).map { it.host }.filter { it != settings.host }
        for (candidate in candidates) {
            val hub = hubFor(candidate, authHash, network) ?: continue
            val info = try {
                // A read, so retrying it is harmless.
                hub.deviceInfo()
            } catch (e: IOException) {
                hubs.remove(candidate)
                continue
            }
            if (info.deviceId == expectedId) {
                settings.host = candidate
                return candidate
            }
            hubs.remove(candidate)
        }
        return null
    }

    private fun hubFor(host: String, authHash: ByteArray, network: Network): TapoIrHub? {
        val endpoint = HubEndpoint.of(host) ?: return null
        return hubs.computeIfAbsent(host) {
            TapoIrHub(
                KlapTransport(
                    endpoint = endpoint,
                    authHash = authHash,
                    // Pinned to Wi-Fi: without this Android routes over cellular whenever it
                    // decides Wi-Fi has no internet, which is this hub's normal state.
                    client = KlapTransport.httpClient(network.socketFactory),
                )
            )
        }
    }

    /** Drops cached connections after setup changes the host or credentials. */
    fun invalidate() {
        hubs.clear()
    }

    private const val TAG = "TvRemocon"
}
