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
        if (intent.action != ACTION_PRESS) return

        val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, INVALID_ID)
        val slot = intent.getIntExtra(EXTRA_SLOT, -1)
        // The two grids have separate slot numbering, so the layout is part of the address.
        val layout = WidgetLayout.entries.firstOrNull { it.id == intent.getStringExtra(EXTRA_LAYOUT) }
        if (appWidgetId == INVALID_ID || slot < 0 || layout == null) return

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

        setStatus(context, manager, appWidgetId, context.getString(R.string.status_sending, assignment.label))

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

        Log.i(TAG, "widget=$appWidgetId ${layout.id}/$slot ${assignment.label} -> $result")
        setStatus(context, manager, appWidgetId, describe(context, assignment, result))
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
        val compact = RemoteViews(context.packageName, R.layout.widget_remote_compact)
        val full = RemoteViews(context.packageName, R.layout.widget_remote_full)
        listOf(compact, full).forEach { it.setTextViewText(R.id.status, text) }
        manager.partiallyUpdateAppWidget(appWidgetId, sized(compact, full))
    }

    companion object {
        private const val TAG = "TvRemocon"
        private const val ACTION_PRESS = "com.tvremocon.PRESS"
        private const val EXTRA_SLOT = "slot"
        private const val EXTRA_LAYOUT = "layout"
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

            val compact = build(context, appWidgetId, WidgetLayout.COMPACT, settings, configured)
            val full = build(context, appWidgetId, WidgetLayout.FULL, settings, configured)
            manager.updateAppWidget(appWidgetId, sized(compact, full))
        }

        /**
         * Lets the launcher pick the grid from the space it actually allocates, rather than
         * this code guessing from screen names. A Fold's cover and inner screens then each get
         * a sensible grid without knowing anything about folding.
         */
        private fun sized(compact: RemoteViews, full: RemoteViews): RemoteViews =
            RemoteViews(
                mapOf(
                    SizeF(140f, 180f) to compact,
                    SizeF(160f, 380f) to full,
                )
            )

        private fun build(
            context: Context,
            appWidgetId: Int,
            layout: WidgetLayout,
            settings: Settings,
            configured: Boolean,
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
                views.setOnClickPendingIntent(id, pressIntent(context, appWidgetId, layout, slot))
            }
            views.setTextViewText(R.id.status, settings.remoteName(appWidgetId).orEmpty())
            return views
        }

        private fun layoutResource(layout: WidgetLayout): Int = when (layout) {
            WidgetLayout.COMPACT -> R.layout.widget_remote_compact
            WidgetLayout.FULL -> R.layout.widget_remote_full
        }

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
