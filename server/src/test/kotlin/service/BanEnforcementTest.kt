package service

import com.revio.server.core.error.AuthErrorCode
import com.revio.server.core.error.AuthErrorResponse
import com.revio.server.features.auth.AuthProvider
import com.revio.server.features.auth.GoogleTokenVerifier
import com.revio.server.features.auth.GoogleUser
import com.revio.server.features.auth.RefreshTokenGenerator
import com.revio.server.features.auth.dto.AuthResponse
import com.revio.server.features.auth.dto.LoginRequest
import com.revio.server.features.auth.dto.RefreshRequest
import com.revio.server.features.auth.session.AuthSessionDAO
import com.revio.server.features.auth.session.AuthSessionTable
import com.revio.server.features.auth.session.RevokeReason
import com.revio.server.features.auth.session.SessionScope
import com.revio.server.features.auth.session.SessionService
import com.revio.server.features.auth.session.SessionStatus
import com.revio.server.features.user.UserTable
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import testutils.TestDatabaseFactory
import testutils.UserTestSeed
import testutils.setTestEnv
import testutils.stopKoinSafely
import testutils.testAuthModule
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Pins Pas 6's ban enforcement: a banned user must be rejected at both /auth/login and
 * /auth/refresh (so a ban applied mid-session can't be dodged by refreshing), an expired or
 * lifted ban must restore access, and banning revokes the account's existing sessions.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BanEnforcementTest {

    private object NoopGoogleVerifier : GoogleTokenVerifier {
        override fun verify(googleIdToken: String): GoogleUser? = null
    }

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

    private fun banTest(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        application { testAuthModule(NoopGoogleVerifier) }
        val client = createClient { install(ContentNegotiation) { json(json) } }
        block(client)
    }

    /** Seeds a REGULAR credential + full profile, matching AuthRoutesTest's own helper. */
    private fun seedAccount(email: String = "alice@example.com", password: String = "Passw0rd!"): Pair<UUID, UUID> {
        val credential = UserTestSeed.seedAuthCredential(email = email, password = password)
        val userId = UserTestSeed.seedUser(authCredentialId = credential.authCredentialId, username = "alice")
        return credential.authCredentialId to userId
    }

    private fun ban(userId: UUID, permanent: Boolean = false, bannedUntil: Instant? = null, reason: String? = "test") = transaction {
        UserTable.update({ UserTable.id eq userId }) {
            it[UserTable.banPermanent] = permanent
            it[UserTable.bannedUntil] = bannedUntil
            it[UserTable.banReason] = reason
            it[UserTable.bannedAt] = Instant.now()
        }
    }

    private fun unban(userId: UUID) = transaction {
        UserTable.update({ UserTable.id eq userId }) {
            it[UserTable.banPermanent] = false
            it[UserTable.bannedUntil] = null
            it[UserTable.banReason] = null
            it[UserTable.bannedAt] = null
        }
    }

    private fun activeSessionCount(credentialId: UUID): Long = transaction {
        AuthSessionTable
            .selectAll()
            .where { (AuthSessionTable.credentialId eq credentialId) and (AuthSessionTable.status eq SessionStatus.ACTIVE.name) }
            .count()
    }

    private fun latestSessionStatus(credentialId: UUID): String? = transaction {
        AuthSessionTable
            .selectAll()
            .where { AuthSessionTable.credentialId eq credentialId }
            .orderBy(AuthSessionTable.createdAt)
            .lastOrNull()
            ?.get(AuthSessionTable.status)
    }

    private suspend fun login(client: HttpClient, email: String, password: String) =
        client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(email = email, password = password, provider = AuthProvider.REGULAR))
        }

    // ---------- login ----------

    @Test
    fun `login for a permanently banned user returns 403 ACCOUNT_SUSPENDED and creates no session`() = banTest { client ->
        val (credentialId, userId) = seedAccount()
        ban(userId, permanent = true)

        val resp = login(client, "alice@example.com", "Passw0rd!")

        assertEquals(HttpStatusCode.Forbidden, resp.status)
        assertEquals(AuthErrorCode.ACCOUNT_SUSPENDED, resp.body<AuthErrorResponse>().error.code)
        assertEquals(0L, activeSessionCount(credentialId), "A rejected login must not create a session")
    }

    @Test
    fun `login for a user banned until a future date returns 403 ACCOUNT_SUSPENDED`() = banTest { client ->
        val (_, userId) = seedAccount()
        ban(userId, bannedUntil = Instant.now().plus(3, ChronoUnit.DAYS))

        val resp = login(client, "alice@example.com", "Passw0rd!")

        assertEquals(HttpStatusCode.Forbidden, resp.status)
        assertEquals(AuthErrorCode.ACCOUNT_SUSPENDED, resp.body<AuthErrorResponse>().error.code)
    }

    @Test
    fun `login succeeds once a dated ban has expired`() = banTest { client ->
        val (_, userId) = seedAccount()
        ban(userId, bannedUntil = Instant.now().minus(1, ChronoUnit.HOURS))

        val resp = login(client, "alice@example.com", "Passw0rd!")

        assertEquals(HttpStatusCode.OK, resp.status, "An expired ban must not block login")
    }

    @Test
    fun `login succeeds again after an unban`() = banTest { client ->
        val (_, userId) = seedAccount()
        ban(userId, permanent = true)
        assertEquals(HttpStatusCode.Forbidden, login(client, "alice@example.com", "Passw0rd!").status)

        unban(userId)

        assertEquals(HttpStatusCode.OK, login(client, "alice@example.com", "Passw0rd!").status)
    }

    // ---------- refresh ----------

    @Test
    fun `refresh for a user banned mid-session returns 403 ACCOUNT_SUSPENDED, not a generic session error`() = banTest { client ->
        val (_, userId) = seedAccount()
        val loginResp = login(client, "alice@example.com", "Passw0rd!")
        assertEquals(HttpStatusCode.OK, loginResp.status)
        val refreshToken = loginResp.body<AuthResponse>().refreshToken

        // Ban applied after the session already exists — the session itself is untouched here,
        // isolating AuthRoutes' own ban check from session revocation's SESSION_REVOKED path.
        ban(userId, permanent = true)

        val refreshResp = client.post("/api/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest(refreshToken))
        }

        assertEquals(HttpStatusCode.Forbidden, refreshResp.status)
        assertEquals(AuthErrorCode.ACCOUNT_SUSPENDED, refreshResp.body<AuthErrorResponse>().error.code)
    }

    @Test
    fun `refresh succeeds for a user whose ban has since expired`() = banTest { client ->
        val (_, userId) = seedAccount()
        val loginResp = login(client, "alice@example.com", "Passw0rd!")
        val refreshToken = loginResp.body<AuthResponse>().refreshToken

        ban(userId, bannedUntil = Instant.now().minus(1, ChronoUnit.HOURS))

        val refreshResp = client.post("/api/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest(refreshToken))
        }

        assertEquals(HttpStatusCode.OK, refreshResp.status)
    }

    // ---------- session revocation ----------

    @Test
    fun `revoking sessions for ACCOUNT_SUSPENDED marks the active session revoked`() = banTest {
        val (credentialId, userId) = seedAccount()
        val sessionService = SessionService(AuthSessionDAO(), RefreshTokenGenerator())
        sessionService.createSession(
            credentialId = credentialId,
            scope = SessionScope.FULL,
            userId = userId,
            deviceId = null,
            deviceName = null,
            userAgent = null,
            ip = null,
        )
        assertEquals(1L, activeSessionCount(credentialId))

        // The exact call ModerationService.banUser makes right after BanDAO.banUserAtomically commits.
        sessionService.revokeAllSessions(credentialId, RevokeReason.ACCOUNT_SUSPENDED)

        assertEquals(0L, activeSessionCount(credentialId))
        assertEquals(SessionStatus.REVOKED.name, latestSessionStatus(credentialId))
    }
}
