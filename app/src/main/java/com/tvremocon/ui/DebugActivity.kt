package com.tvremocon.ui

import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tvremocon.ir.IrRemote
import com.tvremocon.ir.SendResult
import com.tvremocon.ir.TapoIrHub
import com.tvremocon.net.HubEndpoint
import com.tvremocon.net.LocalNetworkAccess
import com.tvremocon.transport.klap.KlapSession
import com.tvremocon.transport.klap.KlapTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manual harness for the IR layer: list the hub's remotes, then send one key per tap.
 *
 * Nothing is transmitted without a deliberate tap — no auto-send on load, no repeat, no
 * "test all keys" button. Reading the remote list is free; every key press is a real IR
 * pulse at the TV.
 *
 * Credentials are typed in and held only in memory here. Phase 3 replaces this with
 * SetupActivity and encrypted storage.
 */
class DebugActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var content: LinearLayout
    private lateinit var email: EditText
    private lateinit var password: EditText

    private var hub: TapoIrHub? = null

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            status.text = if (granted) "権限を取得しました。接続してください。" else "権限が拒否されました"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply {
            setPadding(0, 0, 0, dp(12))
            text = "ハブ $HOST に接続します"
        }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        email = EditText(this).apply {
            hint = "TP-Link のメールアドレス（大文字小文字はそのまま）"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        // Typed in rather than passed via `adb ... --es`, which would put the password in the
        // shell history and in logcat.
        password = EditText(this).apply {
            hint = "パスワード"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val connect = Button(this).apply {
            text = "接続してリモコン一覧を取得"
            setOnClickListener { connect() }
        }

        setContentView(
            ScrollView(this).apply {
                addView(
                    LinearLayout(this@DebugActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(16), dp(16), dp(16), dp(16))
                        addView(status)
                        addView(email)
                        addView(password)
                        addView(connect)
                        addView(content)
                    }
                )
            }
        )
    }

    private fun connect() {
        val blocked = LocalNetworkAccess.blockedReason(this)
        if (blocked == LocalNetworkAccess.Blocked.PERMISSION) {
            LocalNetworkAccess.permission?.let(requestPermission::launch)
            return
        }
        if (blocked == LocalNetworkAccess.Blocked.NO_WIFI) {
            status.text = "未送信: Wi-Fi に接続していません"
            return
        }

        val endpoint = HubEndpoint.of(HOST) ?: run {
            status.text = "$HOST はプライベート IPv4 ではありません"
            return
        }
        val network = LocalNetworkAccess.wifiNetwork(this) ?: return

        // Trim whitespace, keep case: the hash is over the exact string, and python-kasa
        // and the tapo crate both hash the bytes as given.
        val user = email.text.toString().trim()
        val pass = password.text.toString()
        if (user.isEmpty() || pass.isEmpty()) {
            status.text = "メールアドレスとパスワードを入力してください"
            return
        }
        start(endpoint, network, user, pass)
    }

    private fun start(
        endpoint: HubEndpoint,
        network: android.net.Network,
        user: String,
        pass: String,
    ) {
        status.text = "$endpoint に接続中…"
        content.removeAllViews()

        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val transport = KlapTransport(
                        endpoint = endpoint,
                        authHash = KlapSession.authHash(user, pass),
                        client = KlapTransport.httpClient(network.socketFactory),
                    )
                    val client = TapoIrHub(transport)
                    val info = client.deviceInfo()
                    val remotes = client.getRemotes()
                    hub = client
                    info to remotes
                }
            }

            outcome.onSuccess { (info, remotes) ->
                status.text = buildString {
                    append("${info.nickname} (${info.model}) fw ${info.firmware}\n")
                    append(if (info.isIrCapableHub) "IR ハブとして認識\n" else "IR ハブではありません\n")
                    append("リモコン ${remotes.size} 件")
                }
                remotes.forEach(::addRemote)
            }.onFailure { e ->
                Log.w(TAG, "connect failed", e)
                status.text = "接続失敗\n\n${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }

    private fun addRemote(remote: IrRemote) {
        content.addView(
            TextView(this).apply {
                text = "\n${remote.nickname} (${remote.model}) — ${remote.keys.size} キー"
                setPadding(0, dp(16), 0, dp(4))
            }
        )
        if (remote.isAirConditioner) {
            content.addView(
                TextView(this).apply { text = "エアコンは sendIrCmdByStatus 系のため対象外" }
            )
            return
        }
        remote.keys.forEach { key ->
            content.addView(
                Button(this).apply {
                    text = key.label
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    // One tap, one IR pulse. Disabled while in flight so a double tap
                    // cannot become two transmissions.
                    setOnClickListener { view -> send(view, remote, key.name, key.label) }
                }
            )
        }
    }

    private fun send(view: View, remote: IrRemote, rawKeyName: String, label: String) {
        val client = hub ?: return
        view.isEnabled = false
        status.text = "送信中: $label"

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { client.sendKey(remote.deviceId, rawKeyName) }
            Log.i(TAG, "sendKey($label) -> $result")
            status.text = describe(label, result)
            view.isEnabled = true
        }
    }

    /**
     * "No response" and "the TV was not operated" are different facts, so the unknown case
     * says what to check rather than claiming failure.
     */
    private fun describe(label: String, result: SendResult): String = when (result) {
        is SendResult.Accepted -> "ハブ受付済み: $label"
        is SendResult.Rejected -> "ハブが拒否: $label (コード ${result.code})"
        is SendResult.Unknown -> "結果不明: $label\nテレビが反応したか確認してください\n${result.detail}"
        is SendResult.NotSent -> "未送信: $label (${
            when (result.reason) {
                SendResult.Reason.NO_PERMISSION -> "ローカルネットワーク権限なし"
                SendResult.Reason.NO_WIFI -> "Wi-Fi 未接続"
                SendResult.Reason.NOT_CONFIGURED -> "未設定"
                SendResult.Reason.BAD_CREDENTIALS -> "資格情報が一致しません"
                SendResult.Reason.DEADLINE_PASSED -> "期限切れ"
            }
        })"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "TvRemocon"

        /** Phase 2 only; discovery replaces this in Phase 3. */
        const val HOST = "192.168.1.4"
    }
}
