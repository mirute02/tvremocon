package com.tvremocon.ir

import com.tvremocon.transport.HubAuthException
import com.tvremocon.transport.HubResponseLostException
import com.tvremocon.transport.HubTransport
import com.tvremocon.transport.HubUnreachableException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class TapoIrHubTest {

    /** Records what was asked and replies with a canned response. */
    private class FakeTransport(
        private val responder: (JSONObject) -> String,
    ) : HubTransport {
        val sent = mutableListOf<JSONObject>()
        val allowRetryFlags = mutableListOf<Boolean>()
        var invalidated = 0

        override suspend fun call(json: String, allowRetry: Boolean): String {
            val request = JSONObject(json)
            sent += request
            allowRetryFlags += allowRetry
            return responder(request)
        }

        override fun invalidate() { invalidated++ }
    }

    private fun hub(responder: (JSONObject) -> String) = FakeTransport(responder).let { it to TapoIrHub(it) }

    // ---------------------------------------------------------------- sendKey

    @Test
    fun `send is never marked retryable and uses the raw key name`() = runBlocking {
        val (transport, hub) = hub { ACK }
        assertEquals(SendResult.Accepted, hub.sendKey("DEV1", "NAVIGATE_UP"))

        assertEquals(listOf(false), transport.allowRetryFlags)

        val params = transport.sent.single().getJSONObject("params")
        assertEquals("DEV1", params.getString("device_id"))
        val request = params.getJSONObject("requestData")
            .getJSONObject("params").getJSONArray("requests").getJSONObject(0)
        assertEquals("sendIrCmdById", request.getString("method"))
        // The protocol name, not the display label — the label for this key reads "NAVI".
        assertEquals("NAVIGATE_UP", request.getJSONObject("params").getString("name"))
    }

    @Test
    fun `lost response is unknown rather than a failure`() = runBlocking {
        val (_, hub) = hub { throw HubResponseLostException("no response after sending seq 7") }
        val result = hub.sendKey("DEV1", "VOL+")
        assertTrue(result is SendResult.Unknown)
        // Reporting this as failed would invite a second press for a command already sent.
        assertFalse(result is SendResult.NotSent)
    }

    @Test
    fun `unreachable hub is not sent, and is distinct from a lost response`() = runBlocking {
        val (_, hub) = hub { throw HubUnreachableException("no handshake response from 192.168.1.4") }
        val result = hub.sendKey("DEV1", "POWER")
        // The handshake never completed, so nothing was transmitted. This is the only failure
        // that lets the caller look for the hub elsewhere and press again — a lost response
        // must never do that, because the hub may already have acted.
        assertEquals(SendResult.NotSent(SendResult.Reason.HUB_UNREACHABLE), result)

        // Contrast: the same network trouble one step later, after the request went out.
        val (_, afterSend) = hub { throw HubResponseLostException("no response after sending seq 7") }
        assertTrue(afterSend.sendKey("DEV1", "POWER") is SendResult.Unknown)
    }

    @Test
    fun `explicit hub error is rejected with its code`() = runBlocking {
        val (_, hub) = hub {
            """{"error_code":0,"result":{"responseData":{"result":{"responses":[
               {"method":"sendIrCmdById","error_code":-1003}]}}}}"""
        }
        assertEquals(SendResult.Rejected(-1003), hub.sendKey("DEV1", "POWER"))
    }

    @Test
    fun `response without an acknowledgement is unknown even with no error`() = runBlocking {
        val (_, hub) = hub { """{"error_code":0,"result":{}}""" }
        assertTrue(hub.sendKey("DEV1", "POWER") is SendResult.Unknown)
    }

    @Test
    fun `bad credentials are reported as not sent`() = runBlocking {
        val (_, hub) = hub { throw HubAuthException("device hash mismatch") }
        assertEquals(SendResult.NotSent(SendResult.Reason.BAD_CREDENTIALS), hub.sendKey("DEV1", "POWER"))
    }

    @Test
    fun `malformed json does not crash the send path`() = runBlocking {
        val (_, hub) = hub { "not json at all" }
        assertTrue(hub.sendKey("DEV1", "POWER") is SendResult.Unknown)
    }

    // ---------------------------------------------------------------- getRemotes

    @Test
    fun `pagination is followed to the end`() = runBlocking {
        val (transport, hub) = hub { request ->
            when (request.getJSONObject("params").getInt("start_index")) {
                0 -> page(startIndex = 0, sum = 3, ids = listOf("A", "B"))
                2 -> page(startIndex = 2, sum = 3, ids = listOf("C"))
                else -> error("unexpected page")
            }
        }
        assertEquals(listOf("A", "B", "C"), hub.getRemotes().map { it.deviceId })
        assertEquals(2, transport.sent.size)
        // Reads may be retried; only sends may not.
        assertTrue(transport.allowRetryFlags.all { it })
    }

    @Test
    fun `non ir children are dropped`() = runBlocking {
        val (_, hub) = hub {
            """
            {"error_code":0,"result":{"start_index":0,"sum":2,"child_device_list":[
              {"device_id":"S1","category":"subg.trigger.temp-hmdt-sensor","model":"T315"},
              {"device_id":"R1","category":"ir.remote","model":"TV","nickname":"VFY=","key_list":[]}
            ]}}
            """.trimIndent()
        }
        assertEquals(listOf("R1"), hub.getRemotes().map { it.deviceId })
    }

    @Test
    fun `air conditioners are surfaced but flagged`() = runBlocking {
        val (_, hub) = hub {
            """
            {"error_code":0,"result":{"start_index":0,"sum":1,"child_device_list":[
              {"device_id":"AC1","category":"ir.remote","model":"AC","key_list":[]}
            ]}}
            """.trimIndent()
        }
        // Kept in the list so setup can explain why it is unusable, rather than vanishing.
        assertTrue(hub.getRemotes().single().isAirConditioner)
    }

    @Test
    fun `labels come from the protocol name for downloaded keys`() = runBlocking {
        val (_, hub) = hub {
            // display_name values as the real hub sends them: truncated to four bytes,
            // NUL-padded, and identical for all four arrow keys.
            """
            {"error_code":0,"result":{"start_index":0,"sum":1,"child_device_list":[
              {"device_id":"R1","category":"ir.remote","model":"TV","key_list":[
                {"name":"POWER","id":1,"display_name":"UE9XRQ=="},
                {"name":"NAVIGATE_UP","id":46,"display_name":"TkFWSQ=="},
                {"name":"NAVIGATE_DOWN","id":47,"display_name":"TkFWSQ=="},
                {"name":"OK","id":42,"display_name":"T0sAAA=="},
                {"name":"PeF1691","id":-1,"display_name":"6Zai56+A"}
              ]}
            ]}}
            """.trimIndent()
        }
        val keys = hub.getRemotes().single().keys
        assertEquals(listOf("POWER", "NAVIGATE_UP", "NAVIGATE_DOWN", "OK", "関節"), keys.map { it.label })
        // Without this rule both arrow keys would read "NAVI" and be indistinguishable.
        assertEquals(keys[1].label != keys[2].label, true)
        // NUL padding must not leak into the label of a custom key either.
        assertFalse(keys.any { it.label.contains('\u0000') })
    }

    @Test
    fun `hub is identified by device type not model`() = runBlocking {
        val (_, hub) = hub {
            // The hub on hand reports TH11, not the H110 the protocol notes assume.
            """{"error_code":0,"result":{"device_id":"HUB1","model":"TH11",
               "type":"SMART.TAPOHUB","fw_ver":"1.5.4","nickname":"VEgxMV9YWFhY"}}"""
        }
        val info = hub.deviceInfo()
        assertEquals("TH11", info.model)
        assertTrue(info.isIrCapableHub)
        assertEquals("TH11_XXXX", info.nickname)
    }

    @Test
    fun `a non hub device is not treated as one`() = runBlocking {
        val (_, hub) = hub {
            """{"error_code":0,"result":{"device_id":"P1","model":"P110","type":"SMART.TAPOPLUG"}}"""
        }
        assertFalse(hub.deviceInfo().isIrCapableHub)
    }

    @Test
    fun `read errors surface as exceptions unlike sends`() {
        val (_, hub) = hub { throw IOException("connection reset") }
        // getRemotes has no "outcome unknown" problem — nothing was changed by asking.
        org.junit.Assert.assertThrows(IOException::class.java) { runBlocking { hub.getRemotes() } }
    }

    private fun page(startIndex: Int, sum: Int, ids: List<String>): String {
        val children = ids.joinToString(",") {
            """{"device_id":"$it","category":"ir.remote","model":"TV","key_list":[]}"""
        }
        return """{"error_code":0,"result":{"start_index":$startIndex,"sum":$sum,"child_device_list":[$children]}}"""
    }

    private companion object {
        const val ACK =
            """{"error_code":0,"result":{"responseData":{"result":{"responses":[{"method":"sendIrCmdById","error_code":0}]}}}}"""
    }
}
