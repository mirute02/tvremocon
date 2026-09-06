package com.tvremocon.ui

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
import com.tvremocon.R
import com.tvremocon.data.SecretStore
import com.tvremocon.data.Settings
import com.tvremocon.ir.TapoIrHub
import com.tvremocon.net.HubDiscovery
import com.tvremocon.net.HubEndpoint
import com.tvremocon.net.LocalNetworkAccess
import com.tvremocon.transport.klap.KlapSession
import com.tvremocon.transport.klap.KlapTransport
import com.tvremocon.widget.RemoteSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hub address and credentials, configured from the app rather than while placing a widget.
 *
 * These belong to the account, not to any one widget, and typing a password into a dialog
 * that appeared because you dropped something on the home screen is the wrong moment to ask.
 * Once this is done, adding a widget only has to pick which remote it drives.
 */
class HubSetupActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var host: EditText
    private lateinit var email: EditText
    private lateinit var password: EditText
    private lateinit var authHashInput: EditText

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            status.text = getString(
                if (granted) R.string.setup_permission_granted else R.string.setup_permission_denied
            )
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = Settings(this)

        status = TextView(this).apply {
            setPadding(0, 0, 0, dp(12))
            text = getString(R.string.setup_intro)
        }
        host = EditText(this).apply {
            hint = getString(R.string.setup_hint_host)
            setText(settings.host.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT
        }
        email = EditText(this).apply {
            // Case preserved: the hash is over the exact string.
            hint = getString(R.string.setup_hint_email)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        password = EditText(this).apply {
            hint = getString(R.string.setup_hint_password)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        authHashInput = EditText(this).apply {
            hint = getString(R.string.setup_hint_authhash)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }

        setContentView(
            ScrollView(this).apply {
                addView(
                    LinearLayout(this@HubSetupActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(16), dp(16), dp(16), dp(16))
                        listOf<View>(
                            status,
                            host,
                            Button(this@HubSetupActivity).apply {
                                text = getString(R.string.setup_discover)
                                setOnClickListener { discover() }
                            },
                            email,
                            password,
                            authHashInput,
                            Button(this@HubSetupActivity).apply {
                                text = getString(R.string.setup_save_hub)
                                setOnClickListener { save() }
                            },
                        ).forEach(::addView)
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
                status.text = getString(R.string.reason_no_wifi)
                return null
            }
            null -> Unit
        }
        return LocalNetworkAccess.wifiNetwork(this)
    }

    private fun discover() {
        val network = wifiOrComplain() ?: return
        status.text = getString(R.string.setup_searching)
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { HubDiscovery.scan(network) }
            status.text = if (found.isEmpty()) {
                getString(R.string.setup_none_found)
            } else {
                host.setText(found.first().host)
                getString(R.string.setup_found, found.joinToString { it.host })
            }
        }
    }

    private fun save() {
        val network = wifiOrComplain() ?: return
        val endpoint = HubEndpoint.of(host.text.toString().trim()) ?: run {
            status.text = getString(R.string.setup_bad_host)
            return
        }

        val pasted = authHashInput.text.toString()
        val authHash = when {
            pasted.isNotBlank() -> KlapSession.parseAuthHash(pasted) ?: run {
                status.text = getString(R.string.setup_bad_authhash)
                return
            }
            else -> {
                val user = email.text.toString().trim()
                val pass = password.text.toString()
                if (user.isEmpty() || pass.isEmpty()) {
                    status.text = getString(R.string.setup_need_credentials)
                    return
                }
                // Derived once, then the password is dropped — it is never stored.
                KlapSession.authHash(user, pass)
            }
        }

        status.text = getString(R.string.setup_connecting, endpoint.toString())
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    TapoIrHub(
                        KlapTransport(endpoint, authHash, KlapTransport.httpClient(network.socketFactory))
                    ).deviceInfo()
                }
            }
            outcome.onSuccess { info ->
                if (!info.isIrCapableHub) {
                    status.text = getString(R.string.setup_not_a_hub, info.model)
                    return@onSuccess
                }
                Settings(this@HubSetupActivity).apply {
                    this.host = endpoint.host
                    hubDeviceId = info.deviceId
                }
                SecretStore(this@HubSetupActivity).putAuthHash(authHash)
                RemoteSender.invalidate()
                status.text = getString(R.string.setup_hub_saved, info.nickname, info.model)
            }.onFailure { e ->
                Log.w(TAG, "hub setup failed", e)
                status.text = getString(R.string.setup_failed, e.javaClass.simpleName, e.message.orEmpty())
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "TvRemocon"
    }
}
