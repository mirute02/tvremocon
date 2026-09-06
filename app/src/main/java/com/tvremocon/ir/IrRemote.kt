package com.tvremocon.ir

/**
 * One IR remote stored on the hub (`category == "ir.remote"`).
 *
 * [deviceId] is the hub's own device id with an 8-character suffix, and is what
 * `control_child` addresses.
 */
data class IrRemote(
    val deviceId: String,
    val nickname: String,
    val model: String,
    val keys: List<IrKey>,
) {
    /**
     * Air conditioners send their whole state at once via `sendIrCmdByStatus` rather than one
     * command per key, so they cannot be driven by this app's per-button model.
     */
    val isAirConditioner: Boolean get() = model == AC_MODEL

    companion object {
        const val CATEGORY = "ir.remote"
        const val AC_MODEL = "AC"
    }
}

/**
 * One key on a remote.
 *
 * [name] is the protocol identifier and the only thing `sendIrCmdById` accepts. [label] is
 * for display and is deliberately derived rather than trusted — see [of].
 */
data class IrKey(
    val name: String,
    val id: Int,
    val label: String,
) {
    /** Keys the user recorded themselves, which are the only ones with a usable display name. */
    val isCustom: Boolean get() = id == CUSTOM_ID

    companion object {
        const val CUSTOM_ID = -1

        /**
         * Builds a key, choosing a label that is actually readable.
         *
         * `display_name` is truncated to four bytes for keys downloaded from TP-Link's
         * database: POWER arrives as "POWE", and all four of NAVIGATE_UP/DOWN/LEFT/RIGHT
         * arrive as "NAVI", so using it would make the arrow keys indistinguishable. Only
         * user-recorded keys (id == -1) carry the full string, and those are the ones whose
         * `name` is an opaque token like "PeF1691". So: decoded display name for custom keys,
         * raw protocol name for everything else.
         */
        fun of(name: String, id: Int, decodedDisplayName: String?): IrKey {
            val trimmed = decodedDisplayName?.trimEnd('\u0000', ' ')?.takeIf { it.isNotBlank() }
            val label = if (id == CUSTOM_ID && trimmed != null) trimmed else name
            return IrKey(name = name, id = id, label = label)
        }
    }
}
