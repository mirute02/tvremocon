package com.tvremocon.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import com.tvremocon.R
import com.tvremocon.data.Settings
import com.tvremocon.data.SlotAssignment
import com.tvremocon.ir.SendResult
import com.tvremocon.ui.WidgetSetupActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The home-screen remote.
 *
 * One tap is one IR pulse. The press path never asks the hub what remotes exist — setup
 * stored the raw key names, so a button press is a single request.
 *
 * Everything is scoped to appWidgetId: the assignments, the status line, and the identity of
 * each PendingIntent. Two remotes on one home screen must not press each other's buttons.
 */
class TvRemoteWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { render(context, manager, it) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        // Resizing is how the user chooses between the compact grid and the number pad.
        render(context, manager, appWidgetId)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val settings = Settings(context)
        appWidgetIds.forEach(settings::removeWidget)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_PRESS && intent.action != ACTION_ARM) return

        val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, INVALID_ID)
        if (appWidgetId == INVALID_ID) return
        val manager = AppWidgetManager.getInstance(context)
        val settings = Settings(context)
        val now = SystemClock.elapsedRealtime()

        if (intent.action == ACTION_ARM) {
            // Live immediately — the deadline is what a press is checked against — but repaint
            // after a beat. A view keeps its pressed state for a moment after the finger
            // lifts, and swapping in a background that has a pressed colour while that is
            // still set makes the woken key flash blue. Waiting out the tail avoids it
            // without suppressing the press feedback that the same drawable provides later.
            settings.setArmedUntil(appWidgetId, now + ARMED_WINDOW_MS)
            val pendingArm = goAsync()
            scope.launch {
                try {
                    delay(PRESSED_STATE_TAIL_MS)
                    render(context, manager, appWidgetId)
                    holdUntilResting(context, manager, settings, appWidgetId)
                } finally {
                    pendingArm.finish()
                }
            }
            return
        }

        val slot = intent.getIntExtra(EXTRA_SLOT, -1)
        // The grids have separate slot numbering, so the layout is part of the address.
        val layout = WidgetLayout.entries.firstOrNull { it.id == intent.getStringExtra(EXTRA_LAYOUT) }
        if (slot < 0 || layout == null) return

        // Armed-ness is decided here, not by which PendingIntent was drawn. The dimmed look
        // can go stale — the process dies, no redraw happens — and a stale picture must never
        // be able to fire the TV. An expired tap re-arms instead of sending.
        if (settings.armedUntil(appWidgetId) <= now) {
            settings.setArmedUntil(appWidgetId, now + ARMED_WINDOW_MS)
            val pendingArm = goAsync()
            scope.launch {
                try {
                    delay(PRESSED_STATE_TAIL_MS)
                    render(context, manager, appWidgetId)
                    holdUntilResting(context, manager, settings, appWidgetId)
                } finally {
                    pendingArm.finish()
                }
            }
            return
        }
        settings.setArmedUntil(appWidgetId, now + ARMED_WINDOW_MS)

        // The deadline starts now, not when the coroutine gets scheduled. goAsync() buys
        // roughly ten seconds total, and a queue of taps waiting on the hub mutex can eat
        // that before this one even reaches the network.
        val deadline = SystemClock.elapsedRealtime() + OPERATION_BUDGET_MS

        val pending = goAsync()
        scope.launch {
            try {
                press(context, appWidgetId, layout, slot, deadline)
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Waits out the armed window and repaints the widget as resting.
     *
     * An alarm was the obvious mechanism and it did not work: inexact alarms are deferred
     * heavily for background apps on Android 12+, and this device's vendor power management
     * freezes the process on top of that, so the widget kept looking live long after it had
     * stopped accepting presses. Holding the broadcast open and waiting is deterministic for
     * as long as the process survives, which is the case that matters — the user is looking
     * at the widget they just tapped.
     *
     * A later press extends the deadline, so the wait is re-checked rather than assumed;
     * whichever hold outlives the others does the repaint and the rest do nothing.
     *
     * Still only cosmetic. If the process is killed first the widget looks armed while it is
     * not, and a tap in that state re-arms instead of sending, because a press is checked
     * against the stored deadline and never against what is drawn.
     */
    private suspend fun holdUntilResting(
        context: Context,
        manager: AppWidgetManager,
        settings: Settings,
        appWidgetId: Int,
    ) {
        val enteredAt = SystemClock.elapsedRealtime()
        Log.d(TAG, "hold widget=$appWidgetId waiting ${settings.armedUntil(appWidgetId) - enteredAt}ms")
        while (true) {
            val remaining = settings.armedUntil(appWidgetId) - SystemClock.elapsedRealtime()
            if (remaining <= 0) break
            delay(remaining)
        }
        // If this line never appears, the process was frozen or killed before the window
        // closed, and the widget is still showing its armed colours while resting.
        Log.d(TAG, "hold widget=$appWidgetId resting after ${SystemClock.elapsedRealtime() - enteredAt}ms")
        render(context, manager, appWidgetId)
    }

    private suspend fun press(
        context: Context,
        appWidgetId: Int,
        layout: WidgetLayout,
        slot: Int,
        deadline: Long,
    ) {
        val settings = Settings(context)
        val assignment = settings.slots(appWidgetId, layout)[slot] ?: return
        val manager = AppWidgetManager.getInstance(context)
        val startedAt = SystemClock.elapsedRealtime()

        // No "sending" update: it costs a full RemoteViews build and an IPC round trip
        // before the request even starts, which is pure latency on the one path that has to
        // feel immediate. The button's own pressed state is the acknowledgement.

        val remaining = deadline - SystemClock.elapsedRealtime()
        val result = if (remaining <= 0) {
            // Better to drop a stale press than to fire it late: by the time an old tap
            // reaches the TV the user has moved on, and an unexpected channel change is worse
            // than a button that did nothing.
            SendResult.NotSent(SendResult.Reason.DEADLINE_PASSED)
        } else {
            // withTimeoutOrNull alone cannot stop a blocking socket read; the HTTP client
            // carries its own connect/read/write timeouts, and this bounds the wait for the
            // hub mutex on top of them.
            withTimeoutOrNull(remaining) {
                RemoteSender.send(context, appWidgetId, assignment.keyName)
            } ?: SendResult.Unknown(context.getString(R.string.status_timed_out))
        }

        // One line per tap. Two lines for one physical press would mean the launcher
        // delivered the click twice; a large elapsed time points at the transport instead.
        Log.i(TAG, "press widget=$appWidgetId ${layout.id}/$slot ${assignment.label} " +
            "-> $result in ${SystemClock.elapsedRealtime() - startedAt}ms")
        setStatus(context, manager, appWidgetId, describe(context, assignment, result))
        holdUntilResting(context, manager, settings, appWidgetId)
    }

    private fun describe(context: Context, assignment: SlotAssignment, result: SendResult): String =
        when (result) {
            is SendResult.Accepted -> context.getString(R.string.status_accepted, assignment.label)
            is SendResult.Rejected -> context.getString(R.string.status_rejected, assignment.label, result.code.toString())
            // Not phrased as a failure: the hub may have transmitted, and telling the user it
            // failed invites a second press.
            is SendResult.Unknown -> context.getString(R.string.status_unknown, assignment.label)
            is SendResult.NotSent -> context.getString(
                R.string.status_not_sent,
                assignment.label,
                context.getString(
                    when (result.reason) {
                        SendResult.Reason.NO_PERMISSION -> R.string.reason_no_permission
                        SendResult.Reason.NO_WIFI -> R.string.reason_no_wifi
                        SendResult.Reason.NOT_CONFIGURED -> R.string.reason_not_configured
                        SendResult.Reason.BAD_CREDENTIALS -> R.string.reason_bad_credentials
                        SendResult.Reason.HUB_UNREACHABLE -> R.string.reason_hub_unreachable
                        SendResult.Reason.DEADLINE_PASSED -> R.string.reason_deadline
                    }
                ),
            )
        }

    private fun setStatus(context: Context, manager: AppWidgetManager, appWidgetId: Int, text: String) {
        // Redrawing the whole widget would rebuild every PendingIntent for a status change.
        val views = WidgetLayout.entries.associateWith { layout ->
            RemoteViews(context.packageName, layoutResource(layout))
                .apply { setTextViewText(R.id.status, text) }
        }
        manager.partiallyUpdateAppWidget(appWidgetId, sized(views))
    }

    companion object {
        private const val TAG = "TvRemocon"
        private const val ACTION_PRESS = "com.tvremocon.PRESS"
        private const val EXTRA_SLOT = "slot"
        private const val EXTRA_LAYOUT = "layout"
        private const val ACTION_ARM = "com.tvremocon.ARM"

        /**
         * How long one tap keeps the widget live.
         *
         * Five seconds is not only a taste decision. This device's vendor power management
         * freezes the app about six seconds after it drops to the background — its own logs
         * show `FZ ... reason: Bg` that soon after an unfreeze — and a frozen process runs no
         * code, so a longer window would expire while the widget was still painted as armed.
         * Staying inside that margin is what makes the resting repaint actually happen.
         */
        private const val ARMED_WINDOW_MS = 5_000L

        /**
         * How long a view holds its pressed state after the finger lifts. Android keeps it
         * briefly on purpose so a quick tap still shows feedback; this waits it out before
         * installing backgrounds that have a pressed colour.
         */
        private const val PRESSED_STATE_TAIL_MS = 220L
        private const val INVALID_ID = AppWidgetManager.INVALID_APPWIDGET_ID

        /**
         * How long one press may take end to end, including waiting for the hub mutex behind
         * other taps. Chosen to stay inside goAsync()'s budget with room to update the status
         * line afterwards — it is this app's own limit, not a platform constant.
         */
        private const val OPERATION_BUDGET_MS = 7_000L

        /** Outlives any single broadcast, since goAsync() hands the work off. */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, TvRemoteWidget::class.java))
                .forEach { render(context, manager, it) }
        }

        fun render(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
            val settings = Settings(context)
            val configured = settings.isConfigured && settings.remoteDeviceId(appWidgetId) != null
            val armed = settings.armedUntil(appWidgetId) > SystemClock.elapsedRealtime()

            val views = WidgetLayout.entries.associateWith {
                build(context, appWidgetId, it, settings, configured, armed)
            }
            manager.updateAppWidget(appWidgetId, sized(views))
        }

        /**
         * Lets the launcher pick the grid from the space it actually allocates.
         *
         * The three shapes are deliberately far apart so the choice is unambiguous: a short
         * strip, something wide but not tall, and something tall. Resizing the widget is how
         * the user moves between them — an app cannot resize its own widget — and a Fold's
         * cover and inner screens each get a sensible grid without this code knowing anything
         * about folding.
         */
        private fun sized(views: Map<WidgetLayout, RemoteViews>): RemoteViews =
            RemoteViews(
                buildMap {
                    views[WidgetLayout.COMPACT]?.let { put(SizeF(120f, 56f), it) }
                    views[WidgetLayout.WIDE]?.let { put(SizeF(300f, 150f), it) }
                    views[WidgetLayout.FULL]?.let { put(SizeF(150f, 340f), it) }
                }
            )

        private fun build(
            context: Context,
            appWidgetId: Int,
            layout: WidgetLayout,
            settings: Settings,
            configured: Boolean,
            armed: Boolean,
        ): RemoteViews {
            val slots = if (configured) settings.slots(appWidgetId, layout) else emptyMap()
            val views = RemoteViews(context.packageName, layoutResource(layout))

            // An empty grid is also "needs setup", not just a missing host. Assignments can
            // go missing when the stored format changes between versions, and a widget with
            // no buttons would otherwise have no way back to the configuration screen.
            if (!configured || slots.isEmpty()) {
                // Every button opens setup, so there is no way to tap a dead widget and get
                // nothing. Straight to an Activity — never a broadcast that then starts one.
                val setup = setupIntent(context, appWidgetId)
                for (slot in 0 until layout.slotCount) {
                    val id = slotViewId(context, layout, slot) ?: continue
                    views.setTextViewText(id, "")
                    views.setViewVisibility(id, View.VISIBLE)
                    views.setOnClickPendingIntent(id, setup)
                }
                views.setTextViewText(R.id.status, context.getString(R.string.widget_tap_to_setup))
                views.setOnClickPendingIntent(R.id.status, setup)
                return views
            }

            // While resting, every key wakes the widget instead of firing. One shared
            // PendingIntent: they all do the same thing, and building forty of them to say so
            // would be waste.
            val wake = armIntent(context, appWidgetId)

            for (slot in 0 until layout.slotCount) {
                val id = slotViewId(context, layout, slot) ?: continue
                val assignment = slots[slot]
                if (assignment == null) {
                    // INVISIBLE, not GONE: a missing cell would collapse the grid and shift
                    // every button after it.
                    views.setTextViewText(id, "")
                    views.setViewVisibility(id, View.INVISIBLE)
                    continue
                }
                views.setViewVisibility(id, View.VISIBLE)
                views.setTextViewText(id, assignment.label)

                val style = layout.cells[slot].style
                // setBackgroundResource and setTextColor are the only styling RemoteViews
                // allows at runtime, which is why each style ships a prepared idle twin
                // rather than being tinted here.
                views.setInt(
                    id,
                    "setBackgroundResource",
                    drawableId(context, if (armed) style.background else style.idleBackground),
                )
                views.setTextColor(
                    id,
                    colorOf(context, if (armed) style.textColor else style.idleTextColor),
                )
                views.setOnClickPendingIntent(
                    id,
                    if (armed) pressIntent(context, appWidgetId, layout, slot) else wake,
                )
            }

            views.setTextViewText(
                R.id.status,
                if (armed) settings.remoteName(appWidgetId).orEmpty()
                else context.getString(R.string.widget_resting),
            )
            views.setOnClickPendingIntent(R.id.status, wake)
            return views
        }

        private fun layoutResource(layout: WidgetLayout): Int = when (layout) {
            WidgetLayout.COMPACT -> R.layout.widget_remote_compact
            WidgetLayout.WIDE -> R.layout.widget_remote_wide
            WidgetLayout.FULL -> R.layout.widget_remote_full
        }

        private fun armIntent(context: Context, appWidgetId: Int): PendingIntent {
            val intent = Intent(context, TvRemoteWidget::class.java)
                .setAction(ACTION_ARM)
                .setData(Uri.parse("tvremocon://arm/$appWidgetId"))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            return PendingIntent.getBroadcast(
                context,
                // Kept clear of the per-slot codes, which run from appWidgetId * n upwards.
                Int.MIN_VALUE + appWidgetId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        @Suppress("DiscouragedApi")
        private fun drawableId(context: Context, name: String): Int =
            context.resources.getIdentifier(name, "drawable", context.packageName)

        @Suppress("DiscouragedApi")
        private fun colorOf(context: Context, name: String): Int =
            context.getColor(context.resources.getIdentifier(name, "color", context.packageName))

        private fun slotViewId(context: Context, layout: WidgetLayout, slot: Int): Int? {
            @Suppress("DiscouragedApi")
            val id = context.resources.getIdentifier(
                layout.viewIdName(slot), "id", context.packageName
            )
            return id.takeIf { it != 0 }
        }

        /**
         * PendingIntents are matched on requestCode plus the Intent's action, data and
         * component — extras are ignored. Two widgets would therefore share one PendingIntent
         * per slot if only extras differed, and pressing one would send the other's key.
         *
         * So identity is carried three ways: a unique requestCode, a data URI naming the
         * widget and slot, and the extras the receiver actually reads.
         */
        private fun pressIntent(
            context: Context,
            appWidgetId: Int,
            layout: WidgetLayout,
            slot: Int,
        ): PendingIntent {
            val intent = Intent(context, TvRemoteWidget::class.java)
                .setAction(ACTION_PRESS)
                .setData(Uri.parse("tvremocon://widget/$appWidgetId/${layout.id}/$slot"))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .putExtra(EXTRA_LAYOUT, layout.id)
                .putExtra(EXTRA_SLOT, slot)
            return PendingIntent.getBroadcast(
                context,
                // Distinct per widget, per grid, per slot. The data URI above carries the same
                // three, since PendingIntent equality ignores extras.
                (appWidgetId * WidgetLayout.entries.size + layout.ordinal) * WidgetLayout.MAX_SLOTS + slot,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun setupIntent(context: Context, appWidgetId: Int): PendingIntent {
            val intent = Intent(context, WidgetSetupActivity::class.java)
                .setData(Uri.parse("tvremocon://setup/$appWidgetId"))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PendingIntent.getActivity(
                context,
                appWidgetId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
