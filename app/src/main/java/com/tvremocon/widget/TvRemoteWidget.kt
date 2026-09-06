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
import kotlinx.coroutines.Job
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
 * Waking and resting redraw the whole view tree. Partial updates were tried, on the theory
 * that a launcher was replaying clicks after each tree replacement; that theory was wrong —
 * the repeating pattern in the logs was someone pressing the button again every time it went
 * dim — and partial updates turn out not to apply at all to the size-mapped RemoteViews this
 * widget uses, so the widget stopped changing colour.
 *
 * A redraw therefore rebuilds every click target, and that is why it does **not** advance the
 * generation: a press already on its way would be dropped as stale for no reason. Only a
 * structural repaint — setup finishing, a resize — advances it, because those are the ones
 * after which an older click really is talking about a different widget.
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
     * Makes the widget live and paints it so.
     *
     * The window opens immediately — a press is checked against the deadline, not against
     * what is drawn — but the paint waits a beat. A view keeps its pressed state for a moment
     * after the finger lifts, and installing a background that has a pressed colour while
     * that is still set makes the woken key flash.
     *
     * The broadcast is released as soon as that paint is done. It used to be held for the
     * whole window so the resting repaint could not be frozen out, and that quietly broke the
     * widget: Android delivers broadcasts to one receiver serially, so every other tap queued
     * behind the hold and was released a moment after the window closed — where it counted as
     * a wake rather than a press. Four buttons in a row went nowhere that way.
     */
    private fun wake(
        context: Context,
        manager: AppWidgetManager,
        settings: Settings,
        appWidgetId: Int,
        now: Long,
    ) {
        val alreadyLive = settings.armedUntil(appWidgetId) > now
        Log.d(TAG, "wake widget=$appWidgetId alreadyLive=$alreadyLive")
        settings.setArmedUntil(appWidgetId, now + ARMED_WINDOW_MS)
        if (alreadyLive) return // extend only; a second hold racing the first is how loops start

        val pending = goAsync()
        scope.launch {
            try {
                delay(PRESSED_STATE_TAIL_MS)
                redraw(context, manager, appWidgetId, armed = true)
            } finally {
                pending.finish()
            }
        }
        scheduleRest(context, manager, settings, appWidgetId)
    }

    /**
     * Waits out the armed window, then paints the widget as resting.
     *
     * Runs on the process-lifetime scope with no broadcast held, so it cannot delay the next
     * tap. That is the whole point: holding one blocks every subsequent press behind it.
     *
     * An alarm would have been the obvious mechanism and does not work here — inexact alarms
     * are deferred heavily for background apps, and this device's power management freezes
     * the process about six seconds after it drops to the background. Nothing else needs to
     * survive that: a press is checked against the stored deadline, never against what is
     * drawn, so a widget left looking live simply wakes on the next tap instead of sending.
     * The five-second window keeps the wait comfortably inside that six-second margin.
     *
     * One waiter per widget. A later press extends the deadline, and the waiter re-checks
     * rather than assumes, so it settles on the last press rather than the first.
     */
    private fun scheduleRest(
        context: Context,
        manager: AppWidgetManager,
        settings: Settings,
        appWidgetId: Int,
    ) {
        synchronized(resting) {
            if (resting[appWidgetId]?.isActive == true) return
            resting[appWidgetId] = scope.launch {
                while (true) {
                    val remaining = settings.armedUntil(appWidgetId) - SystemClock.elapsedRealtime()
                    if (remaining <= 0) break
                    delay(remaining)
                }
                Log.d(TAG, "widget=$appWidgetId resting")
                redraw(context, manager, appWidgetId, armed = false)
            }
        }
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
            scheduleRest(context, manager, settings, appWidgetId)
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
        // Still live — the window was just extended by this press — so keep the lit colours
        // and only swap the status line.
        redraw(context, manager, appWidgetId, armed = true, statusOverride = describe(context, assignment, result))
        scheduleRest(context, manager, settings, appWidgetId)
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

        /** One pending resting-repaint per widget, so taps do not pile up waiters. */
        private val resting = mutableMapOf<Int, Job>()

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, TvRemoteWidget::class.java))
                .forEach { render(context, manager, it) }
        }

        /**
         * Structural repaint: setup finished, the widget was resized, the system asked for an
         * update. Advances the generation, which retires every click target drawn before it —
         * after a change of this kind an older click is describing a widget that no longer
         * exists in that form.
         */
        fun render(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
            val settings = Settings(context)
            val generation = settings.bumpRenderGeneration(appWidgetId)
            val armed = settings.armedUntil(appWidgetId) > SystemClock.elapsedRealtime()
            Log.d(TAG, "render widget=$appWidgetId generation=$generation armed=$armed")
            draw(context, manager, appWidgetId, settings, generation, armed, statusOverride = null)
        }

        /**
         * Visual redraw for waking and resting. Same tree, same click targets, same
         * generation — only the colours and the status line differ.
         *
         * Deliberately not a partial update: `partiallyUpdateAppWidget` does not appear to
         * apply to the size-mapped RemoteViews this widget hands the launcher, and using it
         * left the widget stuck at whatever it last looked like. And deliberately not a
         * generation bump: rebuilding identical click targets must not invalidate a press
         * that is already in flight.
         */
        private fun redraw(
            context: Context,
            manager: AppWidgetManager,
            appWidgetId: Int,
            armed: Boolean,
            statusOverride: String? = null,
        ) {
            val settings = Settings(context)
            val generation = settings.renderGeneration(appWidgetId)
            Log.d(TAG, "redraw widget=$appWidgetId armed=$armed generation=$generation")
            draw(context, manager, appWidgetId, settings, generation, armed, statusOverride)
        }

        private fun draw(
            context: Context,
            manager: AppWidgetManager,
            appWidgetId: Int,
            settings: Settings,
            generation: Int,
            armed: Boolean,
            statusOverride: String?,
        ) {
            val configured = settings.isConfigured && settings.remoteDeviceId(appWidgetId) != null
            val views = WidgetLayout.entries.associateWith {
                build(context, appWidgetId, it, settings, configured, generation, armed, statusOverride)
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
            generation: Int,
            armed: Boolean,
            statusOverride: String?,
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
                // The same click target whether resting or live. Whether it sends or merely
                // wakes is decided when it arrives, against the stored deadline.
                views.setOnClickPendingIntent(
                    id,
                    pressIntent(context, appWidgetId, layout, slot, generation),
                )
            }

            views.setTextViewText(
                R.id.status,
                statusOverride
                    ?: if (armed) settings.remoteName(appWidgetId).orEmpty()
                    else context.getString(R.string.widget_resting),
            )
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
