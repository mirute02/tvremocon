package com.tvremocon.net

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Everything that has to be true before a socket to the hub can succeed.
 *
 * Two independent gates, both required, and each reported separately so the UI never
 * blames a revoked permission on the hub being unreachable:
 *
 *  - [hasPermission]: Android 17 (targetSdk 37) blocks local-network traffic until
 *    ACCESS_LOCAL_NETWORK is granted at runtime. Older releases have no such gate.
 *  - [wifiNetwork]: Android routes sockets over cellular when it decides Wi-Fi has no
 *    internet — exactly the situation this app runs in. Requests must be pinned to the
 *    Wi-Fi [Network] explicitly, or LAN addresses become unreachable at random.
 */
object LocalNetworkAccess {

    /** Runtime permission name, or null on releases that do not gate local-network access. */
    val permission: String? =
        if (Build.VERSION.SDK_INT >= 37) Manifest.permission.ACCESS_LOCAL_NETWORK else null

    fun hasPermission(context: Context): Boolean {
        val name = permission ?: return true
        return ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * The currently connected Wi-Fi network, or null when Wi-Fi is off or not associated.
     *
     * Deliberately does not require NET_CAPABILITY_INTERNET or VALIDATED: a hub on a
     * router with no upstream is still perfectly reachable, and that case is the whole
     * point of talking to it over the LAN.
     */
    fun wifiNetwork(context: Context): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        // Check the active network first — the common case, and the one the user expects.
        cm.activeNetwork?.let { if (isWifi(cm, it)) return it }
        return cm.allNetworks.firstOrNull { isWifi(cm, it) }
    }

    private fun isWifi(cm: ConnectivityManager, network: Network): Boolean =
        cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

    /** Why a request cannot even be attempted. Null means nothing is blocking it. */
    fun blockedReason(context: Context): Blocked? = when {
        !hasPermission(context) -> Blocked.PERMISSION
        wifiNetwork(context) == null -> Blocked.NO_WIFI
        else -> null
    }

    enum class Blocked { PERMISSION, NO_WIFI }
}
