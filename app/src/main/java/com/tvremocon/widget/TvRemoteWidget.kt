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
 *
 * **Full repaints are rare on purpose.** Replacing the view tree makes this launcher
 * re-deliver a click that belonged to the tree being replaced, roughly 10-30ms later. With
 * the resting look painted by a full repaint that was self-sustaining: repaint, replayed
 * click, wake, five seconds, repaint again — a loop that ran for over a minute and swallowed
 * every real tap. So waking and resting are partial updates, which change colours and text
 * without rebuilding anything clickable, and every PendingIntent carries the generation it
 * was drawn in so a replayed click from an older tree is recognised and dropped.
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
        // Resizing is how the user chooses between the compact grid and the number pad, and
        // it is one of the few things that genuinely needs the tree rebuilt.
        render(context, manager, appWidgetId)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val settings = Settings(context)
        appWidgetIds.forEach(settings::removeWidget)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_PRESS) return

        val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, INVALID_ID)
        if (appWidgetId == INVALID_ID) return

        val manager = AppWidgetManager.getInstance(context)
        val settings = Settings(context)
        val now = SystemClock.elapsedRealtime()

        // Checked before anything else. A click carrying an older generation came from a view
        // tree that has since been replaced, which means the launcher replayed it rather than
        // the user pressing anything — acting on it would fire the TV unbidden.
        val generation = intent.getIntExtra(EXTRA_GENERATION, -1)
        val current = settings.renderGeneration(appWidgetId)
        if (generation != current) {
            Log.w(TAG, "press widget=$appWidgetId ignored: generation $generation, current $current")
            return
        }

        val slot = intent.getIntExtra(EXTRA_SLOT, -1)
        // The grids have separate slot numbering, so the layout is part of the address.
        val layout = WidgetLayout.entries.firstOrNull { it.id == intent.getStringExtra(EXTRA_LAYOUT) }
        if (layout == null) {
            Log.w(TAG, "press widget=$appWidgetId ignored: layout=${intent.getStringExtra(EXTRA_LAYOUT)}")
            return
        }

        // Armed-ness is decided here, not by what is drawn. The painted state can go stale —
        // the process is killed mid-window and no repaint happens — and a stale picture must
        // never be able to fire the TV. A tap after the window closes wakes instead of sending.
        if (settings.armedUntil(appWidgetId) <= now) {
            Log.d(TAG, "press widget=$appWidgetId ${layout.id}/$slot arrived while resting — waking")
            wake(context, manager, settings, appWidgetId, now)
            return
        }

        // The status line's own slot only ever wakes; there is no key behind it.
        if (slot < 0) {
            settings.setArmedUntil(appWidgetId, now + ARMED_WINDOW_MS)
            return
        }

        Log.d(TAG, "press widget=$appWidgetId ${layout.id}/$slot accepted while armed")
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
     * Makes the widget live and paints it so, then waits out the window.
     *
     * The window opens immediately — a press is checked against the deadline, not against
     * what is drawn — but the paint waits a beat. A view keeps its pressed state for a moment
     * after the finger lifts, and installing a background that has a pressed colour while
     * that is still set makes the woken key flash.
     */
    private fun wake(
        context: Context,
        manager: AppWidgetManager,
        settings: Settings,
        appWidgetId: Int,
        now: Long,
    ) {
        val alreadyLive = settings.armedUntil(appWidgetId) > now
        settings.setArmedUntil(appWidgetId, now + ARMED_WINDOW_MS)
        if (alreadyLive) return // extend only; a second hold racing the first is how loops start

        val pending = goAsync()
        scope.launch {
            try {
                delay(PRESSED_STATE_TAIL_MS)
                paint(context, manager, appWidgetId, armed = true)
                holdUntilResting(context, manager, settings, appWidgetId)
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Waits out the armed window, then paints the widget as resting.
     *
     * An alarm was the obvious mechanism and does not work here: inexact alarms are deferred
     * heavily for background apps, and this device's vendor power management freezes the
     * process about six seconds after it drops to the background. Holding the broadcast open
     * and waiting is deterministic for as long as the process survives, which is the case
     * that matters — the user is looking at the widget they just tapped. It is also why the
     * window is five seconds: fifteen was tried, and the repaint never arrived.
     *
     * A later press extends the deadline, so the wait is re-checked rather than assumed;
     * whichever hold outlives the others does the repaint and the rest do nothing.
     */
    private suspend fun holdUntilResting(
        context: Context,
        manager: AppWidgetManager,
        settings: Settings,
        appWidgetId: Int,
    ) {
        while (true) {
            val remaining = settings.armedUntil(appWidgetId) - SystemClock.elapsedRealtime()
            if (remaining <= 0) break
            delay(remaining)
        }
        Log.d(TAG, "widget=$appWidgetId resting")
        paint(context, manager, appWidgetId, armed = false)
    }

    private suspend fun press(
        context: Context,
        appWidgetId: Int,
        layout: WidgetLayout,
        slot: Int,
        deadline: Long,
    ) {
        val settings = Settings(context)
        val assignment = settings.slots(appWidgetId, layout)[slot]
        val manager = AppWidgetManager.getInstance(context)
        if (assignment == null) {
            // Logged rather than returned silently, which made a dead button and a dead app
            // look identical from outside.
            Log.w(TAG, "press widget=$appWidgetId ${layout.id}/$slot has no assignment " +
                "(${settings.slots(appWidgetId, layout).size} slots stored)")
            holdUntilResting(context, manager, settings, appWidgetId)
            return
        }
        val startedAt = SystemClock.elapsedRealtime()

        // No "sending" update: it costs an IPC round trip before the request even starts,
        // which is pure latency on the one path that has to feel immediate. The button's own
        // pressed state is the acknowledgement.

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

        // One line per tap. Two lines for one physical press would mean the click was
        // delivered twice; a large elapsed time points at the transport instead.
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
        private const val EXTRA_GENERATION = "generation"

        /** The status line's pseudo-slot. It has no key behind it and only ever wakes. */
        @androidx.annotation.VisibleForTesting
        internal const val SLOT_STATUS = -1

        /**
         * How long one tap keeps the widget live.
         *
         * Five seconds, because this device's vendor power management freezes a backgrounded
         * app about six seconds in and a frozen process cannot run the repaint. Fifteen was
         * tried on the theory that holding the broadcast open would keep the process out of
         * that state; it does not, and the widget stayed lit. The window has to be short
         * enough that the repaint reliably happens, or the widget goes back to lying about
         * whether it is armed.
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

        /**
         * Rebuilds the whole view tree, which invalidates every click target on it.
         *
         * Reserved for the things that actually need it — setup finishing, a resize, a system
         * update broadcast — because this launcher answers a tree replacement by replaying a
         * click from the tree it just discarded. Bumping the generation is what makes that
         * replay identifiable; waking and resting go through [paint] instead and leave the
         * tree alone.
         *
         * Always drawn resting. If a partial update is ever lost the widget falls back to
         * looking asleep, which is the safe direction: it can under-report being live, never
         * over-report it.
         */
        fun render(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
            val settings = Settings(context)
            val configured = settings.isConfigured && settings.remoteDeviceId(appWidgetId) != null
            val generation = settings.bumpRenderGeneration(appWidgetId)
            Log.d(TAG, "render widget=$appWidgetId generation=$generation configured=$configured")

            val views = WidgetLayout.entries.associateWith {
                build(context, appWidgetId, it, settings, configured, generation)
            }
            manager.updateAppWidget(appWidgetId, sized(views))
        }

        /**
         * Switches the widget between its resting and live looks without touching the view
         * tree — only backgrounds, text colours and the status line, which are the two
         * styling calls RemoteViews allows at runtime plus a text change.
         *
         * Click targets are deliberately left as they are. They were installed by [render]
         * and stay valid, so nothing here can trigger the launcher's replay.
         */
        private fun paint(
            context: Context,
            manager: AppWidgetManager,
            appWidgetId: Int,
            armed: Boolean,
        ) {
            val settings = Settings(context)
            if (!settings.isConfigured || settings.remoteDeviceId(appWidgetId) == null) return

            val views = WidgetLayout.entries.associateWith { layout ->
                val slots = settings.slots(appWidgetId, layout)
                RemoteViews(context.packageName, layoutResource(layout)).apply {
                    for (slot in 0 until layout.slotCount) {
                        if (slots[slot] == null) continue
                        val id = slotViewId(context, layout, slot) ?: continue
                        val style = layout.cells[slot].style
                        setInt(
                            id,
                            "setBackgroundResource",
                            drawableId(context, if (armed) style.background else style.idleBackground),
                        )
                        setTextColor(
                            id,
                            colorOf(context, if (armed) style.textColor else style.idleTextColor),
                        )
                    }
                    setTextViewText(
                        R.id.status,
                        if (armed) settings.remoteName(appWidgetId).orEmpty()
                        else context.getString(R.string.widget_resting),
                    )
                }
            }
            manager.partiallyUpdateAppWidget(appWidgetId, sized(views))
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
            generation: Int,
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
                // Drawn resting; paint() takes it from here.
                views.setInt(id, "setBackgroundResource", drawableId(context, style.idleBackground))
                views.setTextColor(id, colorOf(context, style.idleTextColor))
                // The same click target whether resting or live. Whether it sends or merely
                // wakes is decided when it arrives, against the stored deadline.
                views.setOnClickPendingIntent(
                    id,
                    pressIntent(context, appWidgetId, layout, slot, generation),
                )
            }

            views.setTextViewText(R.id.status, context.getString(R.string.widget_resting))
            views.setOnClickPendingIntent(
                R.id.status,
                pressIntent(context, appWidgetId, layout, SLOT_STATUS, generation),
            )
            return views
        }

        private fun layoutResource(layout: WidgetLayout): Int = when (layout) {
            WidgetLayout.COMPACT -> R.layout.widget_remote_compact
            WidgetLayout.WIDE -> R.layout.widget_remote_wide
            WidgetLayout.FULL -> R.layout.widget_remote_full
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
         * Identity is carried three ways: a unique requestCode, a data URI naming the widget,
         * grid, slot and generation, and the extras the receiver actually reads. The
         * generation in the URI is what makes a replayed click from a discarded view tree a
         * different PendingIntent rather than the same one fired twice.
         */
        private fun pressIntent(
            context: Context,
            appWidgetId: Int,
            layout: WidgetLayout,
            slot: Int,
            generation: Int,
        ): PendingIntent {
            val intent = Intent(context, TvRemoteWidget::class.java)
                .setAction(ACTION_PRESS)
                .setData(Uri.parse("tvremocon://widget/$appWidgetId/${layout.id}/$slot/g$generation"))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .putExtra(EXTRA_LAYOUT, layout.id)
                .putExtra(EXTRA_SLOT, slot)
                .putExtra(EXTRA_GENERATION, generation)
            return PendingIntent.getBroadcast(
                context,
                // Distinct per widget, per grid, per slot. The status line's pseudo-slot sits
                // just past the real ones rather than at a negative offset.
                requestCode(appWidgetId, layout, slot),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /** Visible for tests: a collision here would make one remote fire another's keys. */
        @androidx.annotation.VisibleForTesting
        internal fun requestCode(appWidgetId: Int, layout: WidgetLayout, slot: Int): Int {
            val index = if (slot == SLOT_STATUS) WidgetLayout.MAX_SLOTS else slot
            return (appWidgetId * WidgetLayout.entries.size + layout.ordinal) *
                (WidgetLayout.MAX_SLOTS + 1) + index
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
