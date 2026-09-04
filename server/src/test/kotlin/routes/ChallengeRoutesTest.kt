package routes

import com.revio.server.core.storage.IStorageService
import com.revio.server.core.storage.LocalImageStorageService
import com.revio.server.features.auth.JwtService
import com.revio.server.features.auth.RefreshTokenGenerator
import com.revio.server.features.auth.session.AuthSessionDAO
import com.revio.server.features.auth.session.SessionScope
import com.revio.server.features.auth.session.SessionService
import com.revio.server.features.challenge.ChallengeParticipantTable
import com.revio.server.features.challenge.ChallengeStatus
import com.revio.server.features.challenge.ChallengeTable
import com.revio.server.features.challenge.FinalizationResult
import com.revio.server.features.challenge.IChallengeFinalizationService
import com.revio.server.features.challenge.RewardState
import com.revio.server.features.challenge.dto.ChallengeDTO
import com.revio.server.features.challenge.dto.ChallengeProgressDetailDTO
import com.revio.server.features.challenge.dto.CurrentChallengeDTO
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.dsl.module
import org.koin.ktor.ext.getKoin
import testutils.ChallengeTestSeed
import testutils.CommentTestSeed
import testutils.TestDatabaseFactory
import testutils.TestEnv
import testutils.setTestEnv
import testutils.stopKoinSafely
import testutils.testChallengeModule
import java.nio.file.Files
import java.time.Instant
import java.util.UUID

