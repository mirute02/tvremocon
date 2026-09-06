package com.tvremocon.widget

import android.content.Context
import com.tvremocon.data.SecretStore
import com.tvremocon.data.Settings
import com.tvremocon.ir.SendResult
import com.tvremocon.ir.TapoIrHub
import com.tvremocon.net.HubEndpoint
import com.tvremocon.net.LocalNetworkAccess
import com.tvremocon.transport.klap.KlapTransport
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns a widget press into one IR send.
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
        val endpoint = HubEndpoint.of(host) ?: return SendResult.NotSent(SendResult.Reason.NOT_CONFIGURED)
        val network = LocalNetworkAccess.wifiNetwork(app) ?: return SendResult.NotSent(SendResult.Reason.NO_WIFI)

        val hub = hubs.computeIfAbsent(host) {
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
        return hub.sendKey(remoteDeviceId, rawKeyName)
    }

    /** Drops cached connections after setup changes the host or credentials. */
    fun invalidate() {
        hubs.clear()
    }
}
