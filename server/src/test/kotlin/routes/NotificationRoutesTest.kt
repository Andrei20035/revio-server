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
import kotlinx.serialization.json.boolean
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

    @Test
    fun `GET notifications without a cursor param returns the same shape and items as before`() = notificationTest { client ->
        val alice = CommentTestSeed.seedUser(username = "alice")
        runBlocking {
            notificationDao.insert(alice.userId, NotificationType.POST_REMOVED, "Post removed", "one", false)
            notificationDao.insert(alice.userId, NotificationType.ACCOUNT_SUSPENDED, "Suspended", "two", true)
        }
        val token = tokenFor(alice.authId, alice.userId, alice.email)

        val resp = client.get("/api/notifications") { bearerAuth(token) }

        assertEquals(HttpStatusCode.OK, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val items = body["items"]!!.jsonArray
        // Both existing fields behave exactly as before: same rows, correct unread count. (Not
        // asserting a specific order between the two — that was never guaranteed for rows
        // inserted within the same instant, and isn't what this cursor change is verifying.)
        assertEquals(setOf("one", "two"), items.map { it.jsonObject["body"]!!.jsonPrimitive.content }.toSet())
        assertEquals(2L, body["unreadCount"]?.jsonPrimitive?.long)
        // No more pages for only two rows within the default limit. `hasMore` defaults to false
        // and Json's encodeDefaults is off (config/Serialization.kt), so a false value is simply
        // absent from the wire response rather than sent as an explicit "hasMore": false.
        assertEquals(false, body["hasMore"]?.jsonPrimitive?.boolean ?: false)
    }

    @Test
    fun `GET notifications paginates by cursor without skipping or repeating rows`() = notificationTest { client ->
        val alice = CommentTestSeed.seedUser(username = "alice")
        val bodies = (1..5).map { "n$it" }
        runBlocking {
            bodies.forEach { body ->
                notificationDao.insert(alice.userId, NotificationType.POST_REMOVED, "t", body, false)
            }
        }
        val token = tokenFor(alice.authId, alice.userId, alice.email)

        // First page of 2.
        val page1 = client.get("/api/notifications?limit=2") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, page1.status)
        val page1Body = Json.parseToJsonElement(page1.bodyAsText()).jsonObject
        val page1Items = page1Body["items"]!!.jsonArray
        assertEquals(2, page1Items.size)
        assertEquals(true, page1Body["hasMore"]?.jsonPrimitive?.boolean)
        val cursor1 = page1Body["nextCursor"]!!.jsonObject

        // Second page, following the cursor.
        val page2 = client.get("/api/notifications") {
            bearerAuth(token)
            parameter("limit", "2")
            parameter("cursorCreatedAt", cursor1["lastCreatedAt"]!!.jsonPrimitive.content)
            parameter("cursorNotificationId", cursor1["lastNotificationId"]!!.jsonPrimitive.content)
        }
        assertEquals(HttpStatusCode.OK, page2.status)
        val page2Body = Json.parseToJsonElement(page2.bodyAsText()).jsonObject
        val page2Items = page2Body["items"]!!.jsonArray
        assertEquals(2, page2Items.size)
        assertEquals(true, page2Body["hasMore"]?.jsonPrimitive?.boolean)
        val cursor2 = page2Body["nextCursor"]!!.jsonObject

        // Third (last) page: exactly the remaining row, no more pages after it.
        val page3 = client.get("/api/notifications") {
            bearerAuth(token)
            parameter("limit", "2")
            parameter("cursorCreatedAt", cursor2["lastCreatedAt"]!!.jsonPrimitive.content)
            parameter("cursorNotificationId", cursor2["lastNotificationId"]!!.jsonPrimitive.content)
        }
        assertEquals(HttpStatusCode.OK, page3.status)
        val page3Body = Json.parseToJsonElement(page3.bodyAsText()).jsonObject
        val page3Items = page3Body["items"]!!.jsonArray
        assertEquals(1, page3Items.size)
        // A false hasMore is omitted from the wire response — see the no-cursor test above.
        assertEquals(false, page3Body["hasMore"]?.jsonPrimitive?.boolean ?: false)

        // Across all three pages, every body appears exactly once — no skip, no duplicate.
        // (Not asserting a specific cross-page order: rows inserted in quick succession aren't
        // guaranteed distinct timestamps, and the tie-break by id exists precisely to keep
        // pagination stable regardless — see NotificationDAO.listForUserAfter.)
        val allBodies = (page1Items + page2Items + page3Items).map { it.jsonObject["body"]!!.jsonPrimitive.content }
        assertEquals(5, allBodies.size)
        assertEquals(bodies.toSet(), allBodies.toSet())
    }

    @Test
    fun `GET notifications cursor pagination is unaffected by a concurrent insert between pages`() = notificationTest { client ->
        val alice = CommentTestSeed.seedUser(username = "alice")
        val bodies = (1..3).map { "n$it" }
        runBlocking {
            bodies.forEach { body ->
                notificationDao.insert(alice.userId, NotificationType.POST_REMOVED, "t", body, false)
            }
        }
        val token = tokenFor(alice.authId, alice.userId, alice.email)

        val page1 = client.get("/api/notifications?limit=2") { bearerAuth(token) }
        val page1Body = Json.parseToJsonElement(page1.bodyAsText()).jsonObject
        val page1Items = page1Body["items"]!!.jsonArray
        val cursor1 = page1Body["nextCursor"]!!.jsonObject

        // A brand-new notification arrives after the first page was already fetched — a
        // keyset cursor must not let it push older, already-paginated rows out of place.
        runBlocking {
            notificationDao.insert(alice.userId, NotificationType.POST_REMOVED, "t", "brand-new", false)
        }

        val page2 = client.get("/api/notifications") {
            bearerAuth(token)
            parameter("limit", "2")
            parameter("cursorCreatedAt", cursor1["lastCreatedAt"]!!.jsonPrimitive.content)
            parameter("cursorNotificationId", cursor1["lastNotificationId"]!!.jsonPrimitive.content)
        }
        val page2Body = Json.parseToJsonElement(page2.bodyAsText()).jsonObject
        val page2Items = page2Body["items"]!!.jsonArray

        val page1Bodies = page1Items.map { it.jsonObject["body"]!!.jsonPrimitive.content }
        val page2Bodies = page2Items.map { it.jsonObject["body"]!!.jsonPrimitive.content }

        // Exactly the one original row page 1 didn't already return — no skip, no duplicate —
        // and never the brand-new row inserted after the cursor was already handed out.
        val expectedOnPage2 = bodies.toSet() - page1Bodies.toSet()
        assertEquals(1, expectedOnPage2.size)
        assertEquals(expectedOnPage2, page2Bodies.toSet())
        assertEquals(false, page2Bodies.contains("brand-new"))
        assertEquals(2, page1Bodies.toSet().size)
    }

    @Test
    fun `GET notifications with only one cursor param returns 400`() = notificationTest { client ->
        val alice = CommentTestSeed.seedUser(username = "alice")
        val token = tokenFor(alice.authId, alice.userId, alice.email)

        val resp = client.get("/api/notifications") {
            bearerAuth(token)
            parameter("cursorCreatedAt", "2024-01-01T00:00:00Z")
        }

        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `GET notifications with a malformed cursor returns 400`() = notificationTest { client ->
        val alice = CommentTestSeed.seedUser(username = "alice")
        val token = tokenFor(alice.authId, alice.userId, alice.email)

        val resp = client.get("/api/notifications") {
            bearerAuth(token)
            parameter("cursorCreatedAt", "not-a-timestamp")
            parameter("cursorNotificationId", "not-a-uuid")
        }

        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }
}