/**
 * HTTP contract of the read-only, user-facing challenge routes (plan §5's "Utilizator" table):
 * GET /challenges/current and GET /challenges/{id}/progress. Real DB/DAO/service stack via
 * [testutils.testChallengeModule] — the point here is the route's response shape and status
 * mapping, not re-deriving grant/revoke logic (dao.ChallengeProgressDaoTest.kt).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChallengeRoutesTest {

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

    /**
     * [testChallengeModule] (shared across every challenge route test file) doesn't bind
     * [IStorageService] — no challenge route needed it before `/{id}/progress` started resolving
     * contribution thumbnails (Pas 7). Loading it here, local to this file via
     * [org.koin.ktor.ext.getKoin]/`loadModules`, keeps that shared helper unchanged for every
     * other test file that doesn't need it.
     */
    private fun Application.withStorage() {
        testChallengeModule()
        val uploadsDir = Files.createTempDirectory("challenge-route-test-uploads")
        getKoin().loadModules(listOf(module { single<IStorageService> { LocalImageStorageService(uploadsDir, "http://localhost:8080") } }))
    }

    private fun userTest(block: suspend ApplicationTestBuilder.(HttpClient, String, UUID) -> Unit) = testApplication {
        application { withStorage() }
        val client = createClient { install(ContentNegotiation) { json(json) } }
        val seeded = CommentTestSeed.seedUser()
        val (session) = SessionService(AuthSessionDAO(), RefreshTokenGenerator()).createSession(
            credentialId = seeded.authId,
            scope = SessionScope.FULL,
            userId = seeded.userId,
            deviceId = null,
            deviceName = null,
            userAgent = null,
            ip = null,
        )
        val token = jwt.generateAccessToken(session, seeded.authId, seeded.email, seeded.userId)
        block(client, token, seeded.userId)
    }

    // ---------- GET /challenges/current ----------

    @Test
    fun `GET challenges current returns null challenge and null progress when nothing is scheduled`() = userTest { client, token, _ ->
        val response = client.get("/api/challenges/current") { header(HttpHeaders.Authorization, "Bearer $token") }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<CurrentChallengeDTO>()
        assertNull(body.challenge)
        assertNull(body.progress)
    }

    @Test
    fun `GET challenges current returns the active challenge and the caller's zero progress`() = userTest { client, token, _ ->
        val familyId = ChallengeTestSeed.seedFamily()
        val now = Instant.now()
        ChallengeTestSeed.seedChallenge(
            familyId = familyId, startsAt = now.minusSeconds(3600), endsAt = now.plusSeconds(3600),
            status = ChallengeStatus.SCHEDULED, title = "Weekend Golf Hunt", requiredPosts = 5, rewardPoints = 300,
        )

        val response = client.get("/api/challenges/current") { header(HttpHeaders.Authorization, "Bearer $token") }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<CurrentChallengeDTO>()
        assertEquals("Weekend Golf Hunt", body.challenge?.title)
        assertEquals("volkswagen", body.challenge?.targetFamilyBrand)
        assertEquals(0, body.progress?.contributionCount)
        assertEquals("NONE", body.progress?.rewardState)
    }

    @Test
    fun `GET challenges current returns the caller's real progress, not just zero`() = userTest { client, token, userId ->
        val familyId = ChallengeTestSeed.seedFamily()
        val modelId = ChallengeTestSeed.seedModel("volkswagen", "golf r", familyId)
        val now = Instant.now()
        val challengeId = ChallengeTestSeed.seedChallenge(
            familyId = familyId, startsAt = now.minusSeconds(3600), endsAt = now.plusSeconds(3600), requiredPosts = 5,
        )
        val postId = ChallengeTestSeed.seedCameraPost(userId, modelId)
        com.revio.server.features.challenge.ChallengeProgressDAO().evaluatePostContribution(challengeId, userId, postId, modelId, now)

        val response = client.get("/api/challenges/current") { header(HttpHeaders.Authorization, "Bearer $token") }

        val body = response.body<CurrentChallengeDTO>()
        assertEquals(1, body.progress?.contributionCount)
    }

    @Test
    fun `GET challenges current returns the soonest upcoming challenge when none is active yet`() = userTest { client, token, _ ->
        val familyId = ChallengeTestSeed.seedFamily()
        val now = Instant.now()
        ChallengeTestSeed.seedChallenge(
            familyId = familyId, startsAt = now.plusSeconds(3600), endsAt = now.plusSeconds(7200),
            status = ChallengeStatus.SCHEDULED, title = "Next weekend",
        )

        val response = client.get("/api/challenges/current") { header(HttpHeaders.Authorization, "Bearer $token") }

        val body = response.body<CurrentChallengeDTO>()
        assertEquals("Next weekend", body.challenge?.title)
    }

    @Test
    fun `GET challenges current returns effectiveStatus SCHEDULED for a challenge that hasn't started yet`() = userTest { client, token, _ ->
        val familyId = ChallengeTestSeed.seedFamily()
        val now = Instant.now()
        ChallengeTestSeed.seedChallenge(
            familyId = familyId, startsAt = now.plusSeconds(3600), endsAt = now.plusSeconds(7200),
            status = ChallengeStatus.SCHEDULED, title = "Next weekend",
        )

        val response = client.get("/api/challenges/current") { header(HttpHeaders.Authorization, "Bearer $token") }

        val body = response.body<CurrentChallengeDTO>()
        assertEquals("SCHEDULED", body.effectiveStatus)
    }

    @Test
    fun `GET challenges current returns effectiveStatus ACTIVE for a challenge inside its window`() = userTest { client, token, _ ->
        val familyId = ChallengeTestSeed.seedFamily()
        val now = Instant.now()
        ChallengeTestSeed.seedChallenge(
            familyId = familyId, startsAt = now.minusSeconds(3600), endsAt = now.plusSeconds(3600),
            status = ChallengeStatus.SCHEDULED, title = "Weekend Golf Hunt",
        )

        val response = client.get("/api/challenges/current") { header(HttpHeaders.Authorization, "Bearer $token") }

        val body = response.body<CurrentChallengeDTO>()
        assertEquals("ACTIVE", body.effectiveStatus)
    }

    @Test
    fun `GET challenges current returns null effectiveStatus when nothing is scheduled`() = userTest { client, token, _ ->
        val response = client.get("/api/challenges/current") { header(HttpHeaders.Authorization, "Bearer $token") }

        val body = response.body<CurrentChallengeDTO>()
        assertNull(body.effectiveStatus)
    }

    @Test
    fun `GET challenges current without a token is unauthorized`() = testApplication {
        application { testChallengeModule() }
        val client = createClient { install(ContentNegotiation) { json(json) } }

        val response = client.get("/api/challenges/current")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // ---------- lazy catch-up finalization (plan §9-C8) ----------

    /** Polls until [challengeId] has finalized_at set, or [timeoutMillis] elapses. */
    private suspend fun awaitFinalized(challengeId: UUID, timeoutMillis: Long = 3000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val finalizedAt = transaction {
                ChallengeTable.select(ChallengeTable.finalizedAt).where { ChallengeTable.id eq challengeId }.single()[ChallengeTable.finalizedAt]
            }
            if (finalizedAt != null) return true
            delay(50)
        }
        return false
    }

    @Test
    fun `GET challenges current lazily finalizes a due challenge in the background`() = userTest { client, token, userId ->
        val familyId = ChallengeTestSeed.seedFamily()
        val modelId = ChallengeTestSeed.seedModel("volkswagen", "golf r", familyId)
        val windowEnd = Instant.now().minusSeconds(3600)
        val windowStart = windowEnd.minusSeconds(3600)
        val challengeId = ChallengeTestSeed.seedChallenge(
            familyId = familyId, startsAt = windowStart, endsAt = windowEnd, requiredPosts = 1, rewardPoints = 300,
        )
        val postId = ChallengeTestSeed.seedCameraPost(userId, modelId)
        com.revio.server.features.challenge.ChallengeProgressDAO()
            .evaluatePostContribution(challengeId, userId, postId, modelId, windowStart.plusSeconds(60))

        val response = client.get("/api/challenges/current") { header(HttpHeaders.Authorization, "Bearer $token") }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(awaitFinalized(challengeId), "the due challenge must be finalized shortly after the /current request")
        val rewardState = transaction {
            ChallengeParticipantTable.select(ChallengeParticipantTable.rewardState)
                .where { (ChallengeParticipantTable.challengeId eq challengeId) and (ChallengeParticipantTable.userId eq userId) }
                .single()[ChallengeParticipantTable.rewardState]
        }
        assertEquals(RewardState.GRANTED, rewardState)
    }

    private class ThrowingChallengeFinalizationService : IChallengeFinalizationService {
        override suspend fun finalize(challengeId: UUID, now: Instant): FinalizationResult? =
            throw RuntimeException("simulated finalization failure")
    }

    private fun Application.withThrowingFinalization() {
        testChallengeModule()
        getKoin().loadModules(listOf(module { single<IChallengeFinalizationService> { ThrowingChallengeFinalizationService() } }))
    }

    @Test
    fun `GET challenges current responds normally even when the background finalization throws`() = testApplication {
        application { withThrowingFinalization() }
        val client = createClient { install(ContentNegotiation) { json(json) } }
        val seeded = CommentTestSeed.seedUser()
        val (session) = SessionService(AuthSessionDAO(), RefreshTokenGenerator()).createSession(
            credentialId = seeded.authId,
            scope = SessionScope.FULL,
            userId = seeded.userId,
            deviceId = null,
            deviceName = null,
            userAgent = null,
            ip = null,
        )
        val token = jwt.generateAccessToken(session, seeded.authId, seeded.email, seeded.userId)

        val familyId = ChallengeTestSeed.seedFamily()
        val windowEnd = Instant.now().minusSeconds(3600)
        ChallengeTestSeed.seedChallenge(familyId = familyId, startsAt = windowEnd.minusSeconds(3600), endsAt = windowEnd, requiredPosts = 1)

        val response = client.get("/api/challenges/current") { header(HttpHeaders.Authorization, "Bearer $token") }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<CurrentChallengeDTO>()
        assertNull(body.challenge, "an already-ended challenge is never 'current', regardless of the background finalization outcome")
    }

    // ---------- GET /challenges/{id}/progress ----------

    @Test
    fun `GET challenges id progress returns 404 for an unknown challenge`() = userTest { client, token, _ ->
        val response = client.get("/api/challenges/${UUID.randomUUID()}/progress") { header(HttpHeaders.Authorization, "Bearer $token") }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `GET challenges id progress returns 400 for a malformed id`() = userTest { client, token, _ ->
        val response = client.get("/api/challenges/not-a-uuid/progress") { header(HttpHeaders.Authorization, "Bearer $token") }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `GET challenges id progress returns progress and contributing posts for an existing challenge`() = userTest { client, token, userId ->
        val familyId = ChallengeTestSeed.seedFamily()
        val modelId = ChallengeTestSeed.seedModel("volkswagen", "golf r", familyId)
        val now = Instant.now()
        val challengeId = ChallengeTestSeed.seedChallenge(
            familyId = familyId, startsAt = now.minusSeconds(3600), endsAt = now.plusSeconds(3600), requiredPosts = 5,
        )
        val postId = ChallengeTestSeed.seedCameraPost(userId, modelId)
        com.revio.server.features.challenge.ChallengeProgressDAO().evaluatePostContribution(challengeId, userId, postId, modelId, now)

        val response = client.get("/api/challenges/$challengeId/progress") { header(HttpHeaders.Authorization, "Bearer $token") }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<ChallengeProgressDetailDTO>()
        assertEquals(1, body.progress.contributionCount)
        assertEquals(listOf(postId), body.contributions.map { it.postId })
    }

    @Test
    fun `GET challenges id progress includes imageUrl, carBrand and carModel for each contribution`() = userTest { client, token, userId ->
        val familyId = ChallengeTestSeed.seedFamily()
        val modelId = ChallengeTestSeed.seedModel("volkswagen", "golf r", familyId)
        val now = Instant.now()
        val challengeId = ChallengeTestSeed.seedChallenge(
            familyId = familyId, startsAt = now.minusSeconds(3600), endsAt = now.plusSeconds(3600), requiredPosts = 5,
        )
        val postId = ChallengeTestSeed.seedCameraPost(userId, modelId)
        com.revio.server.features.challenge.ChallengeProgressDAO().evaluatePostContribution(challengeId, userId, postId, modelId, now)

        val response = client.get("/api/challenges/$challengeId/progress") { header(HttpHeaders.Authorization, "Bearer $token") }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<ChallengeProgressDetailDTO>()
        val contribution = body.contributions.single()
        assertEquals("volkswagen", contribution.carBrand)
        assertEquals("golf r", contribution.carModel)
        assertNotNull(contribution.imageUrl)
        assertTrue(contribution.imageUrl!!.contains("posts/test.jpg"), "resolved URL must be built from the post's storage key")
    }

    @Test
    fun `GET challenges id progress response deserializes with a client that doesn't know imageUrl, carBrand or carModel`() = userTest { client, token, userId ->
        // Backward compatibility: a client built before Pas 7 has a DTO without the 3 new fields.
        // ignoreUnknownKeys mirrors how kotlinx.serialization is actually configured on both ends
        // (see this file's own `json` instance and ChallengeApi's Retrofit converter).
        val familyId = ChallengeTestSeed.seedFamily()
        val modelId = ChallengeTestSeed.seedModel("volkswagen", "golf r", familyId)
        val now = Instant.now()
        val challengeId = ChallengeTestSeed.seedChallenge(
            familyId = familyId, startsAt = now.minusSeconds(3600), endsAt = now.plusSeconds(3600), requiredPosts = 5,
        )
        val postId = ChallengeTestSeed.seedCameraPost(userId, modelId)
        com.revio.server.features.challenge.ChallengeProgressDAO().evaluatePostContribution(challengeId, userId, postId, modelId, now)

        val response = client.get("/api/challenges/$challengeId/progress") { header(HttpHeaders.Authorization, "Bearer $token") }
        val raw = response.bodyAsText()

        val legacy = json.decodeFromString(LegacyChallengeProgressDetailDTO.serializer(), raw)
        assertEquals(1, legacy.progress.contributionCount)
        assertEquals(listOf(postId.toString()), legacy.contributions.map { it.postId })
    }

    @Test
    fun `GET challenges id progress works for a challenge that is not the currently active one`() = userTest { client, token, _ ->
        // "not limited to the currently active one" per the route's own KDoc — a past, ENDED
        // challenge must still be readable.
        val familyId = ChallengeTestSeed.seedFamily()
        val now = Instant.now()
        val pastChallengeId = ChallengeTestSeed.seedChallenge(
            familyId = familyId, startsAt = now.minusSeconds(7200), endsAt = now.minusSeconds(3600),
            status = ChallengeStatus.SCHEDULED, title = "Last weekend",
        )

        val response = client.get("/api/challenges/$pastChallengeId/progress") { header(HttpHeaders.Authorization, "Bearer $token") }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<ChallengeProgressDetailDTO>()
        assertEquals(0, body.progress.contributionCount)
        assertEquals(emptyList<UUID>(), body.contributions.map { it.postId })
    }

    @Test
    fun `GET challenges id and id progress both return 404 for a DRAFT challenge`() = userTest { client, token, _ ->
        // D5: /{id}/progress now uses findPublicById, same as /{id} — a DRAFT (never published)
        // must 404 on both, not 200 on one and 404 on the other.
        val familyId = ChallengeTestSeed.seedFamily()
        val now = Instant.now()
        val draftId = ChallengeTestSeed.seedChallenge(
            familyId = familyId, startsAt = now.plusSeconds(3600), endsAt = now.plusSeconds(7200),
            status = ChallengeStatus.DRAFT,
        )

        val detailResponse = client.get("/api/challenges/$draftId") { header(HttpHeaders.Authorization, "Bearer $token") }
        val progressResponse = client.get("/api/challenges/$draftId/progress") { header(HttpHeaders.Authorization, "Bearer $token") }

        assertEquals(HttpStatusCode.NotFound, detailResponse.status)
        assertEquals(HttpStatusCode.NotFound, progressResponse.status)
    }

    @Test
    fun `GET challenges id progress without a token is unauthorized`() = testApplication {
        application { testChallengeModule() }
        val client = createClient { install(ContentNegotiation) { json(json) } }

        val response = client.get("/api/challenges/${UUID.randomUUID()}/progress")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // ---------- effectiveStatus on GET /challenges/{id} (plan §6 pas 4b) ----------
    // Unlike /current and /me, this route has no wrapper DTO to carry effectiveStatus as a
    // sibling field, so it's embedded directly in ChallengeDTO instead — see that DTO's KDoc.

    @Test
    fun `GET challenges id returns effectiveStatus CANCELLED for a cancelled challenge`() = userTest { client, token, _ ->
        val familyId = ChallengeTestSeed.seedFamily()
        val now = Instant.now()
        val challengeId = ChallengeTestSeed.seedChallenge(
            familyId = familyId, startsAt = now.minusSeconds(3600), endsAt = now.plusSeconds(3600),
            status = ChallengeStatus.CANCELLED,
        )

        val response = client.get("/api/challenges/$challengeId") { header(HttpHeaders.Authorization, "Bearer $token") }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<ChallengeDTO>()
        assertEquals("CANCELLED", body.effectiveStatus)
    }

    @Test
    fun `GET challenges id returns effectiveStatus ACTIVE for a challenge inside its window`() = userTest { client, token, _ ->
        val familyId = ChallengeTestSeed.seedFamily()
        val now = Instant.now()
        val challengeId = ChallengeTestSeed.seedChallenge(
            familyId = familyId, startsAt = now.minusSeconds(3600), endsAt = now.plusSeconds(3600),
        )

        val response = client.get("/api/challenges/$challengeId") { header(HttpHeaders.Authorization, "Bearer $token") }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<ChallengeDTO>()
        assertEquals("ACTIVE", body.effectiveStatus)
    }

    @Test
    fun `GET challenges id response deserializes with a client that doesn't know effectiveStatus`() = userTest { client, token, _ ->
        // Backward compatibility: effectiveStatus is new and additive — a client built before it
        // existed (LegacyChallengeDTO, below) must still decode this response.
        val familyId = ChallengeTestSeed.seedFamily()
        val now = Instant.now()
        val challengeId = ChallengeTestSeed.seedChallenge(
            familyId = familyId, startsAt = now.minusSeconds(3600), endsAt = now.plusSeconds(3600), title = "Weekend Golf Hunt",
        )

        val response = client.get("/api/challenges/$challengeId") { header(HttpHeaders.Authorization, "Bearer $token") }
        val raw = response.bodyAsText()

        val legacy = json.decodeFromString(LegacyChallengeDTO.serializer(), raw)
        assertEquals("Weekend Golf Hunt", legacy.title)
    }

    // ---------- participantState wiring (plan §7.2, D2) ----------

    private fun finalizationService() = com.revio.server.features.challenge.ChallengeFinalizationService(
        com.revio.server.features.challenge.ChallengeDAO(),
        com.revio.server.features.challenge.ChallengeProgressDAO(),
    )

    @Test
    fun `GET challenges current returns NOT_STARTED participantState for an upcoming challenge`() = userTest { client, token, _ ->
        val familyId = ChallengeTestSeed.seedFamily()
        val now = Instant.now()
        ChallengeTestSeed.seedChallenge(
            familyId = familyId, startsAt = now.plusSeconds(3600), endsAt = now.plusSeconds(7200),
            status = ChallengeStatus.SCHEDULED, title = "Next weekend",
        )

        val response = client.get("/api/challenges/current") { header(HttpHeaders.Authorization, "Bearer $token") }

        val body = response.body<CurrentChallengeDTO>()
        assertEquals("NOT_STARTED", body.progress?.participantState)
    }

    @Test
    fun `GET challenges current returns IN_PROGRESS participantState for an active challenge below threshold`() = userTest { client, token, _ ->
        val familyId = ChallengeTestSeed.seedFamily()
        val now = Instant.now()
        ChallengeTestSeed.seedChallenge(
            familyId = familyId, startsAt = now.minusSeconds(3600), endsAt = now.plusSeconds(3600),
            status = ChallengeStatus.SCHEDULED, requiredPosts = 5,
        )

        val response = client.get("/api/challenges/current") { header(HttpHeaders.Authorization, "Bearer $token") }

        val body = response.body<CurrentChallengeDTO>()
        assertEquals("IN_PROGRESS", body.progress?.participantState)
    }

    @Test
    fun `GET challenges id progress returns COMPLETED_PENDING participantState once the threshold is reached before finalization`() = userTest { client, token, userId ->
        val familyId = ChallengeTestSeed.seedFamily()
        val modelId = ChallengeTestSeed.seedModel("volkswagen", "golf r", familyId)
        val now = Instant.now()
        val challengeId = ChallengeTestSeed.seedChallenge(
            familyId = familyId, startsAt = now.minusSeconds(3600), endsAt = now.plusSeconds(3600), requiredPosts = 1,
        )
        val postId = ChallengeTestSeed.seedCameraPost(userId, modelId)
        com.revio.server.features.challenge.ChallengeProgressDAO().evaluatePostContribution(challengeId, userId, postId, modelId, now)

        val response = client.get("/api/challenges/$challengeId/progress") { header(HttpHeaders.Authorization, "Bearer $token") }

        val body = response.body<ChallengeProgressDetailDTO>()
        assertEquals("COMPLETED_PENDING", body.progress.participantState)
    }

    @Test
    fun `GET challenges id progress returns REWARDED participantState after finalization grants the reward`() = userTest { client, token, userId ->
        val familyId = ChallengeTestSeed.seedFamily()
        val modelId = ChallengeTestSeed.seedModel("volkswagen", "golf r", familyId)
        val windowEnd = Instant.now().minusSeconds(3600)
        val windowStart = windowEnd.minusSeconds(3600)
        val challengeId = ChallengeTestSeed.seedChallenge(
            familyId = familyId, startsAt = windowStart, endsAt = windowEnd, requiredPosts = 1,
        )
        val postId = ChallengeTestSeed.seedCameraPost(userId, modelId)
        com.revio.server.features.challenge.ChallengeProgressDAO()
            .evaluatePostContribution(challengeId, userId, postId, modelId, windowStart.plusSeconds(60))
        finalizationService().finalize(challengeId, Instant.now())

        val response = client.get("/api/challenges/$challengeId/progress") { header(HttpHeaders.Authorization, "Bearer $token") }

        val body = response.body<ChallengeProgressDetailDTO>()
        assertEquals("REWARDED", body.progress.participantState)
    }

    @Test
    fun `GET challenges id progress returns NOT_COMPLETED participantState after finalization when the threshold was never reached`() = userTest { client, token, _ ->
        val familyId = ChallengeTestSeed.seedFamily()
        val windowEnd = Instant.now().minusSeconds(3600)
        val windowStart = windowEnd.minusSeconds(3600)
        val challengeId = ChallengeTestSeed.seedChallenge(
            familyId = familyId, startsAt = windowStart, endsAt = windowEnd, requiredPosts = 5,
        )
        finalizationService().finalize(challengeId, Instant.now())

        val response = client.get("/api/challenges/$challengeId/progress") { header(HttpHeaders.Authorization, "Bearer $token") }

        val body = response.body<ChallengeProgressDetailDTO>()
        assertEquals("NOT_COMPLETED", body.progress.participantState)
    }

    @Test
    fun `GET challenges id progress returns CANCELLED participantState for a cancelled challenge`() = userTest { client, token, _ ->
        val familyId = ChallengeTestSeed.seedFamily()
        val now = Instant.now()
        val challengeId = ChallengeTestSeed.seedChallenge(
            familyId = familyId, startsAt = now.minusSeconds(3600), endsAt = now.plusSeconds(3600),
            status = ChallengeStatus.CANCELLED,
        )

        val response = client.get("/api/challenges/$challengeId/progress") { header(HttpHeaders.Authorization, "Bearer $token") }

        val body = response.body<ChallengeProgressDetailDTO>()
        assertEquals("CANCELLED", body.progress.participantState)
    }

    @Test
    fun `GET challenges id progress response deserializes with a client that doesn't know participantState`() = userTest { client, token, userId ->
        // Backward compatibility for D2: participantState is new and additive — a client built
        // before it existed (LegacyChallengeProgressDTO, below) must still decode this response.
        val familyId = ChallengeTestSeed.seedFamily()
        val modelId = ChallengeTestSeed.seedModel("volkswagen", "golf r", familyId)
        val now = Instant.now()
        val challengeId = ChallengeTestSeed.seedChallenge(
            familyId = familyId, startsAt = now.minusSeconds(3600), endsAt = now.plusSeconds(3600), requiredPosts = 5,
        )
        val postId = ChallengeTestSeed.seedCameraPost(userId, modelId)
        com.revio.server.features.challenge.ChallengeProgressDAO().evaluatePostContribution(challengeId, userId, postId, modelId, now)

        val response = client.get("/api/challenges/$challengeId/progress") { header(HttpHeaders.Authorization, "Bearer $token") }
        val raw = response.bodyAsText()

        val legacy = json.decodeFromString(LegacyChallengeProgressDetailDTO.serializer(), raw)
        assertEquals(1, legacy.progress.contributionCount)
        assertEquals("NONE", legacy.progress.rewardState)
    }
}

// ---------- Pre-Pas-7 wire shape, for the backward-compatibility test above ----------

@Serializable
private data class LegacyChallengeContributionDTO(
    val postId: String,
    val createdAt: String,
)

@Serializable
private data class LegacyChallengeProgressDTO(
    val contributionCount: Int,
    val rewardState: String,
)

@Serializable
private data class LegacyChallengeProgressDetailDTO(
    val progress: LegacyChallengeProgressDTO,
    val contributions: List<LegacyChallengeContributionDTO>,
)

// ---------- Pre-pas-4b wire shape (no effectiveStatus), for the backward-compatibility test above ----------

@Serializable
private data class LegacyChallengeDTO(
    val id: String,
    val title: String,
    val description: String?,
    val targetFamilyBrand: String,
    val targetFamilyName: String,
    val requiredPosts: Int,
    val rewardPoints: Int,
    val startsAt: String,
    val endsAt: String,
)
