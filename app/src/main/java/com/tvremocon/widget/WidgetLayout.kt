package com.tvremocon.widget

import com.tvremocon.widget.RemoteFunction as F

/**
 * How a button is drawn. RemoteViews cannot style a view at runtime beyond swapping a
 * background resource, so each style is a drawable prepared in advance.
 */
enum class ButtonStyle(val background: String, val textColor: String, val textSize: Int) {
    /** Dark rounded rectangle, the default for labelled keys. */
    NORMAL("widget_button", "widget_button_text", 11),

    /** Red, round, top right — the one button people find without looking. */
    POWER("widget_button_power", "widget_power_text", 11),

    /** Larger and lighter, like the oversized channel digits on the real remote. */
    NUMBER("widget_button_number", "widget_button_text", 17),

    /** Part of the circular pad around 決定. */
    DPAD("widget_button_dpad", "widget_button_text", 15),

    OK("widget_button_ok", "widget_button_text", 13),

    COLOR_BLUE("widget_button_blue", "widget_color_text", 10),
    COLOR_RED("widget_button_red", "widget_color_text", 10),
    COLOR_GREEN("widget_button_green", "widget_color_text", 10),
    COLOR_YELLOW("widget_button_yellow", "widget_color_text", 10),
}

/** One cell: what it does by default, how wide it is, and how it is drawn. */
data class Cell(
    val function: F?,
    val span: Int,
    val style: ButtonStyle = ButtonStyle.NORMAL,
)

/**
 * The button grids, laid out like the physical remote they replace.
 *
 * A twelve-column grid because that is the smallest number divisible by both 3 and 4: the
 * number pad and most rows are three across, the coloured keys are four across, and the top
 * row is four across. Every cell spans 3 or 4 columns.
 *
 * The two layouts have independent slot numbering — the compact grid is not a prefix of the
 * full one, since the buttons worth keeping when space is short are scattered across the
 * full remote rather than gathered at the top. Assignments are therefore stored per layout.
 */
enum class WidgetLayout(val id: String, val cells: List<Cell>) {

    /** Cover screen and small placements: power, volume, channel, and the d-pad. */
    COMPACT(
        id = "compact",
        cells = listOf(
            Cell(F.INPUT, 4), Cell(F.MUTE, 4), Cell(F.POWER, 4, ButtonStyle.POWER),
            Cell(F.VOLUME_UP, 4), Cell(F.UP, 4, ButtonStyle.DPAD), Cell(F.CHANNEL_UP, 4),
            Cell(F.LEFT, 4, ButtonStyle.DPAD), Cell(F.OK, 4, ButtonStyle.OK), Cell(F.RIGHT, 4, ButtonStyle.DPAD),
            Cell(F.VOLUME_DOWN, 4), Cell(F.DOWN, 4, ButtonStyle.DPAD), Cell(F.CHANNEL_DOWN, 4),
            Cell(F.BACK, 4), Cell(F.HOME, 4), Cell(F.GUIDE, 4),
        ),
    ),

    /**
     * The whole remote, following the order on the hardware: utility row, source selection,
     * the 1-12 pad, volume and channel flanking the guide keys, the coloured row, then the
     * d-pad with its surrounding keys.
     */
    FULL(
        id = "full",
        cells = listOf(
            Cell(F.MUTE, 3), Cell(F.SCREEN_INFO, 3), Cell(F.INPUT, 3), Cell(F.POWER, 3, ButtonStyle.POWER),

            Cell(F.TERRESTRIAL, 4), Cell(F.SATELLITE_BS, 4), Cell(F.SATELLITE_CS, 4),

            Cell(F.DIGIT_1, 4, ButtonStyle.NUMBER), Cell(F.DIGIT_2, 4, ButtonStyle.NUMBER), Cell(F.DIGIT_3, 4, ButtonStyle.NUMBER),
            Cell(F.DIGIT_4, 4, ButtonStyle.NUMBER), Cell(F.DIGIT_5, 4, ButtonStyle.NUMBER), Cell(F.DIGIT_6, 4, ButtonStyle.NUMBER),
            Cell(F.DIGIT_7, 4, ButtonStyle.NUMBER), Cell(F.DIGIT_8, 4, ButtonStyle.NUMBER), Cell(F.DIGIT_9, 4, ButtonStyle.NUMBER),
            Cell(F.DIGIT_10, 4, ButtonStyle.NUMBER), Cell(F.DIGIT_11, 4, ButtonStyle.NUMBER), Cell(F.DIGIT_12, 4, ButtonStyle.NUMBER),

            Cell(F.VOLUME_UP, 4), Cell(F.GUIDE, 4), Cell(F.CHANNEL_UP, 4),
            Cell(F.VOLUME_DOWN, 4), Cell(F.PROGRAM_INFO, 4), Cell(F.CHANNEL_DOWN, 4),

            Cell(F.COLOR_BLUE, 3, ButtonStyle.COLOR_BLUE), Cell(F.COLOR_RED, 3, ButtonStyle.COLOR_RED),
            Cell(F.COLOR_GREEN, 3, ButtonStyle.COLOR_GREEN), Cell(F.COLOR_YELLOW, 3, ButtonStyle.COLOR_YELLOW),

            Cell(F.SUBMENU, 4), Cell(F.UP, 4, ButtonStyle.DPAD), Cell(F.BACK, 4),
            Cell(F.LEFT, 4, ButtonStyle.DPAD), Cell(F.OK, 4, ButtonStyle.OK), Cell(F.RIGHT, 4, ButtonStyle.DPAD),
            Cell(F.HOME, 4), Cell(F.DOWN, 4, ButtonStyle.DPAD), Cell(F.EXIT, 4),

            Cell(F.SUBTITLE, 4), Cell(F.DATA, 4), Cell(F.SETTINGS, 4),
        ),
    );

    val slotCount: Int get() = cells.size
    val defaults: List<F?> get() = cells.map(Cell::function)

    /** View id prefix; the two grids have separate id spaces so neither constrains the other. */
    fun viewIdName(slot: Int): String = "%s_slot_%02d".format(id, slot)

    companion object {
        /** Divisible by 3 and 4, so both the number pad and the coloured row fit exactly. */
        const val COLUMNS = 12

        /** Sizes the per-slot PendingIntent request codes. */
        val MAX_SLOTS: Int = entries.maxOf { it.slotCount }

        init {
            // Checked here rather than in each constant's init, where the companion — and so
            // COLUMNS — is not yet initialised. Rows must come out even or GridLayout
            // silently reflows and the remote loses its shape.
            entries.forEach { layout ->
                val spans = layout.cells.sumOf(Cell::span)
                require(spans % COLUMNS == 0) { "${layout.name} spans $spans, not a multiple of $COLUMNS" }
            }
        }
    }
}
