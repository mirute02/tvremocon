package com.tvremocon.ui

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.net.Network
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tvremocon.data.SecretStore
import com.tvremocon.data.Settings
import com.tvremocon.data.defaultSlots
import com.tvremocon.data.unresolvedFunctions
import com.tvremocon.ir.IrRemote
import com.tvremocon.ir.TapoIrHub
import com.tvremocon.net.HubDiscovery
import com.tvremocon.net.HubEndpoint
import com.tvremocon.net.LocalNetworkAccess
import com.tvremocon.transport.klap.KlapSession
import com.tvremocon.transport.klap.KlapTransport
import com.tvremocon.widget.RemoteSender
import com.tvremocon.widget.TvRemoteWidget
import com.tvremocon.widget.WidgetLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Widget configuration, launched by the launcher when a widget is placed.
 *
 * Finds the hub, authenticates once to derive the authHash, lists the remotes and saves a
 * starting button assignment. Nothing here sends IR.
 */
class WidgetSetupActivity : AppCompatActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    private lateinit var status: TextView
    private lateinit var host: EditText
    private lateinit var email: EditText
    private lateinit var password: EditText
    private lateinit var content: LinearLayout

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            status.text = getString(
                if (granted) com.tvremocon.R.string.setup_permission_granted
                else com.tvremocon.R.string.setup_permission_denied
            )
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // The launcher treats a configuration activity that finishes without RESULT_OK as a
        // cancelled placement and removes the widget. Set it up front so backing out does
        // exactly that.
        setResult(Activity.RESULT_CANCELED, resultIntent())

        val settings = Settings(this)

        status = TextView(this).apply { setPadding(0, 0, 0, dp(12)) }
        host = EditText(this).apply {
            hint = getString(com.tvremocon.R.string.setup_hint_host)
            setText(settings.host.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT
        }
        email = EditText(this).apply {
            // Case preserved: the auth hash is over the exact string.
            hint = getString(com.tvremocon.R.string.setup_hint_email)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        password = EditText(this).apply {
            hint = getString(com.tvremocon.R.string.setup_hint_password)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val discover = Button(this).apply {
            text = getString(com.tvremocon.R.string.setup_discover)
            setOnClickListener { discover() }
        }
        val connect = Button(this).apply {
            text = getString(com.tvremocon.R.string.setup_connect)
            setOnClickListener { connect() }
        }

        status.text = getString(com.tvremocon.R.string.setup_intro)

        setContentView(
            ScrollView(this).apply {
                addView(
                    LinearLayout(this@WidgetSetupActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(16), dp(16), dp(16), dp(16))
                        listOf<View>(status, host, discover, email, password, connect, content)
                            .forEach(::addView)
                    }
                )
            }
        )
    }

    private fun wifiOrComplain(): Network? {
        when (LocalNetworkAccess.blockedReason(this)) {
            LocalNetworkAccess.Blocked.PERMISSION -> {
                LocalNetworkAccess.permission?.let(requestPermission::launch)
                return null
            }
            LocalNetworkAccess.Blocked.NO_WIFI -> {
                status.text = getString(com.tvremocon.R.string.reason_no_wifi)
                return null
            }
            null -> Unit
        }
        return LocalNetworkAccess.wifiNetwork(this)
    }

    private fun discover() {
        val network = wifiOrComplain() ?: return
        status.text = getString(com.tvremocon.R.string.setup_searching)
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { HubDiscovery.scan(network) }
            status.text = when {
                found.isEmpty() -> getString(com.tvremocon.R.string.setup_none_found)
                else -> {
                    host.setText(found.first().host)
                    getString(com.tvremocon.R.string.setup_found, found.joinToString { it.host })
                }
            }
        }
    }

    private fun connect() {
        val network = wifiOrComplain() ?: return
        val endpoint = HubEndpoint.of(host.text.toString().trim()) ?: run {
            status.text = getString(com.tvremocon.R.string.setup_bad_host)
            return
        }
        val user = email.text.toString().trim()
        val pass = password.text.toString()
        if (user.isEmpty() || pass.isEmpty()) {
            status.text = getString(com.tvremocon.R.string.setup_need_credentials)
            return
        }

        status.text = getString(com.tvremocon.R.string.setup_connecting, endpoint.toString())
        content.removeAllViews()

        lifecycleScope.launch {
            // Derive the hash and drop the password: it is never stored, and nothing after
            // this point needs it.
            val authHash = KlapSession.authHash(user, pass)
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val hub = TapoIrHub(
                        KlapTransport(endpoint, authHash, KlapTransport.httpClient(network.socketFactory))
                    )
                    hub.deviceInfo() to hub.getRemotes()
                }
            }

            outcome.onSuccess { (info, remotes) ->
                if (!info.isIrCapableHub) {
                    status.text = getString(com.tvremocon.R.string.setup_not_a_hub, info.model)
                    return@onSuccess
                }
                Settings(this@WidgetSetupActivity).apply {
                    this.host = endpoint.host
                    hubDeviceId = info.deviceId
                }
                SecretStore(this@WidgetSetupActivity).putAuthHash(authHash)
                RemoteSender.invalidate()

                val usable = remotes.filterNot(IrRemote::isAirConditioner)
                status.text = getString(
                    com.tvremocon.R.string.setup_pick_remote, info.nickname, info.model, usable.size
                )
                usable.forEach(::addRemoteChoice)
                remotes.filter(IrRemote::isAirConditioner).forEach { ac ->
                    content.addView(TextView(this@WidgetSetupActivity).apply {
                        text = getString(com.tvremocon.R.string.setup_ac_unsupported, ac.nickname)
                    })
                }
            }.onFailure { e ->
                Log.w(TAG, "setup failed", e)
                status.text = getString(
                    com.tvremocon.R.string.setup_failed, e.javaClass.simpleName, e.message.orEmpty()
                )
            }
        }
    }

    private fun addRemoteChoice(remote: IrRemote) {
        content.addView(
            Button(this).apply {
                text = getString(
                    com.tvremocon.R.string.setup_remote_button,
                    remote.nickname.ifEmpty { remote.model },
                    remote.keys.size,
                )
                setOnClickListener { save(remote) }
            }
        )
    }

    private fun save(remote: IrRemote) {
        val settings = Settings(this)
        // Both grids are filled from the same key set: which one shows depends on the space
        // the launcher gives the widget, and that changes when the user resizes it.
        val slots = defaultSlots(remote, WidgetLayout.FULL) + defaultSlots(remote, WidgetLayout.COMPACT)
        settings.putWidget(appWidgetId, remote, slots)

        val missing = unresolvedFunctions(remote, WidgetLayout.FULL)
        if (missing.isNotEmpty()) {
            // Say which buttons will be blank rather than leaving unexplained gaps.
            Log.i(TAG, "unresolved on ${remote.nickname}: ${missing.joinToString { it.name }}")
        }

        TvRemoteWidget.render(this, AppWidgetManager.getInstance(this), appWidgetId)
        setResult(Activity.RESULT_OK, resultIntent())
        finish()
    }

    private fun resultIntent(): Intent =
        Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "TvRemocon"
    }
}
