package com.tvremocon.widget

import com.tvremocon.widget.RemoteFunction as F

/**
 * The button grid, at two heights.
 *
 * Three columns, like a real remote: the d-pad needs a centre column with one either side,
 * and Japanese channel keys are 1-12 in a 3x4 block. Four columns put both off-centre.
 *
 * [FULL] is [COMPACT] plus more rows, and slot indices are shared — slot 7 is the OK button
 * in both. So resizing the widget reveals the number pad without moving a single button the
 * user had already learned, and one saved assignment map serves both.
 *
 * Slot indices are positional and permanent: reordering [SLOTS] would silently move every
 * user's buttons.
 */
enum class WidgetLayout(val rows: Int) {

    /** Everything needed to watch TV. Fits a cover screen. */
    COMPACT(rows = 5),

    /** Adds source selection and the number pad. About one home-screen page. */
    FULL(rows = 10);

    val columns: Int get() = COLUMNS
    val slotCount: Int get() = COLUMNS * rows
    val defaults: List<F?> get() = SLOTS.take(slotCount)

    companion object {
        const val COLUMNS = 3

        /**
         * Row by row. The first five rows are the compact grid; everything after is what
         * appears when the widget is made taller.
         */
        private val SLOTS: List<F?> = listOf(
            F.POWER, F.INPUT, F.MUTE,
            F.CHANNEL_UP, F.UP, F.VOLUME_UP,
            F.LEFT, F.OK, F.RIGHT,
            F.CHANNEL_DOWN, F.DOWN, F.VOLUME_DOWN,
            F.BACK, F.HOME, F.MENU,
            // --- compact ends here ---
            F.TERRESTRIAL, F.SATELLITE_BS, F.SATELLITE_CS,
            F.DIGIT_1, F.DIGIT_2, F.DIGIT_3,
            F.DIGIT_4, F.DIGIT_5, F.DIGIT_6,
            F.DIGIT_7, F.DIGIT_8, F.DIGIT_9,
            F.DIGIT_10, F.DIGIT_11, F.DIGIT_12,
        )

        /** Sizes the per-slot PendingIntent request codes. */
        val MAX_SLOTS: Int = entries.maxOf { it.slotCount }

        init {
            require(SLOTS.size == FULL.slotCount) { "${SLOTS.size} slots defined for ${FULL.slotCount} cells" }
        }
    }
}
