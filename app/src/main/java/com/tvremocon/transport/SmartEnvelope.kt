package com.tvremocon.transport

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/**
 * The SMART JSON layer that rides inside KLAP.
 *
 * Error checking is the part worth reading. A failing child device still returns
 * `error_code: 0` at the top level, so a naive check reports a failed IR send as a success.
 * The real code is buried in `result.responseData.result.responses[0]`.
 *
 * Recursion mirrors tapo-ir-hub's protocol.py (MIT — see THIRD_PARTY_LICENSES).
 */
object SmartEnvelope {

    /** Generated once per transport and reused, matching what python-kasa does. */
    fun newTerminalUuid(): String {
        val uuid = UUID.randomUUID()
        val bytes = java.nio.ByteBuffer.allocate(16)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .array()
        return Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(bytes))
    }

    /**
     * Builds the outer envelope.
     *
     * `request_time_milis` is misspelled in TP-Link's own protocol; it and `terminal_uuid`
     * are optional over KLAP but accepted, and both belong on the outer envelope only —
     * never inside `requestData`. `requestID` is not sent at all on this path.
     */
    fun build(method: String, params: JSONObject?, terminalUuid: String): JSONObject =
        JSONObject()
            .put("method", method)
            .put("request_time_milis", System.currentTimeMillis())
            .put("terminal_uuid", terminalUuid)
            .apply { params?.let { put("params", it) } }

    /**
     * Wraps a child-device request. The sibling keys really are mixed-case: snake `device_id`
     * next to camel `requestData`.
     */
    fun controlChild(deviceId: String, requestData: JSONObject): JSONObject =
        JSONObject().put("device_id", deviceId).put("requestData", requestData)

    /** `multipleRequest` around a single call — the shape the hub is known to accept for IR. */
    fun batched(method: String, params: JSONObject): JSONObject =
        JSONObject()
            .put("method", "multipleRequest")
            .put(
                "params",
                JSONObject().put(
                    "requests",
                    JSONArray().put(JSONObject().put("method", method).put("params", params)),
                ),
            )

    /**
     * Walks the whole response looking for a failure code at any depth.
     *
     * Absent, JSON null, `0` and `"0"` all count as success; anything else is a failure.
     * Finding no error is necessary but **not sufficient** — an empty `{}` has no error and
     * is not an acknowledgement either. See [acknowledgedSendIr].
     */
    @Throws(HubProtocolException::class)
    fun validate(node: Any?, method: String) {
        when (node) {
            is JSONObject -> {
                for (key in ERROR_KEYS) {
                    if (node.has(key) && isFailure(node.opt(key))) {
                        throw HubProtocolException(method, node.opt(key))
                    }
                }
                for (key in arrayOf(method, "multipleRequest", "responses", "responseData", "result")) {
                    if (node.has(key)) validate(node.opt(key), method)
                }
            }

            is JSONArray -> for (i in 0 until node.length()) validate(node.opt(i), method)
        }
    }

    private fun isFailure(value: Any?): Boolean = when {
        value == null || value == JSONObject.NULL -> false
        value is Number -> value.toInt() != 0
        value is String -> value != "0"
        else -> true
    }

    /**
     * True only for the acknowledgement shape a real hub returns, measured in Phase 0:
     *
     * ```
     * {"error_code":0,"result":{"responseData":{"result":{"responses":[
     *   {"method":"sendIrCmdById","error_code":0}]}}}}
     * ```
     *
     * Anything else — including a well-formed response with no error — is "outcome unknown",
     * not success. The difference matters: the hub may have transmitted anyway.
     */
    fun acknowledgedSendIr(response: JSONObject, method: String, batched: Boolean): Boolean {
        val responseData = response.optJSONObject("result")?.optJSONObject("responseData") ?: return false
        if (!batched) {
            return responseData.has("error_code") && !isFailure(responseData.opt("error_code"))
        }
        val first = findResponses(responseData)?.optJSONObject(0) ?: return false
        return first.optString("method") == method &&
            first.has("error_code") &&
            !isFailure(first.opt("error_code"))
    }

    /** `responses` sits under a variable number of `result` wrappers depending on firmware. */
    private fun findResponses(node: JSONObject): JSONArray? =
        node.optJSONArray("responses") ?: node.optJSONObject("result")?.let(::findResponses)

    private val ERROR_KEYS = arrayOf("error_code", "errorCode")
}

/** The hub explicitly reported a failure, at some depth in the response. */
class HubProtocolException(method: String, val code: Any?) :
    IOException("$method failed with protocol error $code")
