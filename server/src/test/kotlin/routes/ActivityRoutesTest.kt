package com.revio.server.routes

import com.revio.server.features.activity.ActivityEventType
import com.revio.server.features.auth.JwtService
import com.revio.server.features.auth.RefreshTokenGenerator
import com.revio.server.features.auth.session.AuthSessionDAO
import com.revio.server.features.auth.session.SessionScope
import com.revio.server.features.auth.session.SessionService
import features.activity.ActivityDAO
import features.comment.CommentDAO
import features.like.LikeDAO
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
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import testutils.CommentTestSeed
import testutils.TestDatabaseFactory
import testutils.TestEnv
import testutils.setTestEnv
import testutils.stopKoinSafely
import testutils.testActivityModule
import java.time.LocalDate
import java.util.UUID

/**
 * Route-level coverage for `GET /activity` (pas 4.1) — the Activity screen's only endpoint had
 * zero test coverage before this: [com.revio.server.features.activity.ActivityDAO] and
 * [com.revio.server.features.activity.ActivityService] each have their own unit tests, but
 * nothing exercised the wired-up route (auth gate, query param parsing, response shape) end to
 * end.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ActivityRoutesTest {

    private val jwt = JwtService(
        jwtSecret = TestEnv.JWT_SECRET,
        jwtIssuer = TestEnv.JWT_ISSUER,
        jwtAudience = TestEnv.JWT_AUDIENCE,
    )

    private val activityDao = ActivityDAO()
    private val likeDao = LikeDAO()
    private val commentDao = CommentDAO()

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

    private fun activityTest(block: suspend ApplicationTestBuilder.(io.ktor.client.HttpClient) -> Unit) =
        testApplication {
            application { testActivityModule() }
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
    fun `GET activity without JWT returns 401`() = activityTest { client ->
        val resp = client.get("/api/activity")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `GET activity merges likes, comments, and persisted events into one timeline`() = activityTest { client ->
        val alice = CommentTestSeed.seedUser(username = "alice")
        val bob = CommentTestSeed.seedUser(username = "bob")
        val alicePost = CommentTestSeed.seedPost(alice.userId)

        runBlocking {
            likeDao.likePost(bob.userId, alicePost.postId)
            commentDao.addComment(bob.userId, alicePost.postId, "nice car")
            activityDao.recordEventIdempotent(alice.userId, ActivityEventType.STREAK, LocalDate.now(), 5)
        }

        val token = tokenFor(alice.authId, alice.userId, alice.email)
        val resp = client.get("/api/activity") { bearerAuth(token) }

        assertEquals(HttpStatusCode.OK, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val items = body["items"]!!.jsonArray
        val types = items.map { it.jsonObject["type"]?.jsonPrimitive?.content }.toSet()

        assertEquals(3, items.size)
        assertEquals(setOf("LIKE", "COMMENT", "STREAK"), types)
    }

    @Test
    fun `GET activity respects the limit query parameter`() = activityTest { client ->
        val alice = CommentTestSeed.seedUser(username = "alice")

        runBlocking {
            repeat(3) { activityDao.recordEventIdempotent(alice.userId, ActivityEventType.LEADERBOARD_UP, LocalDate.now().minusDays(it.toLong()), it + 1) }
        }

        val token = tokenFor(alice.authId, alice.userId, alice.email)
        val resp = client.get("/api/activity") { bearerAuth(token); parameter("limit", "2") }

        assertEquals(HttpStatusCode.OK, resp.status)
        val items = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["items"]!!.jsonArray
        assertTrue(items.size <= 2)
    }

    @Test
    fun `GET activity for a user with no activity returns an empty timeline`() = activityTest { client ->
        val alice = CommentTestSeed.seedUser(username = "alice")
        val token = tokenFor(alice.authId, alice.userId, alice.email)

        val resp = client.get("/api/activity") { bearerAuth(token) }

        assertEquals(HttpStatusCode.OK, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals(0, body["items"]!!.jsonArray.size)
        assertEquals(0, body["todayInteractions"]?.jsonPrimitive?.content?.toInt())
    }
}
