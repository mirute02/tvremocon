package com.tvremocon.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The per-button PendingIntent request code.
 *
 * PendingIntents are matched on request code plus action, data and component; extras are
 * ignored. If two buttons ever shared a request code *and* a data URI, pressing one would
 * send the other's key — across widgets, that means one remote firing another's commands.
 *
 * Calls the production function rather than restating its arithmetic, so a change to the
 * numbering has to survive these assertions instead of only the copy in this file.
 */
class RequestCodeTest {

    private val statusSlot = TvRemoteWidget.SLOT_STATUS

    private fun code(appWidgetId: Int, layout: WidgetLayout, slot: Int): Int =
        TvRemoteWidget.requestCode(appWidgetId, layout, slot)

    @Test
    fun `every button on every grid of several widgets gets its own code`() {
        val codes = mutableListOf<Int>()
        for (widgetId in 1..12) {
            for (layout in WidgetLayout.entries) {
                for (slot in 0 until layout.slotCount) {
                    codes += code(widgetId, layout, slot)
                }
                // The status line shares the numbering and must not land on a real key.
                codes += code(widgetId, layout, statusSlot)
            }
        }
        assertEquals("request codes collide", codes.size, codes.distinct().size)
    }

    @Test
    fun `the status pseudo-slot never collides with the largest grid`() {
        // It sits one past the biggest slot index rather than at a negative offset, which
        // would have run into the previous layout's block.
        val full = WidgetLayout.FULL
        val lastRealKey = code(7, full, full.slotCount - 1)
        assertEquals(true, code(7, full, statusSlot) > lastRealKey)
    }

    @Test
    fun `codes stay clear of the neighbouring widget's block`() {
        val highest = WidgetLayout.entries.maxOf { code(5, it, statusSlot) }
        val lowestOfNext = WidgetLayout.entries.minOf { code(6, it, 0) }
        assertEquals(true, highest < lowestOfNext)
    }
}
