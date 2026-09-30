package com.tvremocon.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The armed deadline is stored on the monotonic clock, which restarts at every reboot. A
 * deadline carried over from a long uptime must not keep the widget live — and sending on
 * the first tap — until the new uptime catches up.
 */
class ArmedDeadlineTest {

    @Test
    fun `a deadline within the window is kept`() {
        assertEquals(12_000L, TvRemoteWidget.effectiveDeadline(stored = 12_000L, now = 10_000L))
        assertEquals(15_000L, TvRemoteWidget.effectiveDeadline(stored = 15_000L, now = 10_000L))
    }

    @Test
    fun `a deadline already past is kept, and reads as resting`() {
        assertEquals(4_000L, TvRemoteWidget.effectiveDeadline(stored = 4_000L, now = 10_000L))
    }

    @Test
    fun `a deadline from before a reboot is dropped`() {
        // Written three days into the previous boot; the phone has been up for a minute.
        val stored = 3 * 24 * 3_600_000L
        assertEquals(0L, TvRemoteWidget.effectiveDeadline(stored = stored, now = 60_000L))
        assertEquals(0L, TvRemoteWidget.effectiveDeadline(stored = 15_001L, now = 10_000L))
    }
}
