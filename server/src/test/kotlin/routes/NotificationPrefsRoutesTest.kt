package com.revio.server.routes

import com.revio.server.features.auth.JwtService
import com.revio.server.features.auth.RefreshTokenGenerator
import com.revio.server.features.auth.session.AuthSessionDAO
import com.revio.server.features.auth.session.SessionScope
import com.revio.server.features.auth.session.SessionService
import com.revio.server.features.notification.UserNotificationPrefsDAO
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
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import testutils.CommentTestSeed
import testutils.TestDatabaseFactory
import testutils.TestEnv
import testutils.setTestEnv
import testutils.stopKoinSafely
import testutils.testNotificationPrefsModule
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationPrefsRoutesTest {

    private val jwt = JwtService(
        jwtSecret = TestEnv.JWT_SECRET,
        jwtIssuer = TestEnv.JWT_ISSUER,
        jwtAudience = TestEnv.JWT_AUDIENCE,
    )

    private val prefsDao = UserNotificationPrefsDAO()

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

    private fun prefsTest(block: suspend ApplicationTestBuilder.(io.ktor.client.HttpClient) -> Unit) =
        testApplication {
            application { testNotificationPrefsModule() }
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

    @Test
    fun `GET notification-preferences without JWT returns 401`() = prefsTest { client ->
        val resp = client.get("/api/users/me/notification-preferences")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `GET notification-preferences for a user with no row returns the defaults`() = prefsTest { client ->
        val alice = CommentTestSeed.seedUser(username = "alice")
        val token = tokenFor(alice.authId, alice.userId, alice.email)

        val resp = client.get("/api/users/me/notification-preferences") { bearerAuth(token) }

        assertEquals(HttpStatusCode.OK, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals(true, body["likesEnabled"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(true, body["commentsEnabled"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(true, body["discoveryEnabled"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(true, body["remindersEnabled"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals("00:00", body["quietStart"]?.jsonPrimitive?.content)
        assertEquals("08:00", body["quietEnd"]?.jsonPrimitive?.content)
    }

    @Test
    fun `PUT notification-preferences without JWT returns 401`() = prefsTest { client ->
        val resp = client.put("/api/users/me/notification-preferences") {
            contentType(ContentType.Application.Json)
            setBody("""{"discoveryEnabled":false}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `PUT notification-preferences partial update does not reset other fields`() = prefsTest { client ->
        val alice = CommentTestSeed.seedUser(username = "alice")
        val token = tokenFor(alice.authId, alice.userId, alice.email)

        val first = client.put("/api/users/me/notification-preferences") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"discoveryEnabled":false}""")
        }
        assertEquals(HttpStatusCode.OK, first.status)

        val second = client.put("/api/users/me/notification-preferences") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"likesEnabled":false}""")
        }
        assertEquals(HttpStatusCode.OK, second.status)

        val secondBody = Json.parseToJsonElement(second.bodyAsText()).jsonObject
        assertEquals(false, secondBody["likesEnabled"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(false, secondBody["discoveryEnabled"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(true, secondBody["commentsEnabled"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(true, secondBody["remindersEnabled"]?.jsonPrimitive?.content?.toBoolean())

        val stored = runBlocking { prefsDao.get(alice.userId) }
        assertEquals(false, stored.likesEnabled)
        assertEquals(false, stored.discoveryEnabled)
        assertEquals(true, stored.commentsEnabled)
        assertEquals(true, stored.remindersEnabled)
    }

    @Test
    fun `PUT notification-preferences with quietStart equal to quietEnd returns 400`() = prefsTest { client ->
        val alice = CommentTestSeed.seedUser(username = "alice")
        val token = tokenFor(alice.authId, alice.userId, alice.email)

        val resp = client.put("/api/users/me/notification-preferences") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"quietStart":"08:00","quietEnd":"08:00"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, resp.status)
        val stored = runBlocking { prefsDao.get(alice.userId) }
        // Untouched — the default window is unaffected by the rejected update.
        assertEquals("00:00", stored.quietStart.toString().take(5))
        assertEquals("08:00", stored.quietEnd.toString().take(5))
    }

    @Test
    fun `PUT notification-preferences with quietStart after quietEnd returns 400`() = prefsTest { client ->
        val alice = CommentTestSeed.seedUser(username = "alice")
        val token = tokenFor(alice.authId, alice.userId, alice.email)

        val resp = client.put("/api/users/me/notification-preferences") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"quietStart":"09:00","quietEnd":"08:00"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `PUT notification-preferences updating only quietEnd is validated against the current quietStart`() = prefsTest { client ->
        val alice = CommentTestSeed.seedUser(username = "alice")
        val token = tokenFor(alice.authId, alice.userId, alice.email)

        // Default quietStart is 00:00, so a new quietEnd of 00:00 collides with it even though
        // quietStart itself isn't part of this request.
        val resp = client.put("/api/users/me/notification-preferences") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"quietEnd":"00:00"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `PUT notification-preferences can change quiet hours to a valid non-wrapping window`() = prefsTest { client ->
        val alice = CommentTestSeed.seedUser(username = "alice")
        val token = tokenFor(alice.authId, alice.userId, alice.email)

        // The window is non-circular (D5) — it must not wrap midnight, so both parts move
        // together to a still-valid, still-non-wrapping pair.
        val resp = client.put("/api/users/me/notification-preferences") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"quietStart":"00:30","quietEnd":"07:00"}""")
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("00:30", body["quietStart"]?.jsonPrimitive?.content)
        assertEquals("07:00", body["quietEnd"]?.jsonPrimitive?.content)
    }
}
