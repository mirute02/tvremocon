package com.tvremocon.ir

import com.tvremocon.transport.HubAuthException
import com.tvremocon.transport.HubProtocolException
import com.tvremocon.transport.HubResponseLostException
import com.tvremocon.transport.HubTransport
import com.tvremocon.transport.SmartEnvelope
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Base64

/**
 * The IR side of a Tapo hub, on top of an encryption-agnostic [HubTransport].
 *
 * Reads may be retried; sends never are. That asymmetry is the whole reason [sendKey]
 * returns a [SendResult] instead of throwing: the caller has to be told the difference
 * between "not sent" and "sent, outcome unknown".
 */
class TapoIrHub(private val transport: HubTransport) {

    private val terminalUuid: String = SmartEnvelope.newTerminalUuid()

    /** Hub identity, used to confirm a rediscovered address is still the same hub. */
    suspend fun deviceInfo(): HubInfo {
        val result = call("get_device_info", null, allowRetry = true).optJSONObject("result")
            ?: throw IOException("get_device_info returned no result")
        return HubInfo(
            deviceId = result.optString("device_id"),
            model = result.optString("model"),
            deviceType = result.optString("type").ifEmpty { result.optString("device_type") },
            firmware = result.optString("fw_ver"),
            nickname = decodeBase64(result.optString("nickname", null)).orEmpty(),
        )
    }

    /**
     * Every `ir.remote` child, following pagination.
     *
     * The reference integration reads only the first page and silently truncates on hubs with
     * more children than fit in one; this walks until `start_index + size >= sum`.
     */
    suspend fun getRemotes(): List<IrRemote> {
        val remotes = mutableListOf<IrRemote>()
        var startIndex = 0
        while (true) {
            val result = call(
                "get_child_device_list",
                JSONObject().put("start_index", startIndex),
                allowRetry = true,
            ).optJSONObject("result") ?: break

            val page = result.optJSONArray("child_device_list") ?: break
            for (i in 0 until page.length()) {
                page.optJSONObject(i)?.let(::parseRemote)?.let(remotes::add)
            }

            val total = result.optInt("sum", startIndex + page.length())
            startIndex = result.optInt("start_index", startIndex) + page.length()
            if (page.length() == 0 || startIndex >= total) break
        }
        return remotes
    }

    /**
     * Sends one IR key, once.
     *
     * [rawKeyName] must be `key_list[].name` — the protocol identifier, not the display
     * label. Never throws for transport trouble; every outcome comes back as a [SendResult]
     * so the caller cannot accidentally treat a lost response as a failure.
     */
    suspend fun sendKey(deviceId: String, rawKeyName: String): SendResult {
        val params = SmartEnvelope.controlChild(
            deviceId,
            SmartEnvelope.batched(SEND_IR, JSONObject().put("name", rawKeyName)),
        )
        val envelope = SmartEnvelope.build("control_child", params, terminalUuid)

        val raw = try {
            // allowRetry = false: this is the one call that must never be replayed.
            transport.call(envelope.toString(), allowRetry = false)
        } catch (e: HubAuthException) {
            return SendResult.NotSent(SendResult.Reason.BAD_CREDENTIALS)
        } catch (e: HubResponseLostException) {
            return SendResult.Unknown(e.message ?: "no response after sending")
        } catch (e: IOException) {
            // Reaching here means the exchange failed in a way that still leaves delivery
            // undecided — the transport reports genuine pre-send problems as NotSent above.
            return SendResult.Unknown(e.message ?: e.javaClass.simpleName)
        }

        val response = try {
            JSONObject(raw)
        } catch (e: org.json.JSONException) {
            return SendResult.Unknown("response was not JSON")
        }

        return try {
            SmartEnvelope.validate(response, SEND_IR)
            if (SmartEnvelope.acknowledgedSendIr(response, SEND_IR, batched = true)) {
                SendResult.Accepted
            } else {
                // No error, but not the acknowledgement shape either. Saying "sent" here
                // would be a guess.
                SendResult.Unknown("hub did not acknowledge $SEND_IR")
            }
        } catch (e: HubProtocolException) {
            SendResult.Rejected(e.code)
        }
    }

    private fun parseRemote(child: JSONObject): IrRemote? {
        if (child.optString("category") != IrRemote.CATEGORY) return null
        val deviceId = child.optString("device_id").takeIf { it.isNotEmpty() } ?: return null
        return IrRemote(
            deviceId = deviceId,
            nickname = decodeBase64(child.optString("nickname", null)).orEmpty(),
            model = child.optString("model"),
            keys = parseKeys(child.optJSONArray("key_list")),
        )
    }

    private fun parseKeys(list: JSONArray?): List<IrKey> {
        if (list == null) return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val key = list.optJSONObject(i) ?: return@mapNotNull null
            val name = key.optString("name").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            IrKey.of(
                name = name,
                // Downloaded keys always carry an id; absent means treat it as downloaded.
                id = key.optInt("id", 0),
                decodedDisplayName = decodeBase64(key.optString("display_name", null)),
            )
        }
    }

    private suspend fun call(method: String, params: JSONObject?, allowRetry: Boolean): JSONObject {
        val raw = transport.call(SmartEnvelope.build(method, params, terminalUuid).toString(), allowRetry)
        val response = JSONObject(raw)
        SmartEnvelope.validate(response, method)
        return response
    }

    /**
     * Nicknames and display names arrive base64-encoded. Trailing NUL bytes come with the
     * territory — the hub pads fixed-width buffers — so they are stripped here.
     */
    private fun decodeBase64(encoded: String?): String? {
        if (encoded.isNullOrEmpty()) return null
        return try {
            String(Base64.getDecoder().decode(encoded), Charsets.UTF_8).trimEnd('\u0000', ' ')
        } catch (e: IllegalArgumentException) {
            encoded
        }
    }

    data class HubInfo(
        val deviceId: String,
        val model: String,
        val deviceType: String,
        val firmware: String,
        val nickname: String,
    ) {
        /**
         * Whether this is a hub that can drive IR remotes.
         *
         * Checked by device type, not model: the hub on hand reports "TH11" rather than the
         * "H110" the protocol notes assume, and other regional model strings are likely.
         */
        val isIrCapableHub: Boolean get() = deviceType == HUB_TYPE
    }

    private companion object {
        const val SEND_IR = "sendIrCmdById"
        const val HUB_TYPE = "SMART.TAPOHUB"
    }
}
