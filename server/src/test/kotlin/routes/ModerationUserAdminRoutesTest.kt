package routes

import com.revio.server.features.auth.JwtService
import com.revio.server.features.auth.RefreshTokenGenerator
import com.revio.server.features.auth.session.AuthSessionDAO
import com.revio.server.features.auth.session.AuthSessionTable
import com.revio.server.features.auth.session.SessionScope
import com.revio.server.features.auth.session.SessionService
import com.revio.server.features.auth.session.SessionStatus
import com.revio.server.features.moderation.AdminAuditLogTable
import com.revio.server.features.moderation.ModerationReason
import com.revio.server.features.moderation.ModerationViolationTable
import com.revio.server.features.user.UserTable
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
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
import testutils.CommentTestSeed
import testutils.TestDatabaseFactory
import testutils.TestEnv
import testutils.setTestEnv
import testutils.stopKoinSafely
import testutils.testModerationModule
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModerationUserAdminRoutesTest {

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

    private fun seedViolation(userId: UUID, adminId: UUID, revoked: Boolean = false): UUID = transaction {
        ModerationViolationTable.insert {
            it[ModerationViolationTable.userId] = userId
            it[ModerationViolationTable.postId] = UUID.randomUUID()
            it[ModerationViolationTable.reason] = ModerationReason.SPAM_OR_MISLEADING
            it[ModerationViolationTable.adminId] = adminId
            if (revoked) {
                it[ModerationViolationTable.revokedAt] = java.time.Instant.now()
                it[ModerationViolationTable.revokedBy] = adminId
            }
        }[ModerationViolationTable.id].value
    }

    private fun activeSessionStatus(credentialId: UUID): String? = transaction {
        AuthSessionTable
            .selectAll()
            .where { AuthSessionTable.credentialId eq credentialId }
            .orderBy(AuthSessionTable.createdAt)
            .lastOrNull()
            ?.get(AuthSessionTable.status)
    }

    private fun isBanned(userId: UUID): Boolean = transaction {
        UserTable.selectAll().where { UserTable.id eq userId }.single()[UserTable.banPermanent]
    }

    private fun auditLogCount(action: String): Long = transaction {
        AdminAuditLogTable.selectAll().where { AdminAuditLogTable.action eq action }.count()
    }

    // ---------- search ----------

    @Test
    fun `search users without admin token returns 403`() = moderationTest { client ->
        val alice = CommentTestSeed.seedUser(username = "alice")
        val userToken = tokenFor(alice.authId, alice.userId, alice.email, isAdmin = false)

        val resp = client.get("/api/admin/users?query=alice") {
            header(HttpHeaders.Authorization, "Bearer $userToken")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test
    fun `search users without query returns 400`() = moderationTest { client ->
        val token = adminToken()
        val resp = client.get("/api/admin/users") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `search users by username substring finds the user`() = moderationTest { client ->
        CommentTestSeed.seedUser(username = "alice_spotter")
        CommentTestSeed.seedUser(username = "bob")
        val token = adminToken()

        val resp = client.get("/api/admin/users?query=alice") { header(HttpHeaders.Authorization, "Bearer $token") }

        assertEquals(HttpStatusCode.OK, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonArray
        assertEquals(1, body.size)
        assertEquals("alice_spotter", body[0].jsonObject["username"]?.jsonPrimitive?.content)
    }

    @Test
    fun `search users by email substring finds the user`() = moderationTest { client ->
        val alice = CommentTestSeed.seedUser(username = "alice", email = "alice.spotter@example.com")
        val token = adminToken()

        val resp = client.get("/api/admin/users?query=alice.spotter") { header(HttpHeaders.Authorization, "Bearer $token") }

        assertEquals(HttpStatusCode.OK, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonArray
        assertEquals(1, body.size)
        assertEquals(alice.userId.toString(), body[0].jsonObject["id"]?.jsonPrimitive?.content)
    }

    // ---------- moderation detail ----------

    @Test
    fun `moderation detail for unknown user returns 404`() = moderationTest { client ->
        val token = adminToken()
        val resp = client.get("/api/admin/users/${UUID.randomUUID()}/moderation") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `moderation detail flags needsReview at 3 active violations`() = moderationTest { client ->
        val alice = CommentTestSeed.seedUser(username = "alice")
        val admin = CommentTestSeed.seedUser(username = "moderator2")
        seedViolation(alice.userId, admin.userId)
        seedViolation(alice.userId, admin.userId)
        seedViolation(alice.userId, admin.userId, revoked = true) // revoked — must not count
        val token = adminToken()

        val resp = client.get("/api/admin/users/${alice.userId}/moderation") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals(2, body["activeViolationCount"]?.jsonPrimitive?.int)
        assertFalse(body["needsReview"]!!.jsonPrimitive.boolean, "2 active violations must not trip the 3-violation threshold")
        assertEquals(3, body["violations"]!!.jsonArray.size, "Revoked violations still show up in the history")

        seedViolation(alice.userId, admin.userId)
        val resp2 = client.get("/api/admin/users/${alice.userId}/moderation") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val body2 = Json.parseToJsonElement(resp2.bodyAsText()).jsonObject
        assertEquals(3, body2["activeViolationCount"]?.jsonPrimitive?.int)
        assertTrue(body2["needsReview"]!!.jsonPrimitive.boolean, "3 active violations must trip needsReview")
    }

    // ---------- ban / unban ----------

    @Test
    fun `ban with an invalid durationDays returns 400`() = moderationTest { client ->
        val alice = CommentTestSeed.seedUser(username = "alice")
        val token = adminToken()

        val resp = client.post("/api/admin/users/${alice.userId}/ban") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"durationDays":5,"permanent":false}""")
        }

        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertFalse(isBanned(alice.userId))
    }

    @Test
    fun `ban for an unknown user returns 404`() = moderationTest { client ->
        val token = adminToken()
        val resp = client.post("/api/admin/users/${UUID.randomUUID()}/ban") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"durationDays":7,"permanent":false}""")
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `ban revokes active sessions and records audit and notification`() = moderationTest { client ->
        val alice = CommentTestSeed.seedUser(username = "alice")
        // Alice's own session, so we can verify it gets revoked by the ban.
        SessionService(AuthSessionDAO(), RefreshTokenGenerator()).createSession(
            credentialId = alice.authId,
            scope = SessionScope.FULL,
            userId = alice.userId,
            deviceId = null,
            deviceName = null,
            userAgent = null,
            ip = null,
        )
        val token = adminToken()

        val resp = client.post("/api/admin/users/${alice.userId}/ban") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"durationDays":7,"permanent":false,"reason":"Repeated spam"}""")
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals(SessionStatus.REVOKED.name, activeSessionStatus(alice.authId))
        assertEquals(1L, auditLogCount("USER_BANNED"))

        val detail = client.get("/api/admin/users/${alice.userId}/moderation") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val banState = Json.parseToJsonElement(detail.bodyAsText()).jsonObject["user"]!!.jsonObject["banState"]!!.jsonObject
        assertTrue(banState["isBanned"]!!.jsonPrimitive.boolean)
        assertFalse(banState["permanent"]!!.jsonPrimitive.boolean)
        val notifications = Json.parseToJsonElement(detail.bodyAsText()).jsonObject["recentNotifications"]!!.jsonArray
        assertTrue(notifications.any { it.jsonObject["type"]?.jsonPrimitive?.content == "ACCOUNT_SUSPENDED" })
    }

    @Test
    fun `permanent ban sets banPermanent regardless of durationDays`() = moderationTest { client ->
        val alice = CommentTestSeed.seedUser(username = "alice")
        val token = adminToken()

        val resp = client.post("/api/admin/users/${alice.userId}/ban") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"permanent":true}""")
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(isBanned(alice.userId))
    }

    @Test
    fun `unban clears ban state and notifies the user`() = moderationTest { client ->
        val alice = CommentTestSeed.seedUser(username = "alice")
        val token = adminToken()

        client.post("/api/admin/users/${alice.userId}/ban") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"permanent":true}""")
        }

        val resp = client.post("/api/admin/users/${alice.userId}/unban") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        assertFalse(isBanned(alice.userId))
        assertEquals(1L, auditLogCount("USER_UNBANNED"))
    }

    // ---------- violation revoke ----------

    @Test
    fun `revoke violation for an unknown id returns 404`() = moderationTest { client ->
        val token = adminToken()
        val resp = client.post("/api/admin/violations/${UUID.randomUUID()}/revoke") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `revoke violation is idempotent and drops the active count`() = moderationTest { client ->
        val alice = CommentTestSeed.seedUser(username = "alice")
        val admin = CommentTestSeed.seedUser(username = "moderator2")
        val violationId = seedViolation(alice.userId, admin.userId)
        val token = adminToken()

        val first = client.post("/api/admin/violations/$violationId/revoke") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val second = client.post("/api/admin/violations/$violationId/revoke") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(HttpStatusCode.OK, second.status)
        assertEquals(1L, auditLogCount("VIOLATION_REVOKED"), "A repeat revoke must not write a second audit entry")

        val detail = client.get("/api/admin/users/${alice.userId}/moderation") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val body = Json.parseToJsonElement(detail.bodyAsText()).jsonObject
        assertEquals(0, body["activeViolationCount"]?.jsonPrimitive?.int)
    }

    // ---------- audit log ----------

    @Test
    fun `audit log lists recorded actions newest first`() = moderationTest { client ->
        val alice = CommentTestSeed.seedUser(username = "alice")
        val token = adminToken()

        client.post("/api/admin/users/${alice.userId}/ban") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"permanent":true}""")
        }
        client.post("/api/admin/users/${alice.userId}/unban") { header(HttpHeaders.Authorization, "Bearer $token") }

        val resp = client.get("/api/admin/audit") { header(HttpHeaders.Authorization, "Bearer $token") }

        assertEquals(HttpStatusCode.OK, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonArray
        assertEquals(2, body.size)
        assertEquals("USER_UNBANNED", body[0].jsonObject["action"]?.jsonPrimitive?.content)
        assertEquals("USER_BANNED", body[1].jsonObject["action"]?.jsonPrimitive?.content)
    }
}
