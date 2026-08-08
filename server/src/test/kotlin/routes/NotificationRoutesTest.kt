package com.revio.server.routes

import com.revio.server.features.auth.JwtService
import com.revio.server.features.auth.RefreshTokenGenerator
import com.revio.server.features.auth.session.AuthSessionDAO
import com.revio.server.features.auth.session.SessionScope
import com.revio.server.features.auth.session.SessionService
import com.revio.server.features.notification.NotificationDAO
import com.revio.server.features.notification.NotificationType
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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
import testutils.testNotificationModule
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationRoutesTest {

    private val jwt = JwtService(
        jwtSecret = TestEnv.JWT_SECRET,
        jwtIssuer = TestEnv.JWT_ISSUER,
        jwtAudience = TestEnv.JWT_AUDIENCE,
    )

    private val notificationDao = NotificationDAO()

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

    private fun notificationTest(block: suspend ApplicationTestBuilder.(io.ktor.client.HttpClient) -> Unit) =
        testApplication {
            application { testNotificationModule() }
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
    fun `GET notifications without JWT returns 401`() = notificationTest { client ->
        val resp = client.get("/api/notifications")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `GET notifications only returns the caller's own notifications`() = notificationTest { client ->
        val alice = CommentTestSeed.seedUser(username = "alice")
        val bob = CommentTestSeed.seedUser(username = "bob")

        runBlocking {
            notificationDao.insert(alice.userId, NotificationType.POST_REMOVED, "Post removed", "alice body", false)
            notificationDao.insert(bob.userId, NotificationType.POST_REMOVED, "Post removed", "bob body", false)
        }

        val token = tokenFor(alice.authId, alice.userId, alice.email)
        val resp = client.get("/api/notifications") { bearerAuth(token) }

        assertEquals(HttpStatusCode.OK, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val items = body["items"]!!.jsonArray
        assertEquals(1, items.size)
        assertEquals("alice body", items[0].jsonObject["body"]?.jsonPrimitive?.content)
        assertEquals(1L, body["unreadCount"]?.jsonPrimitive?.long)
    }

    @Test
    fun `POST notifications read on someone else's notification returns 404`() = notificationTest { client ->
        val alice = CommentTestSeed.seedUser(username = "alice")
        val bob = CommentTestSeed.seedUser(username = "bob")

        val bobNotificationId = runBlocking {
            notificationDao.insert(bob.userId, NotificationType.POST_REMOVED, "Post removed", "bob body", false)
        }

        val token = tokenFor(alice.authId, alice.userId, alice.email)
        val resp = client.post("/api/notifications/$bobNotificationId/read") { bearerAuth(token) }

        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `POST notifications read is idempotent`() = notificationTest { client ->
        val alice = CommentTestSeed.seedUser(username = "alice")
        val notificationId = runBlocking {
            notificationDao.insert(alice.userId, NotificationType.POST_REMOVED, "Post removed", "alice body", false)
        }
        val token = tokenFor(alice.authId, alice.userId, alice.email)

        val first = client.post("/api/notifications/$notificationId/read") { bearerAuth(token) }
        val second = client.post("/api/notifications/$notificationId/read") { bearerAuth(token) }

        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(HttpStatusCode.OK, second.status)

        val unreadAfter = runBlocking { notificationDao.countUnread(alice.userId) }
        assertEquals(0L, unreadAfter)
    }

    @Test
    fun `POST notifications read-all marks every unread notification read`() = notificationTest { client ->
        val alice = CommentTestSeed.seedUser(username = "alice")
        runBlocking {
            notificationDao.insert(alice.userId, NotificationType.POST_REMOVED, "Post removed", "one", false)
            notificationDao.insert(alice.userId, NotificationType.ACCOUNT_SUSPENDED, "Suspended", "two", true)
        }
        val token = tokenFor(alice.authId, alice.userId, alice.email)

        val resp = client.post("/api/notifications/read-all") { bearerAuth(token) }

        assertEquals(HttpStatusCode.OK, resp.status)
        val unreadAfter = runBlocking { notificationDao.countUnread(alice.userId) }
        assertEquals(0L, unreadAfter)
    }
}
