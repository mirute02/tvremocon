package com.tvremocon

import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tvremocon.databinding.ActivityMainBinding
import com.tvremocon.net.HubEndpoint
import com.tvremocon.net.LocalNetworkAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Phase 1b reachability check: can the *installed APK* reach the hub?
 *
 * Deliberately stops at handshake1, which needs no credentials and sends no IR. Succeeding
 * here is a separate pass condition from the Termux probe succeeding — the probe runs as
 * Termux and inherits its permissions, this runs as its own app under Android 17's
 * local-network gate.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) probe() else show("権限が拒否されました。設定 → アプリ → TV Remocon から許可してください。")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.textView.text = "ハブ $HOST への到達を確認します"
        binding.textView.setOnClickListener { start() }
        start()
    }

    private fun start() {
        val permission = LocalNetworkAccess.permission
        if (permission != null && !LocalNetworkAccess.hasPermission(this)) {
            show("ローカルネットワークへのアクセスを許可してください…")
            requestPermission.launch(permission)
            return
        }
        probe()
    }

    private fun probe() {
        val endpoint = HubEndpoint.of(HOST)
        if (endpoint == null) {
            show("$HOST はプライベート IPv4 ではありません")
            return
        }
        val network = LocalNetworkAccess.wifiNetwork(this)
        if (network == null) {
            show("未送信: Wi-Fi に接続していません")
            return
        }

        show("$endpoint に handshake1 を送信中…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                // Pin to the Wi-Fi network: Android otherwise routes over cellular when it
                // thinks Wi-Fi has no internet, and LAN addresses stop resolving.
                val client = OkHttpClient.Builder()
                    .socketFactory(network.socketFactory)
                    .retryOnConnectionFailure(false)
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .connectTimeout(2, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .writeTimeout(5, TimeUnit.SECONDS)
                    .build()
                val seed = ByteArray(16).also { SecureRandom().nextBytes(it) }
                val request = Request.Builder()
                    .url(endpoint.url(HubEndpoint.Path.HANDSHAKE1))
                    .post(seed.toRequestBody(null))
                    .build()
                runCatching {
                    client.newCall(request).execute().use { response ->
                        val bytes = response.body?.bytes()?.size ?: 0
                        // Log the cookie's presence, never its value.
                        val cookie = response.headers("Set-Cookie").any { it.startsWith("TP_SESSIONID=") }
                        Triple(response.code, bytes, cookie)
                    }
                }
            }

            result.onSuccess { (code, bytes, cookie) ->
                Log.i(TAG, "handshake1: HTTP $code, $bytes bytes, session cookie=$cookie")
                if (code == 200 && bytes == 48 && cookie) {
                    show("到達成功\n\nHTTP $code / 48 バイト / セッション Cookie あり\nハブは KLAP で応答しています。")
                } else {
                    show("到達したが応答が想定外\n\nHTTP $code / $bytes バイト / Cookie=$cookie")
                }
            }.onFailure { e ->
                Log.w(TAG, "handshake1 failed", e)
                show("到達できません\n\n${e.javaClass.simpleName}: ${e.message}\n\nタップで再試行")
            }
        }
    }

    private fun show(text: String) {
        binding.textView.text = text
    }

    private companion object {
        const val TAG = "TvRemocon"

        /** Phase 1b only. Discovery replaces this in Phase 3. */
        const val HOST = "192.168.1.4"
    }
}
