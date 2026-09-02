package service

import com.revio.server.features.notification.FcmSendResult
import com.revio.server.features.notification.FcmTerminalReason
import com.revio.server.features.notification.FirebaseProject
import com.revio.server.features.notification.classifyResponse
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Coverage for [classifyResponse] — the pure part of PushDispatchService (plan §18, step 3.4)
 * that maps a raw FCM HTTP v1 response onto the status vocabulary from plan §14. The actual HTTP
 * call is exercised manually against a real device/project per the step's acceptance criteria,
 * not here.
 */
class PushDispatchServiceTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a 200 response with a message name is Accepted`() {
        val result = classifyResponse(
            json = json,
            project = FirebaseProject.DEBUG,
            isSuccess = true,
            statusCode = 200,
            retryAfterHeader = null,
            responseText = """{"name":"projects/revio-debug-47037/messages/0:1234567890"}""",
        )
        assertEquals(FcmSendResult.Accepted("projects/revio-debug-47037/messages/0:1234567890"), result)
    }

    @Test
    fun `a 200 response with an unparseable body is Unknown`() {
        val result = classifyResponse(
            json = json,
            project = FirebaseProject.DEBUG,
            isSuccess = true,
            statusCode = 200,
            retryAfterHeader = null,
            responseText = """not json at all""",
        )
        assertEquals(FcmSendResult.Unknown(200, "not json at all"), result)
    }

    @Test
    fun `a 404 with errorCode UNREGISTERED is Terminal UNREGISTERED`() {
        val body = """
            {"error":{"code":404,"message":"Requested entity was not found.","status":"NOT_FOUND",
             "details":[{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError","errorCode":"UNREGISTERED"}]}}
        """.trimIndent()
        val result = classifyResponse(json = json, project = FirebaseProject.DEBUG, isSuccess = false, statusCode = 404, retryAfterHeader = null, responseText = body)
        assertEquals(FcmSendResult.Terminal(FcmTerminalReason.UNREGISTERED), result)
    }

    @Test
    fun `a 400 with errorCode INVALID_ARGUMENT is Terminal INVALID_ARGUMENT`() {
        val body = """
            {"error":{"code":400,"message":"Invalid registration token.","status":"INVALID_ARGUMENT",
             "details":[{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError","errorCode":"INVALID_ARGUMENT"}]}}
        """.trimIndent()
        val result = classifyResponse(json = json, project = FirebaseProject.DEBUG, isSuccess = false, statusCode = 400, retryAfterHeader = null, responseText = body)
        assertEquals(FcmSendResult.Terminal(FcmTerminalReason.INVALID_ARGUMENT), result)
    }

    @Test
    fun `a 404 without an FCM errorCode still falls back to Terminal UNREGISTERED by HTTP status`() {
        val body = """{"error":{"code":404,"message":"Not found","status":"NOT_FOUND","details":[]}}"""
        val result = classifyResponse(json = json, project = FirebaseProject.DEBUG, isSuccess = false, statusCode = 404, retryAfterHeader = null, responseText = body)
        assertEquals(FcmSendResult.Terminal(FcmTerminalReason.UNREGISTERED), result)
    }

    @Test
    fun `a 400 without an FCM errorCode still falls back to Terminal INVALID_ARGUMENT by HTTP status`() {
        val body = """{"error":{"code":400,"message":"Bad request","status":"INVALID_ARGUMENT","details":[]}}"""
        val result = classifyResponse(json = json, project = FirebaseProject.DEBUG, isSuccess = false, statusCode = 400, retryAfterHeader = null, responseText = body)
        assertEquals(FcmSendResult.Terminal(FcmTerminalReason.INVALID_ARGUMENT), result)
    }

    @Test
    fun `a 429 is Retriable and carries the Retry-After header when present`() {
        val body = """{"error":{"code":429,"message":"Quota exceeded","status":"RESOURCE_EXHAUSTED","details":[]}}"""
        val result = classifyResponse(json = json, project = FirebaseProject.DEBUG, isSuccess = false, statusCode = 429, retryAfterHeader = "30", responseText = body)
        assertEquals(FcmSendResult.Retriable(429, 30L), result)
    }

    @Test
    fun `a 500 is Retriable with no Retry-After header`() {
        val body = """{"error":{"code":500,"message":"Internal error","status":"INTERNAL","details":[]}}"""
        val result = classifyResponse(json = json, project = FirebaseProject.DEBUG, isSuccess = false, statusCode = 500, retryAfterHeader = null, responseText = body)
        assertEquals(FcmSendResult.Retriable(500), result)
        assertNull((result as FcmSendResult.Retriable).retryAfterSeconds)
    }

    @Test
    fun `a 503 is Retriable`() {
        val body = """{"error":{"code":503,"message":"Server unavailable","status":"UNAVAILABLE","details":[]}}"""
        val result = classifyResponse(json = json, project = FirebaseProject.DEBUG, isSuccess = false, statusCode = 503, retryAfterHeader = null, responseText = body)
        assertEquals(FcmSendResult.Retriable(503), result)
    }

    @Test
    fun `an unrecognized error status is Unknown, body preserved as-is`() {
        val body = """{"error":{"code":418,"message":"I'm a teapot","status":"UNKNOWN","details":[]}}"""
        val result = classifyResponse(json = json, project = FirebaseProject.DEBUG, isSuccess = false, statusCode = 418, retryAfterHeader = null, responseText = body)
        assertEquals(FcmSendResult.Unknown(418, body), result)
    }

    // ── step 4.5 — 401/403 are classified as Unconfigured, not Unknown ────────────────────────

    @Test
    fun `a 401 is classified as Unconfigured for the calling project, not Unknown`() {
        val body = """{"error":{"code":401,"message":"Request had invalid authentication credentials","status":"UNAUTHENTICATED","details":[]}}"""
        val result = classifyResponse(json = json, project = FirebaseProject.RELEASE, isSuccess = false, statusCode = 401, retryAfterHeader = null, responseText = body)
        assertEquals(FcmSendResult.Unconfigured(FirebaseProject.RELEASE), result)
    }

    @Test
    fun `a 403 is classified as Unconfigured for the calling project, not Unknown`() {
        val body = """{"error":{"code":403,"message":"Sender ID mismatch","status":"PERMISSION_DENIED","details":[]}}"""
        val result = classifyResponse(json = json, project = FirebaseProject.DEBUG, isSuccess = false, statusCode = 403, retryAfterHeader = null, responseText = body)
        assertEquals(FcmSendResult.Unconfigured(FirebaseProject.DEBUG), result)
    }

    @Test
    fun `a completely unparseable error body still classifies by HTTP status alone`() {
        val result = classifyResponse(json = json, project = FirebaseProject.DEBUG, isSuccess = false, statusCode = 503, retryAfterHeader = null, responseText = "<html>gateway timeout</html>")
        assertEquals(FcmSendResult.Retriable(503), result)
    }
}
