package com.revio.server.routes

import com.revio.server.config.configureHealth
import com.revio.server.config.configureSerialization
import com.revio.server.features.notification.FirebaseProject
import com.revio.server.features.notification.IFcmCredentialsProvider
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin

/**
 * Verifies the 1.9 healthcheck route in isolation (fake [IFcmCredentialsProvider], no real Google
 * credentials or network calls) — it's a thin, self-contained test setup rather than reusing
 * TestApplicationFactory.kt's shared helpers, since /health/fcm needs no JWT/DB wiring at all.
 */
class HealthFcmRouteTest {

    private class FakeFcmCredentialsProvider(
        private val healthyProjects: Set<FirebaseProject>,
    ) : IFcmCredentialsProvider {
        override suspend fun getAccessToken(project: FirebaseProject): String? =
            if (project in healthyProjects) "fake-token-$project" else null
    }

    private fun healthTest(
        provider: IFcmCredentialsProvider,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application {
            install(Koin) { modules(module { single<IFcmCredentialsProvider> { provider } }) }
            configureSerialization()
            configureHealth()
        }
        block()
    }

    @Test
    fun `both projects healthy reports both as healthy`() = healthTest(
        FakeFcmCredentialsProvider(setOf(FirebaseProject.DEBUG, FirebaseProject.RELEASE)),
    ) {
        val resp = client.get("/health/fcm")

        assertEquals(HttpStatusCode.OK, resp.status)
        val fcm = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["fcm"]!!.jsonObject
        assertEquals(true, fcm["DEBUG"]?.jsonObject?.get("healthy")?.jsonPrimitive?.boolean)
        assertEquals(true, fcm["RELEASE"]?.jsonObject?.get("healthy")?.jsonPrimitive?.boolean)
    }

    @Test
    fun `one project unconfigured reports only that one as unhealthy, with 200 overall`() = healthTest(
        FakeFcmCredentialsProvider(setOf(FirebaseProject.DEBUG)),
    ) {
        val resp = client.get("/health/fcm")

        // Always 200 — FCM isn't used for sending yet, so it must never gate the app's own
        // liveness/readiness signal (see the route's own comment in Health.kt).
        assertEquals(HttpStatusCode.OK, resp.status)
        val fcm = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["fcm"]!!.jsonObject
        assertEquals(true, fcm["DEBUG"]?.jsonObject?.get("healthy")?.jsonPrimitive?.boolean)
        assertEquals(false, fcm["RELEASE"]?.jsonObject?.get("healthy")?.jsonPrimitive?.boolean)
    }

    @Test
    fun `neither project configured still returns 200 with both unhealthy`() = healthTest(
        FakeFcmCredentialsProvider(emptySet()),
    ) {
        val resp = client.get("/health/fcm")

        assertEquals(HttpStatusCode.OK, resp.status)
        val fcm = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["fcm"]!!.jsonObject
        assertEquals(false, fcm["DEBUG"]?.jsonObject?.get("healthy")?.jsonPrimitive?.boolean)
        assertEquals(false, fcm["RELEASE"]?.jsonObject?.get("healthy")?.jsonPrimitive?.boolean)
    }

    @Test
    fun `response never includes a token or credential value, only booleans`() = healthTest(
        FakeFcmCredentialsProvider(setOf(FirebaseProject.DEBUG, FirebaseProject.RELEASE)),
    ) {
        val resp = client.get("/health/fcm")

        val raw = resp.bodyAsText()
        assertEquals(false, raw.contains("fake-token"))
    }
}
