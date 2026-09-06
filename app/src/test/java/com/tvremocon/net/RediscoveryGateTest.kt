package com.tvremocon.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The interval between automatic sweeps of the local network.
 *
 * The sweep touches every address on the subnet, so getting this wrong is not a performance
 * bug — it is the difference between a phone that behaves itself on someone else's Wi-Fi and
 * one that port-scans it on every button press.
 */
class RediscoveryGateTest {

    private val interval = 1_000L

    @Test
    fun `the first sweep is allowed`() {
        assertTrue(RediscoveryGate.shouldScan(lastScanAt = 0L, now = 0L, interval = interval))
        assertTrue(RediscoveryGate.shouldScan(lastScanAt = 0L, now = 500L, interval = interval))
    }

    @Test
    fun `a sweep within the interval is refused`() {
        assertFalse(RediscoveryGate.shouldScan(lastScanAt = 100L, now = 200L, interval = interval))
        // Just short of the interval is still short of it.
        assertFalse(RediscoveryGate.shouldScan(lastScanAt = 100L, now = 1_099L, interval = interval))
    }

    @Test
    fun `a sweep at or past the interval is allowed`() {
        assertTrue(RediscoveryGate.shouldScan(lastScanAt = 100L, now = 1_100L, interval = interval))
        assertTrue(RediscoveryGate.shouldScan(lastScanAt = 100L, now = 9_000L, interval = interval))
    }

    @Test
    fun `a clock that went backwards means the device rebooted, not that a sweep is recent`() {
        // elapsedRealtime restarts at zero on reboot, so a stored value ahead of now is
        // stale rather than fresh. Refusing here would lock rediscovery out until the phone
        // had been up longer than it was before.
        assertTrue(RediscoveryGate.shouldScan(lastScanAt = 5_000L, now = 10L, interval = interval))
    }

    @Test
    fun `the shipped interval is minutes, not seconds`() {
        // A guard against someone shrinking this to make testing easier and leaving it.
        assertTrue(RediscoveryGate.INTERVAL_MS >= 5 * 60 * 1000L)
    }
}
