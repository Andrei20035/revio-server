package routes

import com.revio.server.features.auth.JwtService
import com.revio.server.features.auth.RefreshTokenGenerator
import com.revio.server.features.auth.session.AuthSessionDAO
import com.revio.server.features.auth.session.SessionScope
import com.revio.server.features.auth.session.SessionService
import com.revio.server.features.moderation.AdminAuditLogEntry
import com.revio.server.features.moderation.AdminAuditLogTable
import com.revio.server.features.moderation.AdminUserSummary
import com.revio.server.features.moderation.BulkRemovalResult
import com.revio.server.features.moderation.IModerationService
import com.revio.server.features.moderation.ModerationReason
import com.revio.server.features.moderation.ModerationViolationTable
import com.revio.server.features.moderation.OrphanRetryResult
import com.revio.server.features.moderation.UserModerationDetail
import com.revio.server.features.notification.NotificationTable
import com.revio.server.features.post.PostTable
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.dsl.module
import org.koin.ktor.ext.getKoin
import testutils.CommentTestSeed
import testutils.TestDatabaseFactory
import testutils.TestEnv
import testutils.setTestEnv
import testutils.stopKoinSafely
import testutils.testModerationModule
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModerationAdminRoutesTest {

    private val jwt = JwtService(
        jwtSecret = TestEnv.JWT_SECRET,
        jwtIssuer = TestEnv.JWT_ISSUER,
        jwtAudience = TestEnv.JWT_AUDIENCE,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @BeforeAll
    fun setup() {
        setTestEnv()
        TestDatabaseFactory.start()
    }

    @AfterAll
    fun tearDown() = TestDatabaseFactory.stop()

    @BeforeEach
    fun clean() {
        TestDatabaseFactory.cleanDatabase()
        stopKoinSafely()
    }

    private fun moderationTest(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        application { testModerationModule() }
        val client = createClient { install(ContentNegotiation) { json(json) } }
        block(client)
    }

    private suspend fun tokenFor(authId: UUID, userId: UUID?, email: String, isAdmin: Boolean): String {
        val (session) = SessionService(AuthSessionDAO(), RefreshTokenGenerator()).createSession(
            credentialId = authId,
            scope = SessionScope.FULL,
            userId = userId,
            deviceId = null,
            deviceName = null,
            userAgent = null,
            ip = null,
        )
        return jwt.generateAccessToken(session, authId, email, userId, isAdmin = isAdmin)
    }

    private suspend fun adminToken(): String {
        val admin = CommentTestSeed.seedUser(username = "moderator")
        return tokenFor(admin.authId, admin.userId, admin.email, isAdmin = true)
    }

    private fun postExists(postId: UUID): Boolean = transaction {
        PostTable.selectAll().where { PostTable.id eq postId }.any()
    }

    private fun violationCount(postId: UUID): Long = transaction {
        ModerationViolationTable.selectAll().where { ModerationViolationTable.postId eq postId }.count()
    }

    private fun violationReason(postId: UUID): ModerationReason? = transaction {
        ModerationViolationTable
            .select(ModerationViolationTable.reason)
            .where { ModerationViolationTable.postId eq postId }
            .map { it[ModerationViolationTable.reason] }
            .singleOrNull()
    }

    private fun notificationCount(userId: UUID): Long = transaction {
        NotificationTable.selectAll().where { NotificationTable.userId eq userId }.count()
    }

    private fun auditLogCount(targetId: UUID): Long = transaction {
        AdminAuditLogTable.selectAll().where { AdminAuditLogTable.targetId eq targetId }.count()
    }

    @Test
    fun `remove post without admin token returns 403`() = moderationTest { client ->
        val alice = CommentTestSeed.seedUser(username = "alice")
        val post = CommentTestSeed.seedPost(alice.userId)
        val userToken = tokenFor(alice.authId, alice.userId, alice.email, isAdmin = false)

        val resp = client.post("/api/admin/posts/${post.postId}/remove") {
            header(HttpHeaders.Authorization, "Bearer $userToken")
            contentType(ContentType.Application.Json)
            setBody("""{"reason":"SPAM_OR_MISLEADING"}""")
        }

        assertEquals(HttpStatusCode.Forbidden, resp.status)
        assertTrue(postExists(post.postId))
    }

    @Test
    fun `remove post with reason OTHER and no reasonDetails returns 400`() = moderationTest { client ->
        val alice = CommentTestSeed.seedUser(username = "alice")
        val post = CommentTestSeed.seedPost(alice.userId)
        val token = adminToken()

        val resp = client.post("/api/admin/posts/${post.postId}/remove") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"reason":"OTHER"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(postExists(post.postId))
    }

    @Test
    fun `remove post with reasonDetails over the length limit returns 400`() = moderationTest { client ->
        val alice = CommentTestSeed.seedUser(username = "alice")
        val post = CommentTestSeed.seedPost(alice.userId)
        val token = adminToken()
        val tooLong = "a".repeat(501)

        val resp = client.post("/api/admin/posts/${post.postId}/remove") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"reason":"OTHER","reasonDetails":"$tooLong"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(postExists(post.postId))
    }

    @Test
    fun `remove post for an unknown postId returns 404`() = moderationTest { client ->
        val token = adminToken()

        val resp = client.post("/api/admin/posts/${UUID.randomUUID()}/remove") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"reason":"SPAM_OR_MISLEADING"}""")
        }

        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `remove post deletes the post and records violation, audit log and a blocking notification`() = moderationTest { client ->
        val alice = CommentTestSeed.seedUser(username = "alice")
        val post = CommentTestSeed.seedPost(alice.userId)
        val token = adminToken()

        val resp = client.post("/api/admin/posts/${post.postId}/remove") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"reason":"NO_CAR_CONTENT"}""")
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals(post.postId.toString(), body["postId"]?.jsonPrimitive?.content)

        assertFalse(postExists(post.postId), "Post row must be gone")
        assertEquals(1L, violationCount(post.postId), "A violation row must survive the post's own deletion")
        assertEquals(1L, notificationCount(alice.userId))
        assertEquals(1L, auditLogCount(post.postId))
    }

    @Test
    fun `remove post with OTHER and non-blank reasonDetails succeeds`() = moderationTest { client ->
        val alice = CommentTestSeed.seedUser(username = "alice")
        val post = CommentTestSeed.seedPost(alice.userId)
        val token = adminToken()

        val resp = client.post("/api/admin/posts/${post.postId}/remove") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"reason":"OTHER","reasonDetails":"Doesn't fit any category"}""")
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        assertFalse(postExists(post.postId))
    }

    @Test
    fun `remove post succeeds and records the correct reason for every one of the 13 moderation reasons`() = moderationTest { client ->
        val token = adminToken()

        ModerationReason.entries.forEach { reason ->
            val user = CommentTestSeed.seedUser(username = "user-${reason.name.lowercase()}")
            val post = CommentTestSeed.seedPost(user.userId)
            val reasonDetails = if (reason == ModerationReason.OTHER) "Doesn't fit any category" else null

            val body = buildString {
                append("{\"reason\":\"${reason.name}\"")
                if (reasonDetails != null) append(",\"reasonDetails\":\"$reasonDetails\"")
                append("}")
            }

            val resp = client.post("/api/admin/posts/${post.postId}/remove") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(body)
            }

            assertEquals(HttpStatusCode.OK, resp.status, "reason=$reason")
            assertFalse(postExists(post.postId), "reason=$reason: post row must be gone")
            assertEquals(reason, violationReason(post.postId), "reason=$reason: violation must record this exact reason")
            assertEquals(1L, violationCount(post.postId), "reason=$reason")
            assertEquals(1L, notificationCount(user.userId), "reason=$reason")
            assertEquals(1L, auditLogCount(post.postId), "reason=$reason")
        }
    }

    private class ThrowingModerationService : IModerationService {
        override suspend fun removePost(postId: UUID, adminId: UUID, reason: ModerationReason, reasonDetails: String?): UUID =
            throw RuntimeException("simulated moderation failure")

        override suspend fun bulkRemovePosts(
            postIds: List<UUID>,
            adminId: UUID,
            reason: ModerationReason,
            reasonDetails: String?,
        ): BulkRemovalResult = TODO("not used in this test")

        override suspend fun retryOrphanedStorage(): OrphanRetryResult = TODO("not used in this test")

        override suspend fun searchUsers(query: String, limit: Int): List<AdminUserSummary> = TODO("not used in this test")

        override suspend fun getUserModeration(userId: UUID): UserModerationDetail = TODO("not used in this test")

        override suspend fun banUser(userId: UUID, durationDays: Int?, permanent: Boolean, reason: String?, adminId: UUID): Unit =
            TODO("not used in this test")

        override suspend fun unbanUser(userId: UUID, adminId: UUID): Unit = TODO("not used in this test")

        override suspend fun revokeViolation(violationId: UUID, adminId: UUID): Unit = TODO("not used in this test")

        override suspend fun listAuditLog(limit: Int): List<AdminAuditLogEntry> = TODO("not used in this test")
    }

    private fun Application.withThrowingModeration() {
        testModerationModule()
        getKoin().loadModules(listOf(module { single<IModerationService> { ThrowingModerationService() } }))
    }

    @Test
    fun `remove post returns 500 JSON with a stable code when the moderation service throws`() = testApplication {
        application { withThrowingModeration() }
        val client = createClient { install(ContentNegotiation) { json(json) } }
        val alice = CommentTestSeed.seedUser(username = "alice")
        val post = CommentTestSeed.seedPost(alice.userId)
        val token = adminToken()

        val resp = client.post("/api/admin/posts/${post.postId}/remove") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"reason":"SPAM_OR_MISLEADING"}""")
        }

        assertEquals(HttpStatusCode.InternalServerError, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("post_removal_failed", body["code"]?.jsonPrimitive?.content)
        assertTrue(postExists(post.postId), "A thrown exception must not leave the post removed")
    }

    @Test
    fun `bulk-remove with empty postIds returns 400`() = moderationTest { client ->
        val token = adminToken()

        val resp = client.post("/api/admin/posts/bulk-remove") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"postIds":[],"reason":"SPAM_OR_MISLEADING"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `bulk-remove removes every post but sends a single aggregated notification per owner`() = moderationTest { client ->
        val alice = CommentTestSeed.seedUser(username = "alice")
        val postA = CommentTestSeed.seedPost(alice.userId, customBrand = "vw", customModel = "golf")
        val postB = CommentTestSeed.seedPost(alice.userId, customBrand = "vw", customModel = "polo")
        val postC = CommentTestSeed.seedPost(alice.userId, customBrand = "vw", customModel = "id3")
        val token = adminToken()

        val resp = client.post("/api/admin/posts/bulk-remove") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                """{"postIds":["${postA.postId}","${postB.postId}","${postC.postId}"],"reason":"FAKE_OR_STOLEN_CONTENT"}"""
            )
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals(3, body["removedCount"]?.jsonPrimitive?.int)
        assertEquals(1, body["notifiedUserCount"]?.jsonPrimitive?.int)

        assertFalse(postExists(postA.postId))
        assertFalse(postExists(postB.postId))
        assertFalse(postExists(postC.postId))
        assertEquals(3L, violationCount(postA.postId) + violationCount(postB.postId) + violationCount(postC.postId))
        assertEquals(1L, notificationCount(alice.userId), "One aggregated notification, not three")
    }

    @Test
    fun `retry-orphans with an empty queue returns zero counts`() = moderationTest { client ->
        val token = adminToken()

        val resp = client.post("/api/admin/storage/retry-orphans") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals(0, body["attempted"]?.jsonPrimitive?.int)
        assertEquals(0, body["succeeded"]?.jsonPrimitive?.int)
    }
}
