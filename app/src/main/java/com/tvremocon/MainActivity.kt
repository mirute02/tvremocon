package com.tvremocon

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tvremocon.data.SecretStore
import com.tvremocon.data.Settings
import com.tvremocon.net.LocalNetworkAccess
import com.tvremocon.ui.DebugActivity
import com.tvremocon.ui.HubSetupActivity
import com.tvremocon.widget.TvRemoteWidget

/**
 * What the launcher icon opens.
 *
 * The app's real surface is the home-screen widget, so this exists to explain that and to
 * show whether the pieces a widget depends on are in place. Diagnosing from here beats
 * pressing a widget button and getting one line of status text.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var report: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        report = TextView(this).apply {
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.3f)
        }

        setContentView(
            ScrollView(this).apply {
                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(20), dp(20), dp(20), dp(20))
                        addView(report)
                        addView(
                            Button(this@MainActivity).apply {
                                text = getString(R.string.main_hub_setup)
                                setOnClickListener {
                                    startActivity(Intent(this@MainActivity, HubSetupActivity::class.java))
                                }
                            }
                        )
                        addView(
                            Button(this@MainActivity).apply {
                                text = getString(R.string.main_open_debug)
                                setOnClickListener {
                                    startActivity(Intent(this@MainActivity, DebugActivity::class.java))
                                }
                            }
                        )
                        addView(
                            Button(this@MainActivity).apply {
                                text = getString(R.string.main_refresh_widgets)
                                setOnClickListener {
                                    TvRemoteWidget.refresh(this@MainActivity)
                                    describe()
                                }
                            }
                        )
                    }
                )
            }
        )
    }

    override fun onResume() {
        super.onResume()
        describe()
    }

    private fun describe() {
        val settings = Settings(this)
        val widgetIds = AppWidgetManager.getInstance(this)
            .getAppWidgetIds(ComponentName(this, TvRemoteWidget::class.java))

        report.text = buildString {
            appendLine(getString(R.string.main_intro))
            appendLine()
            appendLine(line(R.string.main_check_permission, LocalNetworkAccess.hasPermission(this@MainActivity)))
            appendLine(line(R.string.main_check_wifi, LocalNetworkAccess.wifiNetwork(this@MainActivity) != null))
            appendLine(line(R.string.main_check_credentials, SecretStore(this@MainActivity).hasAuthHash()))
            appendLine(
                line(R.string.main_check_hub, settings.isConfigured) +
                    (settings.host?.let { " ($it)" } ?: "")
            )
            appendLine(getString(R.string.main_widget_count, widgetIds.size))
            widgetIds.forEach { id ->
                val name = settings.remoteName(id)
                appendLine("  #$id  ${name ?: getString(R.string.main_widget_unconfigured)}")
            }
            if (widgetIds.isEmpty()) {
                appendLine()
                appendLine(getString(R.string.main_how_to_add))
            }
        }
    }

    private fun line(labelRes: Int, ok: Boolean): String =
        "${if (ok) "✓" else "✗"} ${getString(labelRes)}"

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
