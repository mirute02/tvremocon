package com.tvremocon.net

/**
 * How often the app is willing to sweep the local network looking for a moved hub.
 *
 * A sweep is not free and it is not private: it opens a TCP connection to all 254 addresses
 * on the subnet and posts a handshake probe to everything answering on port 80. That is
 * unremarkable on the network the hub lives on, and rude on any other — a phone carried into
 * an office or a café would otherwise port-scan it every time a button was pressed against a
 * hub that is not there.
 *
 * Pure, so the interval is testable without a device or a clock.
 */
object RediscoveryGate {

    /**
     * Ten minutes. DHCP moves a hub rarely — a lease change or a router restart — so waiting
     * costs the user one press that reports the hub as unreachable, and the alternative costs
     * a scan of somebody else's network on every press.
     */
    const val INTERVAL_MS = 10 * 60 * 1000L

    /**
     * @param lastScanAt monotonic time of the last sweep, or 0 if there has not been one
     * @param now monotonic now; both come from elapsedRealtime, never the wall clock, so
     *   changing the device's time cannot unlock a scan
     */
    fun shouldScan(lastScanAt: Long, now: Long, interval: Long = INTERVAL_MS): Boolean {
        if (lastScanAt <= 0L) return true
        // A clock that appears to have gone backwards means the device rebooted and
        // elapsedRealtime restarted, so the stored time is meaningless rather than recent.
        if (now < lastScanAt) return true
        return now - lastScanAt >= interval
    }
}
