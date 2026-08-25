package service

import com.revio.server.features.auth.RefreshTokenGenerator
import com.revio.server.features.auth.session.AuthSession
import com.revio.server.features.auth.session.AuthSessionDAO
import com.revio.server.features.auth.session.IAuthSessionDAO
import com.revio.server.features.auth.session.NewAuthSession
import com.revio.server.features.auth.session.RefreshTokenConsumedException
import com.revio.server.features.auth.session.RefreshTokenExpiredException
import com.revio.server.features.auth.session.RefreshTokenInvalidException
import com.revio.server.features.auth.session.RefreshTokenReusedException
import com.revio.server.features.auth.session.RevokeReason
import com.revio.server.features.auth.session.SessionRevokedException
import com.revio.server.features.auth.session.SessionScope
import com.revio.server.features.auth.session.SessionService
import com.revio.server.features.auth.session.SessionStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import testutils.TestDataFactory
import testutils.TestDatabaseFactory
import testutils.UserTestSeed
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionServiceTest {

    // ── DB-backed infrastructure (needed for refreshTokens which queries table directly) ──

    private val authSessionDao = AuthSessionDAO()

    @BeforeAll
    fun setup() {
        TestDatabaseFactory.start()
    }

    @AfterAll
    fun tearDown() {
        TestDatabaseFactory.stop()
    }

    @BeforeEach
    fun clean() {
        TestDatabaseFactory.cleanDatabase()
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private val generator = RefreshTokenGenerator()

    private fun sessionWith(
        credentialId: UUID,
        status: SessionStatus = SessionStatus.ACTIVE,
        scope: SessionScope = SessionScope.ONBOARDING,
        userId: UUID? = null,
        version: Int = 1,
        refreshTokenHash: String = "a".repeat(64),
        prevTokenHash: String? = null,
        prevRotatedAt: Instant? = null,
        idleExpiresAt: Instant = Instant.now().plusSeconds(2_592_000),
        absoluteExpiresAt: Instant = Instant.now().plusSeconds(15_552_000),
    ) = AuthSession(
        id = UUID.randomUUID(),
        credentialId = credentialId,
        userId = userId,
        version = version,
        scope = scope,
        refreshTokenHash = refreshTokenHash,
        prevTokenHash = prevTokenHash,
        prevRotatedAt = prevRotatedAt,
        status = status,
        revokedReason = null,
        deviceId = null,
        deviceName = null,
        userAgent = null,
        ipAddress = null,
        createdAt = Instant.now(),
        lastUsedAt = Instant.now(),
        idleExpiresAt = idleExpiresAt,
        absoluteExpiresAt = absoluteExpiresAt,
    )

    private fun newService(dao: IAuthSessionDAO = mockk(relaxed = true)) =
        SessionService(dao, generator)

    // ── createSession (mock DAO) ───────────────────────────────────────────────

    @Test
    fun `createSession revokes existing active session then inserts new one`() = runTest {
        val credId = UUID.randomUUID()
        val dao = mockk<IAuthSessionDAO>()
        val capturedNew = slot<NewAuthSession>()
        val fakeSession = sessionWith(credId, refreshTokenHash = "x".repeat(64))

        coEvery { dao.listActiveSessions(credId) } returns emptyList()
        coEvery { dao.replaceActiveSession(capture(capturedNew)) } returns fakeSession

        val service = newService(dao)
        val (session, rawToken) = service.createSession(
            credentialId = credId,
            scope = SessionScope.ONBOARDING,
            deviceId = null, deviceName = null, userAgent = null, ip = null,
        )

        coVerify(exactly = 1) { dao.replaceActiveSession(any()) }
        assertEquals(fakeSession, session)
        assertTrue(rawToken.isNotBlank())
        assertEquals(SessionScope.ONBOARDING, capturedNew.captured.scope)
        assertNotNull(capturedNew.captured.idleExpiresAt)
        assertNotNull(capturedNew.captured.absoluteExpiresAt)
    }

    @Test
    fun `createSession with FULL scope stores provided userId`() = runTest {
        val credId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val dao = mockk<IAuthSessionDAO>()
        val capturedNew = slot<NewAuthSession>()
        val fakeSession = sessionWith(credId, scope = SessionScope.FULL, userId = userId)

        coEvery { dao.listActiveSessions(credId) } returns emptyList()
        coEvery { dao.replaceActiveSession(capture(capturedNew)) } returns fakeSession

        val service = newService(dao)
        service.createSession(
            credentialId = credId,
            scope = SessionScope.FULL,
            userId = userId,
            deviceId = null, deviceName = null, userAgent = null, ip = null,
        )

        assertEquals(userId, capturedNew.captured.userId)
        assertEquals(SessionScope.FULL, capturedNew.captured.scope)
    }

    @Test
    fun `concurrent createSession calls leave exactly one ACTIVE session`() {
        val authDao = com.revio.server.features.auth.AuthDAO()
        val credentialId = runBlocking {
            authDao.createCredentials(
                TestDataFactory.regularCredential(email = "concurrent-${UUID.randomUUID()}@test.com")
            )
        }
        val service = SessionService(authSessionDao, generator)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val attempts = (1..2).map { device ->
                executor.submit<Pair<AuthSession, String>> {
                    check(start.await(5, TimeUnit.SECONDS))
                    runBlocking {
                        service.createSession(
                            credentialId = credentialId,
                            scope = SessionScope.ONBOARDING,
                            deviceId = "device-$device",
                            deviceName = null,
                            userAgent = null,
                            ip = null,
                        )
                    }
                }
            }

            start.countDown()
            val created = attempts.map { it.get(10, TimeUnit.SECONDS).first }
            val active = runBlocking { authSessionDao.listActiveSessions(credentialId) }

            assertEquals(2, created.size)
            assertEquals(1, active.size)
            assertTrue(created.any { it.id == active.single().id })
            val superseded = created.first { it.id != active.single().id }
            assertEquals(
                RevokeReason.SUPERSEDED,
                runBlocking { authSessionDao.findById(superseded.id) }?.revokedReason,
            )
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    // ── revokeSession (mock DAO) ──────────────────────────────────────────────

    @Test
    fun `revokeSession delegates to DAO`() = runTest {
        val dao = mockk<IAuthSessionDAO>()
        val sessionId = UUID.randomUUID()
        // LOGOUT is a device-deactivating reason, so revokeSession looks the session up first —
        // returning null here keeps this test scoped to "delegates to DAO", not device deactivation.
        coEvery { dao.findById(sessionId) } returns null
        coEvery { dao.revokeSession(sessionId, RevokeReason.LOGOUT) } returns 1

        newService(dao).revokeSession(sessionId, RevokeReason.LOGOUT)

        coVerify(exactly = 1) { dao.revokeSession(sessionId, RevokeReason.LOGOUT) }
    }

    // ── revokeAllSessions (mock DAO) ──────────────────────────────────────────

    @Test
    fun `revokeAllSessions delegates to DAO revokeActiveByCredential`() = runTest {
        val dao = mockk<IAuthSessionDAO>()
        val credId = UUID.randomUUID()
        val exceptId = UUID.randomUUID()
        // LOGOUT_ALL is a device-deactivating reason, so revokeAllSessions lists active sessions
        // first — empty here keeps this test scoped to "delegates to DAO", not device deactivation.
        coEvery { dao.listActiveSessions(credId) } returns emptyList()
        coEvery { dao.revokeActiveByCredential(credId, RevokeReason.LOGOUT_ALL, exceptId) } returns 2

        newService(dao).revokeAllSessions(credId, RevokeReason.LOGOUT_ALL, exceptSessionId = exceptId)

        coVerify(exactly = 1) { dao.revokeActiveByCredential(credId, RevokeReason.LOGOUT_ALL, exceptId) }
    }

    // ── validateSessionForRequest (mock DAO) ──────────────────────────────────

    @Test
    fun `validateSessionForRequest returns session when ACTIVE and version matches`() = runTest {
        val credId = UUID.randomUUID()
        val session = sessionWith(credId, version = 3)
        val dao = mockk<IAuthSessionDAO>()
        coEvery { dao.findById(session.id) } returns session

        val result = newService(dao).validateSessionForRequest(session.id, credId, 3)

        assertEquals(session, result)
    }

    @Test
    fun `validateSessionForRequest returns null when session not found`() = runTest {
        val dao = mockk<IAuthSessionDAO>()
        coEvery { dao.findById(any()) } returns null

        val result = newService(dao).validateSessionForRequest(UUID.randomUUID(), UUID.randomUUID(), 1)

        assertNull(result)
    }

    @Test
    fun `validateSessionForRequest returns null when status is REVOKED`() = runTest {
        val credId = UUID.randomUUID()
        val session = sessionWith(credId, status = SessionStatus.REVOKED)
        val dao = mockk<IAuthSessionDAO>()
        coEvery { dao.findById(session.id) } returns session

        val result = newService(dao).validateSessionForRequest(session.id, credId, 1)

        assertNull(result)
    }

    @Test
    fun `validateSessionForRequest returns null when version mismatches`() = runTest {
        val credId = UUID.randomUUID()
        val session = sessionWith(credId, version = 5)
        val dao = mockk<IAuthSessionDAO>()
        coEvery { dao.findById(session.id) } returns session

        val result = newService(dao).validateSessionForRequest(session.id, credId, 3)

        assertNull(result)
    }

    @Test
    fun `validateSessionForRequest returns null when credentialId mismatches`() = runTest {
        val credId = UUID.randomUUID()
        val session = sessionWith(credId)
        val dao = mockk<IAuthSessionDAO>()
        coEvery { dao.findById(session.id) } returns session

        val result = newService(dao).validateSessionForRequest(session.id, UUID.randomUUID(), 1)

        assertNull(result)
    }

    @Test
    fun `validateSessionForRequest returns null when idle TTL exceeded`() = runTest {
        val credId = UUID.randomUUID()
        val session = sessionWith(credId, idleExpiresAt = Instant.now().minusSeconds(1))
        val dao = mockk<IAuthSessionDAO>()
        coEvery { dao.findById(session.id) } returns session

        val result = newService(dao).validateSessionForRequest(session.id, credId, 1)

        assertNull(result)
    }

    // ── rotateForPasswordChange (mock DAO) ────────────────────────────────────

    @Test
    fun `rotateForPasswordChange revokes all other sessions and rotates current`() = runTest {
        val credId = UUID.randomUUID()
        val session = sessionWith(credId, version = 2, refreshTokenHash = "f".repeat(64))
        val rotated = session.copy(version = 3, refreshTokenHash = "g".repeat(64))
        val dao = mockk<IAuthSessionDAO>()

        coEvery {
            dao.rotateForPasswordChangeAtomically(session.id, any(), any(), any())
        } returns rotated

        val (updatedSession, rawToken) = newService(dao).rotateForPasswordChange(session.id)

        coVerify { dao.rotateForPasswordChangeAtomically(session.id, any(), any(), any()) }
        assertEquals(rotated, updatedSession)
        assertTrue(rawToken.isNotBlank())
    }

    @Test
    fun `rotateForPasswordChange throws SessionNotFoundException when session absent`() = runTest {
        val dao = mockk<IAuthSessionDAO>()
        coEvery {
            dao.rotateForPasswordChangeAtomically(any(), any(), any(), any())
        } returns null

        assertThrows(com.revio.server.features.auth.session.SessionNotFoundException::class.java) {
            runBlocking { newService(dao).rotateForPasswordChange(UUID.randomUUID()) }
        }
    }

    // ── refreshTokens — real DB needed for prev-hash lookup ───────────────────

    private suspend fun createCredentialAndSession(
        email: String = "alice@example.com",
        hash: String = "a".repeat(64),
    ): Pair<UUID, AuthSession> {
        val authDao = com.revio.server.features.auth.AuthDAO()
        val cred = TestDataFactory.regularCredential(email = email)
        val credId = authDao.createCredentials(cred)
        val session = authSessionDao.createSession(
            NewAuthSession(
                credentialId = credId,
                userId = null,
                scope = SessionScope.ONBOARDING,
                refreshTokenHash = hash,
                deviceId = null, deviceName = null, userAgent = null, ipAddress = null,
                idleExpiresAt = Instant.now().plusSeconds(2_592_000),
                absoluteExpiresAt = Instant.now().plusSeconds(15_552_000),
            )
        )
        return Pair(credId, session)
    }

    @Test
    fun `refreshTokens normal rotation returns new token pair`() = runTest {
        val rawToken = "raw-token-normal-" + UUID.randomUUID()
        val hash = generator.hashOf(rawToken)
        val (_, _) = createCredentialAndSession(hash = hash)
        val service = SessionService(authSessionDao, generator)

        val (session, newRaw) = service.refreshTokens(rawToken)

        assertNotEquals(rawToken, newRaw, "new raw token must differ from old")
        assertEquals(hash, session.prevTokenHash, "old hash must be stored as prevTokenHash")
        assertEquals(2, session.version)
        assertEquals(SessionStatus.ACTIVE, session.status)
    }

    @Test
    fun `refreshTokens throws RefreshTokenExpiredException when idle TTL exceeded`() = runTest {
        val rawToken = "raw-token-expired-" + UUID.randomUUID()
        val hash = generator.hashOf(rawToken)
        val authDao = com.revio.server.features.auth.AuthDAO()
        val cred = TestDataFactory.regularCredential(email = "expired-${UUID.randomUUID()}@test.com")
        val credId = authDao.createCredentials(cred)
        authSessionDao.createSession(
            NewAuthSession(
                credentialId = credId, userId = null, scope = SessionScope.ONBOARDING,
                refreshTokenHash = hash,
                deviceId = null, deviceName = null, userAgent = null, ipAddress = null,
                idleExpiresAt = Instant.now().minusSeconds(1),     // already expired
                absoluteExpiresAt = Instant.now().plusSeconds(15_552_000),
            )
        )
        val service = SessionService(authSessionDao, generator)

        assertThrows(RefreshTokenExpiredException::class.java) {
            runBlocking { service.refreshTokens(rawToken) }
        }
    }

    @Test
    fun `refreshTokens throws RefreshTokenConsumed for prev token within grace window`() = runTest {
        val (rawToken, hash) = generator.generate()
        val (_, session) = createCredentialAndSession(hash = hash)
        val service = SessionService(authSessionDao, generator)

        // Rotate once — old token moves to prevTokenHash, prevRotatedAt = now
        service.refreshTokens(rawToken)

        // Retry with old token within grace window (< 30s)
        assertThrows(RefreshTokenConsumedException::class.java) {
            runBlocking { service.refreshTokens(rawToken) }
        }
    }

    @Test
    fun `refreshTokens throws RefreshTokenReused for prev token after grace window`() = runTest {
        val (rawToken, hash) = generator.generate()
        val (_, session) = createCredentialAndSession(hash = hash)
        val service = SessionService(authSessionDao, generator)

        // Rotate once normally
        service.refreshTokens(rawToken)

        // Manually backdate prevRotatedAt past the grace window
        val table = com.revio.server.features.auth.session.AuthSessionTable
        transaction {
            table.update({ table.id eq session.id }) {
                it[table.prevRotatedAt] =
                    Instant.now()
                        .minusSeconds(SessionService.GRACE_WINDOW_SECONDS + 10)
                        .atOffset(java.time.ZoneOffset.UTC)
            }
        }

        // Old token after grace window → REUSED
        assertThrows(RefreshTokenReusedException::class.java) {
            runBlocking { service.refreshTokens(rawToken) }
        }
    }

    @Test
    fun `refreshTokens throws RefreshTokenInvalid for completely unknown token`() = runTest {
        val service = SessionService(authSessionDao, generator)

        assertThrows(RefreshTokenInvalidException::class.java) {
            runBlocking { service.refreshTokens("completely-unknown-token-${UUID.randomUUID()}") }
        }
    }

    @Test
    fun `refreshTokens on REVOKED session throws SessionRevokedException`() = runTest {
        val (rawToken, hash) = generator.generate()
        val (_, session) = createCredentialAndSession(hash = hash)
        authSessionDao.revokeSession(session.id, RevokeReason.LOGOUT)
        val service = SessionService(authSessionDao, generator)

        assertThrows(SessionRevokedException::class.java) {
            runBlocking { service.refreshTokens(rawToken) }
        }
    }

    // ── promoteSession (mock DAO) ─────────────────────────────────────────────

    @Test
    fun `promoteSession promotes ONBOARDING to FULL with new token`() = runTest {
        val sessionId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val dao = mockk<IAuthSessionDAO>()
        val onboardingSession = sessionWith(UUID.randomUUID(), scope = SessionScope.ONBOARDING, version = 1)
        val promotedSession = onboardingSession.copy(scope = SessionScope.FULL, userId = userId, version = 2)

        coEvery {
            dao.promoteToFullAtomically(sessionId, userId, any(), any())
        } returns promotedSession

        val (session, rawToken) = newService(dao).promoteSession(sessionId, userId)

        coVerify { dao.promoteToFullAtomically(sessionId, userId, any(), any()) }
        assertEquals(SessionScope.FULL, session.scope)
        assertEquals(userId, session.userId)
        assertTrue(rawToken.isNotBlank())
    }
}
