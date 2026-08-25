package com.revio.server.routes

import com.revio.server.features.auth.JwtService
import com.revio.server.features.auth.RefreshTokenGenerator
import com.revio.server.features.auth.session.AuthSessionDAO
import com.revio.server.features.auth.session.SessionScope
import com.revio.server.features.auth.session.SessionService
import com.revio.server.features.notification.UserDeviceDAO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import testutils.CommentTestSeed
import testutils.TestDatabaseFactory
import testutils.TestEnv
import testutils.setTestEnv
import testutils.stopKoinSafely
import testutils.testDeviceModule
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeviceRoutesTest {

    private val jwt = JwtService(
        jwtSecret = TestEnv.JWT_SECRET,
        jwtIssuer = TestEnv.JWT_ISSUER,
        jwtAudience = TestEnv.JWT_AUDIENCE,
    )

    private val userDeviceDao = UserDeviceDAO()

    @BeforeAll
    fun setup() {
        setTestEnv()
        TestDatabaseFactory.start()
    }

    @AfterAll
    fun tearDown() {
        TestDatabaseFactory.stop()
    }

    @BeforeEach
    fun clean() {
        TestDatabaseFactory.cleanDatabase()
        stopKoinSafely()
    }

    private fun deviceTest(block: suspend ApplicationTestBuilder.(io.ktor.client.HttpClient) -> Unit) =
        testApplication {
            application { testDeviceModule() }
            val client = createClient {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; isLenient = true })
                }
            }
            block(client)
        }

    private suspend fun tokenFor(authId: UUID, userId: UUID?, email: String = "user@example.com"): String {
        val (session) = SessionService(AuthSessionDAO(), RefreshTokenGenerator()).createSession(
            credentialId = authId,
            scope = if (userId != null) SessionScope.FULL else SessionScope.ONBOARDING,
            userId = userId,
            deviceId = null,
            deviceName = null,
            userAgent = null,
            ip = null,
        )
        return jwt.generateAccessToken(session, authId, email, userId)
    }

    private fun registerBody(
        deviceId: String = "device-1",
        fcmToken: String = "token-a",
        firebaseProject: String = "DEBUG",
        platform: String = "ANDROID",
        appVersion: String = "1.0.0",
        timezone: String? = "Europe/Bucharest",
        locale: String? = "ro-RO",
    ) = buildString {
        append("{")
        append("\"deviceId\":\"$deviceId\",")
        append("\"fcmToken\":\"$fcmToken\",")
        append("\"firebaseProject\":\"$firebaseProject\",")
        append("\"platform\":\"$platform\",")
        append("\"appVersion\":\"$appVersion\"")
        if (timezone != null) append(",\"timezone\":\"$timezone\"")
        if (locale != null) append(",\"locale\":\"$locale\"")
        append("}")
    }

    @Test
    fun `POST devices without JWT returns 401`() = deviceTest { client ->
        val resp = client.post("/api/devices") {
            contentType(ContentType.Application.Json)
            setBody(registerBody())
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `POST devices registers a new device`() = deviceTest { client ->
        val alice = CommentTestSeed.seedUser(username = "alice")
        val token = tokenFor(alice.authId, alice.userId, alice.email)

        val resp = client.post("/api/devices") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(registerBody())
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("device-1", body["deviceId"]?.jsonPrimitive?.content)
        assertEquals("DEBUG", body["firebaseProject"]?.jsonPrimitive?.content)
        assertEquals(true, body["isActive"]?.jsonPrimitive?.content?.toBoolean() ?: true)

        val stored = runBlocking { userDeviceDao.findByUserAndDevice(alice.userId, "device-1") }
        assertNotNull(stored)
        assertEquals("token-a", stored!!.fcmToken)
    }

    @Test
    fun `POST devices twice for the same device upserts a single row`() = deviceTest { client ->
        val alice = CommentTestSeed.seedUser(username = "alice")
        val token = tokenFor(alice.authId, alice.userId, alice.email)

        val first = client.post("/api/devices") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(registerBody(fcmToken = "token-a", appVersion = "1.0.0"))
        }
        val second = client.post("/api/devices") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(registerBody(fcmToken = "token-a-rotated", appVersion = "1.1.0"))
        }

        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(HttpStatusCode.OK, second.status)

        val firstId = Json.parseToJsonElement(first.bodyAsText()).jsonObject["id"]?.jsonPrimitive?.content
        val secondId = Json.parseToJsonElement(second.bodyAsText()).jsonObject["id"]?.jsonPrimitive?.content
        assertEquals(firstId, secondId)

        val stored = runBlocking { userDeviceDao.findByUserAndDevice(alice.userId, "device-1") }
        assertEquals("token-a-rotated", stored?.fcmToken)
        assertEquals("1.1.0", stored?.appVersion)
    }

    @Test
    fun `POST devices with an invalid timezone returns 400 and does not persist the device`() = deviceTest { client ->
        val alice = CommentTestSeed.seedUser(username = "alice")
        val token = tokenFor(alice.authId, alice.userId, alice.email)

        val resp = client.post("/api/devices") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(registerBody(timezone = "Europe/Bucuresti"))
        }

        assertEquals(HttpStatusCode.BadRequest, resp.status)
        val stored = runBlocking { userDeviceDao.findByUserAndDevice(alice.userId, "device-1") }
        assertEquals(null, stored)
    }

    @Test
    fun `POST devices without a timezone still registers the device`() = deviceTest { client ->
        val alice = CommentTestSeed.seedUser(username = "alice")
        val token = tokenFor(alice.authId, alice.userId, alice.email)

        val resp = client.post("/api/devices") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(registerBody(timezone = null))
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        val stored = runBlocking { userDeviceDao.findByUserAndDevice(alice.userId, "device-1") }
        assertEquals(null, stored?.timezone)
    }

    @Test
    fun `DELETE devices without JWT returns 401`() = deviceTest { client ->
        val resp = client.delete("/api/devices/device-1")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `DELETE devices deactivates a registered device`() = deviceTest { client ->
        val alice = CommentTestSeed.seedUser(username = "alice")
        val token = tokenFor(alice.authId, alice.userId, alice.email)
        runBlocking {
            userDeviceDao.registerDevice(
                userId = alice.userId,
                deviceId = "device-1",
                fcmToken = "token-a",
                firebaseProject = com.revio.server.features.notification.FirebaseProject.DEBUG,
                platform = com.revio.server.features.notification.DevicePlatform.ANDROID,
                appVersion = "1.0.0",
                timezone = null,
                locale = null,
            )
        }

        val resp = client.delete("/api/devices/device-1") { bearerAuth(token) }

        assertEquals(HttpStatusCode.NoContent, resp.status)
        val stored = runBlocking { userDeviceDao.findByUserAndDevice(alice.userId, "device-1") }
        assertEquals(false, stored?.isActive)
        assertEquals(null, stored?.fcmToken)
    }

    @Test
    fun `DELETE devices for an unknown device returns 404`() = deviceTest { client ->
        val alice = CommentTestSeed.seedUser(username = "alice")
        val token = tokenFor(alice.authId, alice.userId, alice.email)

        val resp = client.delete("/api/devices/never-registered") { bearerAuth(token) }

        assertEquals(HttpStatusCode.NotFound, resp.status)
    }
}
