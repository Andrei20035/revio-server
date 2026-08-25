package com.revio.server.routes

import com.revio.server.config.configureSerialization
import com.revio.server.features.notification.FcmPriority
import com.revio.server.features.notification.FcmSendResult
import com.revio.server.features.notification.FcmTerminalReason
import com.revio.server.features.notification.FirebaseProject
import com.revio.server.features.notification.IPushDispatchService
import com.revio.server.features.notification.pushDispatchRoutes
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin

/**
 * Verifies the 3.4 manual test-send route in isolation (fake [IPushDispatchService], no real FCM
 * call) — same self-contained style as HealthFcmRouteTest.kt, no JWT/DB wiring needed.
 */
class PushDispatchRoutesTest {

    private class FakePushDispatchService(private val result: FcmSendResult) : IPushDispatchService {
        var lastCall: Triple<FirebaseProject, String, String>? = null

        override suspend fun send(
            project: FirebaseProject,
            fcmToken: String,
            title: String,
            body: String,
            data: Map<String, String>,
            priority: FcmPriority,
            ttlSeconds: Long?,
        ): FcmSendResult {
            lastCall = Triple(project, fcmToken, title)
            return result
        }
    }

    private fun routeTest(
        service: IPushDispatchService,
        adminToken: String? = "test-admin-token",
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application {
            install(Koin) { modules(module { single<IPushDispatchService> { service } }) }
            configureSerialization()
            routing { pushDispatchRoutes(adminTokenProvider = { adminToken }) }
        }
        block()
    }

    private val requestBody = """
        {"fcmToken":"device-token-123","firebaseProject":"DEBUG","title":"Test","body":"Hello"}
    """.trimIndent()

    @Test
    fun `missing admin token header is rejected with 401`() = routeTest(FakePushDispatchService(FcmSendResult.Accepted("msg-1"))) {
        val resp = client.post("/internal/notifications/test-send") {
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `wrong admin token header is rejected with 401`() = routeTest(FakePushDispatchService(FcmSendResult.Accepted("msg-1"))) {
        val resp = client.post("/internal/notifications/test-send") {
            header("X-Admin-Token", "wrong-token")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `unconfigured admin token env rejects every request`() = routeTest(FakePushDispatchService(FcmSendResult.Accepted("msg-1")), adminToken = null) {
        val resp = client.post("/internal/notifications/test-send") {
            header("X-Admin-Token", "anything")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `valid admin token forwards the request and returns an ACCEPTED outcome`() {
        val service = FakePushDispatchService(FcmSendResult.Accepted("projects/revio-debug-47037/messages/0:1"))
        routeTest(service) {
            val resp = client.post("/internal/notifications/test-send") {
                header("X-Admin-Token", "test-admin-token")
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            val json = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("ACCEPTED", json["outcome"]?.jsonPrimitive?.content)
            assertEquals("projects/revio-debug-47037/messages/0:1", json["fcmMessageId"]?.jsonPrimitive?.content)
            assertEquals(FirebaseProject.DEBUG, service.lastCall?.first)
            assertEquals("device-token-123", service.lastCall?.second)
        }
    }

    @Test
    fun `a TERMINAL result is surfaced with its reason`() = routeTest(
        FakePushDispatchService(FcmSendResult.Terminal(FcmTerminalReason.UNREGISTERED)),
    ) {
        val resp = client.post("/internal/notifications/test-send") {
            header("X-Admin-Token", "test-admin-token")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val json = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("TERMINAL", json["outcome"]?.jsonPrimitive?.content)
        assertEquals("UNREGISTERED", json["terminalReason"]?.jsonPrimitive?.content)
    }
}
