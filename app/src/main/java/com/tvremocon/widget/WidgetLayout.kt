package com.tvremocon.widget

import com.tvremocon.widget.RemoteFunction as F

/**
 * The two button grids, and where each one starts out.
 *
 * Which grid a widget shows is decided by the space the launcher gives it, not by a setting:
 * resize the widget and the number pad appears. That also means a Fold's cover screen and
 * inner screen each get the grid that fits, with no per-screen configuration.
 *
 * Slot indices are positional and stable — a saved assignment refers to slot 7, and slot 7
 * is the same cell forever. Reordering these lists would silently move users' buttons.
 */
enum class WidgetLayout(val columns: Int, val rows: Int, val defaults: List<F?>) {

    /** Everything needed to watch TV, nothing else. Fits a cover screen. */
    COMPACT(
        columns = 3,
        rows = 5,
        defaults = listOf(
            F.POWER, F.INPUT, F.MUTE,
            F.CHANNEL_UP, F.UP, F.VOLUME_UP,
            F.LEFT, F.OK, F.RIGHT,
            F.CHANNEL_DOWN, F.DOWN, F.VOLUME_DOWN,
            F.BACK, F.HOME, F.MENU,
        ),
    ),

    /** Adds the number pad and inputs. Channel and volume stay in the same columns. */
    FULL(
        columns = 4,
        rows = 8,
        defaults = listOf(
            F.POWER, F.INPUT, F.MUTE, F.HOME,
            F.CHANNEL_UP, F.UP, F.VOLUME_UP, F.MENU,
            F.LEFT, F.OK, F.RIGHT, F.BACK,
            F.CHANNEL_DOWN, F.DOWN, F.VOLUME_DOWN, F.INFO,
            F.DIGIT_1, F.DIGIT_2, F.DIGIT_3, F.DIGIT_4,
            F.DIGIT_5, F.DIGIT_6, F.DIGIT_7, F.DIGIT_8,
            F.DIGIT_9, F.DIGIT_10, F.DIGIT_11, F.DIGIT_12,
            F.HDMI_1, F.HDMI_2, F.HDMI_3, F.GUIDE,
        ),
    );

    val slotCount: Int get() = columns * rows

    init {
        require(defaults.size == slotCount) { "$name: ${defaults.size} defaults for $slotCount slots" }
    }

    companion object {
        /** The largest slot index any layout has, for sizing the PendingIntent request codes. */
        val MAX_SLOTS: Int = entries.maxOf { it.slotCount }
    }
}
