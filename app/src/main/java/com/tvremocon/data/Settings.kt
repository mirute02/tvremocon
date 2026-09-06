package com.tvremocon.data

import android.content.Context
import com.tvremocon.ir.IrRemote
import com.tvremocon.widget.RemoteFunction
import com.tvremocon.widget.WidgetLayout
import org.json.JSONObject

/**
 * Everything the widget needs to send a key without asking the hub anything first.
 *
 * Once setup is done a button press is one request: no device list, no lookup. That is what
 * keeps a tap responsive, and it is why the raw key names are stored rather than looked up
 * by function at press time.
 *
 * Slot assignments are keyed by appWidgetId, so two widgets on the same home screen can show
 * different buttons for different remotes.
 */
class Settings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** Hub address, re-resolved by discovery when DHCP moves it. */
    var host: String?
        get() = prefs.getString(KEY_HOST, null)
        set(value) = prefs.edit().putString(KEY_HOST, value).apply()

    /**
     * The hub's own device_id, so a rediscovered address can be confirmed to be the same
     * hardware rather than whatever else answered on that IP.
     */
    var hubDeviceId: String?
        get() = prefs.getString(KEY_HUB_ID, null)
        set(value) = prefs.edit().putString(KEY_HUB_ID, value).apply()

    val isConfigured: Boolean get() = host != null && hubDeviceId != null

    /**
     * When the last automatic sweep for a moved hub ran, on the monotonic clock.
     *
     * Belongs to the hub rather than to a widget: the scan looks for one piece of hardware,
     * and three widgets failing at once should not mean three sweeps.
     */
    var lastRediscoveryAt: Long
        get() = prefs.getLong(KEY_LAST_REDISCOVERY, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_REDISCOVERY, value).apply()

    // ------------------------------------------------------------------ per-widget

    /**
     * When this widget's armed window ends, on the monotonic clock.
     *
     * A widget lives under a thumb, so it rests dimmed and takes one tap to wake. The
     * deadline is stored rather than held in memory because the widget's process is killed
     * between presses; elapsedRealtime is used so changing the wall clock cannot arm it.
     */
    fun armedUntil(appWidgetId: Int): Long =
        prefs.getLong(keyArmed(appWidgetId), 0L)

    fun setArmedUntil(appWidgetId: Int, elapsedRealtime: Long) {
        prefs.edit().putLong(keyArmed(appWidgetId), elapsedRealtime).apply()
    }

    /**
     * Counts full repaints of this widget.
     *
     * The launcher re-delivers a click belonging to the previous view tree shortly after the
     * tree is replaced. Every PendingIntent carries the generation it was drawn in, so a
     * replayed click from an older tree can be recognised and dropped instead of being
     * mistaken for a tap.
     */
    fun renderGeneration(appWidgetId: Int): Int =
        prefs.getInt(keyGeneration(appWidgetId), 0)

    /** Called by the full repaint, which is the only thing that invalidates old click targets. */
    fun bumpRenderGeneration(appWidgetId: Int): Int {
        val next = renderGeneration(appWidgetId) + 1
        // commit, not apply: the PendingIntents built straight after this must not be able to
        // reference a generation the next broadcast cannot yet read back.
        prefs.edit().putInt(keyGeneration(appWidgetId), next).commit()
        return next
    }

    fun remoteDeviceId(appWidgetId: Int): String? =
        prefs.getString(keyRemote(appWidgetId), null)

    fun remoteName(appWidgetId: Int): String? =
        prefs.getString(keyRemoteName(appWidgetId), null)

    /**
     * Slot index to raw `key_list[].name` for one grid. Absent entries render as blank.
     *
     * Keyed by layout as well as widget: the compact grid is not a prefix of the full one —
     * the buttons worth keeping when space is short are scattered across the real remote —
     * so slot 7 means different things in each and they cannot share a map.
     */
    fun slots(appWidgetId: Int, layout: WidgetLayout): Map<Int, SlotAssignment> {
        val stored = prefs.getString(keySlots(appWidgetId, layout), null) ?: return emptyMap()
        val json = runCatching { JSONObject(stored) }.getOrNull() ?: return emptyMap()
        return buildMap {
            json.keys().forEach { slot ->
                val entry = json.optJSONObject(slot) ?: return@forEach
                val name = entry.optString("name").takeIf { it.isNotEmpty() } ?: return@forEach
                slot.toIntOrNull()?.let { put(it, SlotAssignment(name, entry.optString("label", name))) }
            }
        }
    }

    fun putWidget(
        appWidgetId: Int,
        remote: IrRemote,
        slotsByLayout: Map<WidgetLayout, Map<Int, SlotAssignment>>,
    ) {
        val edit = prefs.edit()
            .putString(keyRemote(appWidgetId), remote.deviceId)
            .putString(keyRemoteName(appWidgetId), remote.nickname)
        slotsByLayout.forEach { (layout, slots) ->
            val json = JSONObject()
            slots.forEach { (slot, assignment) ->
                json.put(slot.toString(), JSONObject().put("name", assignment.keyName).put("label", assignment.label))
            }
            edit.putString(keySlots(appWidgetId, layout), json.toString())
        }
        edit.apply()
    }

    /** Called from the widget's onDeleted so removed widgets do not leave assignments behind. */
    fun removeWidget(appWidgetId: Int) {
        val edit = prefs.edit()
            .remove(keyRemote(appWidgetId))
            .remove(keyRemoteName(appWidgetId))
            .remove(keyArmed(appWidgetId))
            .remove(keyGeneration(appWidgetId))
        WidgetLayout.entries.forEach { edit.remove(keySlots(appWidgetId, it)) }
        edit.apply()
    }

    private fun keyArmed(id: Int) = "widget.$id.armed_until"
    private fun keyGeneration(id: Int) = "widget.$id.render_generation"
    private fun keyRemote(id: Int) = "widget.$id.remote"
    private fun keyRemoteName(id: Int) = "widget.$id.remote_name"
    /**
     * Includes a schema version. Slot indices only mean something relative to a particular
     * grid, so when a grid's cells change — the compact strip dropped its d-pad for power and
     * channel — stored assignments have to be abandoned rather than reinterpreted. A widget
     * whose grid comes back empty is treated as needing setup, which is the recovery path.
     */
    private fun keySlots(id: Int, layout: WidgetLayout) =
        "widget.$id.slots.${layout.id}.v$SLOTS_SCHEMA"

    private companion object {
        const val NAME = "tvremocon"
        const val KEY_HOST = "host"
        const val KEY_HUB_ID = "hub_device_id"
        const val KEY_LAST_REDISCOVERY = "last_rediscovery_at"

        /** Bumped whenever any layout's cells change meaning. */
        const val SLOTS_SCHEMA = 2
    }
}

/** One button: what to send, and what to print on it. */
data class SlotAssignment(val keyName: String, val label: String)

/**
 * Builds the starting assignment for a remote by resolving each layout slot's default
 * function against the keys this remote actually has.
 *
 * Both layouts are filled from the same call, because which one a widget shows depends on
 * the space the launcher gives it and can change when the user resizes.
 */
fun defaultSlots(remote: IrRemote, layout: WidgetLayout): Map<Int, SlotAssignment> =
    buildMap {
        layout.defaults.forEachIndexed { slot, function ->
            val key = function?.resolve(remote.keys) ?: return@forEachIndexed
            // Label comes from the function, not the key: "音量 ＋" reads better on a button
            // than "VOL+", and downloaded keys have no usable display name anyway.
            put(slot, SlotAssignment(keyName = key.name, label = function.label))
        }
    }

/** Which functions this remote cannot supply, so setup can say so instead of showing gaps. */
fun unresolvedFunctions(remote: IrRemote, layout: WidgetLayout): List<RemoteFunction> =
    layout.defaults.filterNotNull().distinct().filter { it.resolve(remote.keys) == null }
