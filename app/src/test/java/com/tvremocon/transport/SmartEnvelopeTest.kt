package com.tvremocon.transport

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartEnvelopeTest {

    /** Captured from the real hub in Phase 0 — an INFO key that the TV actually reacted to. */
    private val acknowledgement = JSONObject(
        """
        {"error_code":0,
         "result":{"responseData":{"result":{"responses":[
           {"method":"sendIrCmdById","error_code":0}]}}}}
        """.trimIndent()
    )

    @Test
    fun `real hub acknowledgement is recognised`() {
        SmartEnvelope.validate(acknowledgement, "sendIrCmdById")
        assertTrue(SmartEnvelope.acknowledgedSendIr(acknowledgement, "sendIrCmdById", batched = true))
    }

    @Test
    fun `child failure is found despite a zero at the top level`() {
        // The whole reason the walk exists: error_code is 0 outside and -1003 four levels in.
        val response = JSONObject(
            """
            {"error_code":0,
             "result":{"responseData":{"result":{"responses":[
               {"method":"sendIrCmdById","error_code":-1003}]}}}}
            """.trimIndent()
        )
        val failure = assertThrows(HubProtocolException::class.java) {
            SmartEnvelope.validate(response, "sendIrCmdById")
        }
        assertEquals(-1003, (failure.code as Number).toInt())
    }

    @Test
    fun `camelCase errorCode is checked too`() {
        val response = JSONObject("""{"result":{"errorCode":-1001}}""")
        assertThrows(HubProtocolException::class.java) { SmartEnvelope.validate(response, "get_device_info") }
    }

    @Test
    fun `absent null and string zero all count as success`() {
        SmartEnvelope.validate(JSONObject("""{"result":{}}"""), "m")
        SmartEnvelope.validate(JSONObject("""{"error_code":null}"""), "m")
        SmartEnvelope.validate(JSONObject("""{"error_code":"0"}"""), "m")
    }

    @Test
    fun `no error is not the same as acknowledged`() {
        // An empty result passes the error walk. Treating that as a successful IR send would
        // report "sent" for a response that says nothing at all.
        val empty = JSONObject("""{"error_code":0,"result":{}}""")
        SmartEnvelope.validate(empty, "sendIrCmdById")
        assertFalse(SmartEnvelope.acknowledgedSendIr(empty, "sendIrCmdById", batched = true))
    }

    @Test
    fun `a different method in the response is not an acknowledgement`() {
        val other = JSONObject(
            """{"result":{"responseData":{"result":{"responses":[
               {"method":"getIrReceiveStatus","error_code":0}]}}}}"""
        )
        assertFalse(SmartEnvelope.acknowledgedSendIr(other, "sendIrCmdById", batched = true))
    }

    @Test
    fun `control_child keeps the mixed-case sibling keys`() {
        val params = SmartEnvelope.controlChild("DEV1", SmartEnvelope.batched("sendIrCmdById", JSONObject().put("name", "INFO")))
        assertEquals("DEV1", params.getString("device_id"))
        // snake device_id next to camel requestData — TP-Link's spelling, not a typo here.
        assertTrue(params.has("requestData"))

        val inner = params.getJSONObject("requestData")
        assertEquals("multipleRequest", inner.getString("method"))
        val request = inner.getJSONObject("params").getJSONArray("requests").getJSONObject(0)
        assertEquals("sendIrCmdById", request.getString("method"))
        assertEquals("INFO", request.getJSONObject("params").getString("name"))
        // The raw key name is the only param; no id, no pwm.
        assertEquals(1, request.getJSONObject("params").length())
    }

    @Test
    fun `envelope carries the misspelled time field and no requestID`() {
        val envelope = SmartEnvelope.build("get_device_info", null, "uuid")
        assertTrue("request_time_milis is TP-Link's spelling", envelope.has("request_time_milis"))
        assertFalse(envelope.has("request_time_millis"))
        assertFalse("requestID is not sent over KLAP", envelope.has("requestID"))
        assertEquals("uuid", envelope.getString("terminal_uuid"))
    }

    @Test
    fun `terminal uuid is base64 of an md5 digest`() {
        val uuid = SmartEnvelope.newTerminalUuid()
        assertEquals(16, java.util.Base64.getDecoder().decode(uuid).size)
    }
}
