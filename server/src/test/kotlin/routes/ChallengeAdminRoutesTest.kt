package routes

import com.revio.server.features.auth.JwtService
import com.revio.server.features.auth.RefreshTokenGenerator
import com.revio.server.features.auth.session.AuthSessionDAO
import com.revio.server.features.auth.session.SessionScope
import com.revio.server.features.auth.session.SessionService
import com.revio.server.features.car_family.CarFamilyAdminDTO
import com.revio.server.features.car_family.CreateCarFamilyRequest
import com.revio.server.features.challenge.ChallengeAdminDTO
import com.revio.server.features.challenge.ChallengeAdminPageDTO
import com.revio.server.features.challenge.ChallengeContributionTable
import com.revio.server.features.challenge.ChallengeParticipantTable
import com.revio.server.features.challenge.CreateChallengeAdminRequest
import com.revio.server.features.challenge.FinalizationHealthDTO
import com.revio.server.features.challenge.FinalizationResultDTO
import com.revio.server.features.challenge.FinalizeDueResultDTO
import com.revio.server.features.challenge.RewardState
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.insert
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
import testutils.ChallengeTestSeed
import testutils.CommentTestSeed
import testutils.TestDatabaseFactory
import testutils.TestEnv
import testutils.setTestEnv
import testutils.stopKoinSafely
import testutils.testChallengeAdminModule
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * HTTP contract of GET /api/admin/challenges (list, keyset pagination, effective-status filter —
 * plan §9-E5) and PUT /api/admin/challenges/{id} (full DRAFT edit — plan §9-E3). DAO-level
 * pagination/filter correctness is covered by dao.ChallengeDaoListAllTest; these tests are about
 * the route's request/response contract.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChallengeAdminRoutesTest {

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

    private fun adminTest(block: suspend ApplicationTestBuilder.(HttpClient, String) -> Unit) = testApplication {
        application { testChallengeAdminModule() }
        val client = createClient { install(ContentNegotiation) { json(json) } }
        block(client, adminToken())
    }

    private val validCronSecret = "test-cron-secret"

    /** Like [adminTest], but wires [cronSecret] (or leaves it unset) for POST .../finalize-due. */
    private fun cronTest(cronSecret: String?, block: suspend ApplicationTestBuilder.(HttpClient, String) -> Unit) = testApplication {
        application { testChallengeAdminModule(cronSecret) }
        val client = createClient { install(ContentNegotiation) { json(json) } }
        block(client, adminToken())
    }

    private suspend fun adminToken(): String {
        val seeded = CommentTestSeed.seedUser(username = "moderator")
        val (session) = SessionService(AuthSessionDAO(), RefreshTokenGenerator()).createSession(
            credentialId = seeded.authId,
            scope = SessionScope.FULL,
            userId = seeded.userId,
            deviceId = null,
            deviceName = null,
            userAgent = null,
            ip = null,
        )
        return jwt.generateAccessToken(session, seeded.authId, seeded.email, seeded.userId, isAdmin = true)
    }

    private suspend fun HttpClient.createFamily(token: String, brand: String = "volkswagen", name: String = "Golf"): CarFamilyAdminDTO {
        val response = post("/api/admin/car-families") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(CreateCarFamilyRequest.serializer(), CreateCarFamilyRequest(brand, name)))
        }
        return response.body()
    }

    private fun challengeRequest(
        title: String = "Weekend Golf Hunt",
        familyId: java.util.UUID,
        startsAtLocal: String = "2026-08-08T09:00:00",
        endsAtLocal: String = "2026-08-10T09:00:00",
    ) = CreateChallengeAdminRequest(
        title = title,
        description = "Spot 5 Golfs",
        targetFamilyId = familyId,
        requiredPosts = 5,
        rewardPoints = 300,
        startsAtLocal = startsAtLocal,
        endsAtLocal = endsAtLocal,
        timezone = "Europe/Bucharest",
    )

    private suspend fun HttpClient.createChallenge(token: String, req: CreateChallengeAdminRequest): ChallengeAdminDTO {
        val response = post("/api/admin/challenges") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(CreateChallengeAdminRequest.serializer(), req))
        }
        return response.body()
    }

    // ---------- GET /admin/challenges ----------

    @Test
    fun `GET admin challenges returns an empty page when none exist`() = adminTest { client, token ->
        val response = client.get("/api/admin/challenges") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val page = response.body<ChallengeAdminPageDTO>()
        assertTrue(page.challenges.isEmpty())
        assertEquals(false, page.hasMore)
        assertNull(page.nextCursor)
    }

    @Test
    fun `GET admin challenges paginates newest-first with a stable cursor`() = adminTest { client, token ->
        val family = client.createFamily(token)
        client.createChallenge(token, challengeRequest(title = "A", familyId = family.id, startsAtLocal = "2026-08-01T09:00:00", endsAtLocal = "2026-08-02T09:00:00"))
        client.createChallenge(token, challengeRequest(title = "B", familyId = family.id, startsAtLocal = "2026-08-03T09:00:00", endsAtLocal = "2026-08-04T09:00:00"))
        client.createChallenge(token, challengeRequest(title = "C", familyId = family.id, startsAtLocal = "2026-08-05T09:00:00", endsAtLocal = "2026-08-06T09:00:00"))

        val firstResponse = client.get("/api/admin/challenges?limit=2") { header(HttpHeaders.Authorization, "Bearer $token") }
        val firstPage = firstResponse.body<ChallengeAdminPageDTO>()
        assertEquals(2, firstPage.challenges.size)
        assertEquals(true, firstPage.hasMore)
        assertNotNull(firstPage.nextCursor)

        val cursor = firstPage.nextCursor!!
        val secondResponse = client.get(
            "/api/admin/challenges?limit=2&cursorCreatedAt=${cursor.lastCreatedAt}&cursorId=${cursor.lastChallengeId}",
        ) { header(HttpHeaders.Authorization, "Bearer $token") }
        val secondPage = secondResponse.body<ChallengeAdminPageDTO>()
        assertEquals(1, secondPage.challenges.size)
        assertEquals(false, secondPage.hasMore)

        val allTitles = (firstPage.challenges + secondPage.challenges).map { it.title }
        assertEquals(listOf("C", "B", "A"), allTitles)
    }

    @Test
    fun `GET admin challenges filters by effective status`() = adminTest { client, token ->
        val family = client.createFamily(token)
        val draft = client.createChallenge(token, challengeRequest(familyId = family.id))
        val published = client.createChallenge(
            token,
            challengeRequest(title = "Published", familyId = family.id, startsAtLocal = "2027-01-01T09:00:00", endsAtLocal = "2027-01-02T09:00:00"),
        )
        client.post("/api/admin/challenges/${published.id}/publish") { header(HttpHeaders.Authorization, "Bearer $token") }

        val draftPage = client.get("/api/admin/challenges?status=DRAFT") { header(HttpHeaders.Authorization, "Bearer $token") }
            .body<ChallengeAdminPageDTO>()
        assertEquals(listOf(draft.id), draftPage.challenges.map { it.id })

        val scheduledPage = client.get("/api/admin/challenges?status=SCHEDULED") { header(HttpHeaders.Authorization, "Bearer $token") }
            .body<ChallengeAdminPageDTO>()
        assertEquals(listOf(published.id), scheduledPage.challenges.map { it.id })
    }

    @Test
    fun `GET admin challenges rejects an invalid status`() = adminTest { client, token ->
        val response = client.get("/api/admin/challenges?status=NOT_A_STATUS") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `GET admin challenges rejects a malformed cursor`() = adminTest { client, token ->
        val response = client.get("/api/admin/challenges?cursorCreatedAt=not-an-instant&cursorId=${java.util.UUID.randomUUID()}") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ---------- PUT /admin/challenges/{id} ----------

    @Test
    fun `PUT admin challenges replaces every field while DRAFT`() = adminTest { client, token ->
        val family = client.createFamily(token)
        val otherFamily = client.createFamily(token, name = "ID")
        val created = client.createChallenge(token, challengeRequest(familyId = family.id))

        val response = client.put("/api/admin/challenges/${created.id}") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    CreateChallengeAdminRequest.serializer(),
                    challengeRequest(title = "Updated title", familyId = otherFamily.id, startsAtLocal = "2026-09-01T09:00:00", endsAtLocal = "2026-09-03T09:00:00"),
                ),
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val updated = response.body<ChallengeAdminDTO>()
        assertEquals("Updated title", updated.title)
        assertEquals(otherFamily.id, updated.targetFamilyId)

        val reloaded = client.get("/api/admin/challenges/${created.id}") { header(HttpHeaders.Authorization, "Bearer $token") }
            .body<ChallengeAdminDTO>()
        assertEquals("Updated title", reloaded.title)
        assertEquals(otherFamily.id, reloaded.targetFamilyId)
    }

    @Test
    fun `PUT admin challenges returns 404 for an unknown id`() = adminTest { client, token ->
        val family = client.createFamily(token)
        val response = client.put("/api/admin/challenges/${java.util.UUID.randomUUID()}") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(CreateChallengeAdminRequest.serializer(), challengeRequest(familyId = family.id)))
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `PUT admin challenges returns 409 once the challenge is no longer DRAFT`() = adminTest { client, token ->
        val family = client.createFamily(token)
        val created = client.createChallenge(token, challengeRequest(familyId = family.id))
        client.post("/api/admin/challenges/${created.id}/publish") { header(HttpHeaders.Authorization, "Bearer $token") }

        val response = client.put("/api/admin/challenges/${created.id}") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(CreateChallengeAdminRequest.serializer(), challengeRequest(title = "New", familyId = family.id)))
        }
        assertEquals(HttpStatusCode.Conflict, response.status)

        // The rejected edit must not have applied.
        val reloaded = client.get("/api/admin/challenges/${created.id}") { header(HttpHeaders.Authorization, "Bearer $token") }
            .body<ChallengeAdminDTO>()
        assertEquals(created.title, reloaded.title)
    }

    @Test
    fun `PUT admin challenges rejects an invalid window with 400 and applies nothing`() = adminTest { client, token ->
        val family = client.createFamily(token)
        val created = client.createChallenge(token, challengeRequest(familyId = family.id))

        val response = client.put("/api/admin/challenges/${created.id}") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    CreateChallengeAdminRequest.serializer(),
                    challengeRequest(title = "Bad window", familyId = family.id, startsAtLocal = "2026-09-03T09:00:00", endsAtLocal = "2026-09-01T09:00:00"),
                ),
            )
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)

        val reloaded = client.get("/api/admin/challenges/${created.id}") { header(HttpHeaders.Authorization, "Bearer $token") }
            .body<ChallengeAdminDTO>()
        assertEquals(created.title, reloaded.title)
    }

    @Test
    fun `PUT admin challenges rejects a target family that doesn't exist`() = adminTest { client, token ->
        val family = client.createFamily(token)
        val created = client.createChallenge(token, challengeRequest(familyId = family.id))

        val response = client.put("/api/admin/challenges/${created.id}") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    CreateChallengeAdminRequest.serializer(),
                    challengeRequest(familyId = java.util.UUID.randomUUID()),
                ),
            )
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ---------- POST /admin/challenges (creation) ----------

    @Test
    fun `POST admin challenges creates a DRAFT challenge`() = adminTest { client, token ->
        val family = client.createFamily(token)

        val response = client.post("/api/admin/challenges") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(CreateChallengeAdminRequest.serializer(), challengeRequest(familyId = family.id)))
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val created = response.body<ChallengeAdminDTO>()
        assertEquals("DRAFT", created.status)
        assertEquals(family.id, created.targetFamilyId)
    }

    @Test
    fun `POST admin challenges rejects an unknown targetFamilyId with 400`() = adminTest { client, token ->
        val response = client.post("/api/admin/challenges") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(CreateChallengeAdminRequest.serializer(), challengeRequest(familyId = java.util.UUID.randomUUID())))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST admin challenges rejects an invalid IANA timezone with 400, never falling back to UTC`() = adminTest { client, token ->
        val family = client.createFamily(token)

        val response = client.post("/api/admin/challenges") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    CreateChallengeAdminRequest.serializer(),
                    challengeRequest(familyId = family.id).copy(timezone = "Europe/Bucuresti"),
                ),
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST admin challenges rejects non-positive requiredPosts and rewardPoints with 400`() = adminTest { client, token ->
        val family = client.createFamily(token)

        val badRequiredPosts = client.post("/api/admin/challenges") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(CreateChallengeAdminRequest.serializer(), challengeRequest(familyId = family.id).copy(requiredPosts = 0)))
        }
        assertEquals(HttpStatusCode.BadRequest, badRequiredPosts.status)

        val badRewardPoints = client.post("/api/admin/challenges") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(CreateChallengeAdminRequest.serializer(), challengeRequest(familyId = family.id).copy(rewardPoints = 0)))
        }
        assertEquals(HttpStatusCode.BadRequest, badRewardPoints.status)
    }

    // ---------- POST /admin/challenges/{id}/publish ----------

    @Test
    fun `POST publish moves a DRAFT challenge to SCHEDULED`() = adminTest { client, token ->
        val family = client.createFamily(token)
        val created = client.createChallenge(token, challengeRequest(familyId = family.id))

        val response = client.post("/api/admin/challenges/${created.id}/publish") { header(HttpHeaders.Authorization, "Bearer $token") }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("SCHEDULED", response.body<ChallengeAdminDTO>().status)
    }

    @Test
    fun `POST publish returns 409 when the window overlaps another SCHEDULED challenge`() = adminTest { client, token ->
        val family = client.createFamily(token)
        val first = client.createChallenge(token, challengeRequest(title = "First", familyId = family.id, startsAtLocal = "2026-08-08T09:00:00", endsAtLocal = "2026-08-10T09:00:00"))
        client.post("/api/admin/challenges/${first.id}/publish") { header(HttpHeaders.Authorization, "Bearer $token") }
        val overlapping = client.createChallenge(token, challengeRequest(title = "Overlapping", familyId = family.id, startsAtLocal = "2026-08-09T09:00:00", endsAtLocal = "2026-08-11T09:00:00"))

        val response = client.post("/api/admin/challenges/${overlapping.id}/publish") { header(HttpHeaders.Authorization, "Bearer $token") }

        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    @Test
    fun `POST publish allows two challenges with adjacent (non-overlapping) windows`() = adminTest { client, token ->
        // endsAt of the first == startsAt of the second — the semi-open interval [starts, ends)
        // means these do NOT overlap.
        val family = client.createFamily(token)
        val first = client.createChallenge(token, challengeRequest(title = "First", familyId = family.id, startsAtLocal = "2026-08-08T09:00:00", endsAtLocal = "2026-08-10T09:00:00"))
        client.post("/api/admin/challenges/${first.id}/publish") { header(HttpHeaders.Authorization, "Bearer $token") }
        val adjacent = client.createChallenge(token, challengeRequest(title = "Adjacent", familyId = family.id, startsAtLocal = "2026-08-10T09:00:00", endsAtLocal = "2026-08-12T09:00:00"))

        val response = client.post("/api/admin/challenges/${adjacent.id}/publish") { header(HttpHeaders.Authorization, "Bearer $token") }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `POST publish returns 404 for an unknown id`() = adminTest { client, token ->
        val response = client.post("/api/admin/challenges/${java.util.UUID.randomUUID()}/publish") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ---------- POST /admin/challenges/{id}/cancel ----------

    @Test
    fun `POST cancel a SCHEDULED, not-yet-ended challenge sets status CANCELLED with zero revocations`() = adminTest { client, token ->
        val family = client.createFamily(token)
        val created = client.createChallenge(token, challengeRequest(familyId = family.id, startsAtLocal = "2026-08-08T09:00:00", endsAtLocal = "2026-08-10T09:00:00"))
        client.post("/api/admin/challenges/${created.id}/publish") { header(HttpHeaders.Authorization, "Bearer $token") }

        val response = client.post("/api/admin/challenges/${created.id}/cancel") { header(HttpHeaders.Authorization, "Bearer $token") }

        assertEquals(HttpStatusCode.OK, response.status)
        val reloaded = client.get("/api/admin/challenges/${created.id}") { header(HttpHeaders.Authorization, "Bearer $token") }
            .body<ChallengeAdminDTO>()
        assertEquals("CANCELLED", reloaded.status)
    }

    @Test
    fun `POST cancel a DRAFT challenge is permitted`() = adminTest { client, token ->
        val family = client.createFamily(token)
        val created = client.createChallenge(token, challengeRequest(familyId = family.id))

        val response = client.post("/api/admin/challenges/${created.id}/cancel") { header(HttpHeaders.Authorization, "Bearer $token") }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `POST cancel an already-ended challenge returns 409 pointing at revoke-all`() = adminTest { client, token ->
        val family = client.createFamily(token)
        // A window entirely in the past — publish succeeds (nothing to overlap with), but the
        // challenge is already effectively ENDED by the time cancel is attempted.
        val created = client.createChallenge(token, challengeRequest(familyId = family.id, startsAtLocal = "2020-01-01T09:00:00", endsAtLocal = "2020-01-02T09:00:00"))
        client.post("/api/admin/challenges/${created.id}/publish") { header(HttpHeaders.Authorization, "Bearer $token") }

        val response = client.post("/api/admin/challenges/${created.id}/cancel") { header(HttpHeaders.Authorization, "Bearer $token") }

        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    @Test
    fun `POST cancel is idempotent on a second call`() = adminTest { client, token ->
        val family = client.createFamily(token)
        val created = client.createChallenge(token, challengeRequest(familyId = family.id, startsAtLocal = "2026-08-08T09:00:00", endsAtLocal = "2026-08-10T09:00:00"))
        client.post("/api/admin/challenges/${created.id}/publish") { header(HttpHeaders.Authorization, "Bearer $token") }
        client.post("/api/admin/challenges/${created.id}/cancel") { header(HttpHeaders.Authorization, "Bearer $token") }

        val response = client.post("/api/admin/challenges/${created.id}/cancel") { header(HttpHeaders.Authorization, "Bearer $token") }

        assertEquals(HttpStatusCode.OK, response.status)
        val reloaded = client.get("/api/admin/challenges/${created.id}") { header(HttpHeaders.Authorization, "Bearer $token") }
            .body<ChallengeAdminDTO>()
        assertEquals("CANCELLED", reloaded.status)
    }

    @Test
    fun `POST cancel returns 404 for an unknown id`() = adminTest { client, token ->
        val response = client.post("/api/admin/challenges/${java.util.UUID.randomUUID()}/cancel") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ---------- POST /admin/challenges/{id}/revoke-all ----------

    private suspend fun HttpClient.revokeAll(token: String, id: java.util.UUID, confirmId: java.util.UUID = id) =
        post("/api/admin/challenges/$id/revoke-all") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"confirmChallengeId":"$confirmId"}""")
        }

    @Test
    fun `POST revoke-all rejects a confirmChallengeId that doesn't match the path id`() = adminTest { client, token ->
        val family = client.createFamily(token)
        val created = client.createChallenge(token, challengeRequest(familyId = family.id))

        val response = client.revokeAll(token, created.id, confirmId = java.util.UUID.randomUUID())

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST revoke-all works regardless of ends_at and does not change status`() = adminTest { client, token ->
        val family = client.createFamily(token)
        // Already-ended window, unlike cancel — revoke-all is permitted after endsAt.
        val created = client.createChallenge(token, challengeRequest(familyId = family.id, startsAtLocal = "2020-01-01T09:00:00", endsAtLocal = "2020-01-02T09:00:00"))
        client.post("/api/admin/challenges/${created.id}/publish") { header(HttpHeaders.Authorization, "Bearer $token") }

        val response = client.revokeAll(token, created.id)

        assertEquals(HttpStatusCode.OK, response.status)
        val reloaded = client.get("/api/admin/challenges/${created.id}") { header(HttpHeaders.Authorization, "Bearer $token") }
            .body<ChallengeAdminDTO>()
        assertEquals("SCHEDULED", reloaded.status, "revoke-all must never flip the challenge to CANCELLED")
    }

    @Test
    fun `POST revoke-all is idempotent - repeated calls report zero revocations`() = adminTest { client, token ->
        val family = client.createFamily(token)
        val created = client.createChallenge(token, challengeRequest(familyId = family.id, startsAtLocal = "2020-01-01T09:00:00", endsAtLocal = "2020-01-02T09:00:00"))
        client.post("/api/admin/challenges/${created.id}/publish") { header(HttpHeaders.Authorization, "Bearer $token") }
        client.revokeAll(token, created.id)

        val response = client.revokeAll(token, created.id)

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `POST revoke-all returns 404 for an unknown id`() = adminTest { client, token ->
        val id = java.util.UUID.randomUUID()
        val response = client.revokeAll(token, id)
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ---------- POST /admin/challenges/{id}/finalize ----------

    @Test
    fun `POST finalize returns 404 for an unknown id`() = adminTest { client, token ->
        val response = client.post("/api/admin/challenges/${java.util.UUID.randomUUID()}/finalize") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `POST finalize on a challenge that hasn't ended yet is a no-op reporting zero counts`() = adminTest { client, token ->
        val family = client.createFamily(token)
        val startsAtLocal = java.time.LocalDateTime.now().minusHours(1).withNano(0).toString()
        val endsAtLocal = java.time.LocalDateTime.now().plusDays(2).withNano(0).toString()
        val created = client.createChallenge(token, challengeRequest(familyId = family.id, startsAtLocal = startsAtLocal, endsAtLocal = endsAtLocal))
        client.post("/api/admin/challenges/${created.id}/publish") { header(HttpHeaders.Authorization, "Bearer $token") }

        val response = client.post("/api/admin/challenges/${created.id}/finalize") { header(HttpHeaders.Authorization, "Bearer $token") }

        assertEquals(HttpStatusCode.OK, response.status)
        val result = response.body<FinalizationResultDTO>()
        assertEquals(FinalizationResultDTO(grantedCount = 0, revokedCount = 0), result)
        val reloaded = client.get("/api/admin/challenges/${created.id}") { header(HttpHeaders.Authorization, "Bearer $token") }
            .body<ChallengeAdminDTO>()
        assertEquals("SCHEDULED", reloaded.status, "finalize on an active challenge must not touch its status")
    }

    @Test
    fun `POST finalize on a due challenge is idempotent - a repeated call still returns 200 with zero counts`() = adminTest { client, token ->
        val family = client.createFamily(token)
        // Already-ended window, so the challenge is SCHEDULED-and-due for finalization.
        val created = client.createChallenge(token, challengeRequest(familyId = family.id, startsAtLocal = "2020-01-01T09:00:00", endsAtLocal = "2020-01-02T09:00:00"))
        client.post("/api/admin/challenges/${created.id}/publish") { header(HttpHeaders.Authorization, "Bearer $token") }

        val first = client.post("/api/admin/challenges/${created.id}/finalize") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(FinalizationResultDTO(grantedCount = 0, revokedCount = 0), first.body<FinalizationResultDTO>())

        val second = client.post("/api/admin/challenges/${created.id}/finalize") { header(HttpHeaders.Authorization, "Bearer $token") }

        assertEquals(HttpStatusCode.OK, second.status)
        assertEquals(FinalizationResultDTO(grantedCount = 0, revokedCount = 0), second.body<FinalizationResultDTO>())
    }

    @Test
    fun `GET admin challenges id has a null finalizedAt for a published, not-yet-ended challenge`() = adminTest { client, token ->
        val family = client.createFamily(token)
        val startsAtLocal = java.time.LocalDateTime.now().minusHours(1).withNano(0).toString()
        val endsAtLocal = java.time.LocalDateTime.now().plusDays(2).withNano(0).toString()
        val created = client.createChallenge(token, challengeRequest(familyId = family.id, startsAtLocal = startsAtLocal, endsAtLocal = endsAtLocal))
        client.post("/api/admin/challenges/${created.id}/publish") { header(HttpHeaders.Authorization, "Bearer $token") }

        val reloaded = client.get("/api/admin/challenges/${created.id}") { header(HttpHeaders.Authorization, "Bearer $token") }
            .body<ChallengeAdminDTO>()

        assertNull(reloaded.finalizedAt)
    }

    @Test
    fun `GET admin challenges id has a non-null finalizedAt after finalize reconciles a due challenge`() = adminTest { client, token ->
        val family = client.createFamily(token)
        // Already-ended window, so the challenge is SCHEDULED-and-due for finalization.
        val created = client.createChallenge(token, challengeRequest(familyId = family.id, startsAtLocal = "2020-01-01T09:00:00", endsAtLocal = "2020-01-02T09:00:00"))
        client.post("/api/admin/challenges/${created.id}/publish") { header(HttpHeaders.Authorization, "Bearer $token") }
        client.post("/api/admin/challenges/${created.id}/finalize") { header(HttpHeaders.Authorization, "Bearer $token") }

        val reloaded = client.get("/api/admin/challenges/${created.id}") { header(HttpHeaders.Authorization, "Bearer $token") }
            .body<ChallengeAdminDTO>()

        assertNotNull(reloaded.finalizedAt)
    }

    // ---------- POST /admin/challenges/finalize-due (cron) ----------

    /** Inserts a participant row directly, bypassing evaluatePostContribution's own grant/revoke. */
    private fun seedParticipant(challengeId: java.util.UUID, userId: java.util.UUID, rewardState: RewardState, contributionCountCache: Int) = transaction {
        ChallengeParticipantTable.insert {
            it[ChallengeParticipantTable.challengeId] = challengeId
            it[ChallengeParticipantTable.userId] = userId
            it[ChallengeParticipantTable.contributionCount] = contributionCountCache
            it[ChallengeParticipantTable.rewardState] = rewardState
        }
    }

    /** Inserts one real contribution row (with a real backing post) directly. */
    private fun seedContribution(challengeId: java.util.UUID, userId: java.util.UUID, modelId: java.util.UUID) = transaction {
        val postId = ChallengeTestSeed.seedCameraPost(userId, modelId)
        ChallengeContributionTable.insert {
            it[ChallengeContributionTable.challengeId] = challengeId
            it[ChallengeContributionTable.userId] = userId
            it[ChallengeContributionTable.postId] = postId
            it[ChallengeContributionTable.carModelId] = modelId
            it[ChallengeContributionTable.postCreatedAt] = Instant.now().atOffset(ZoneOffset.UTC)
        }
    }

    @Test
    fun `POST finalize-due without a secret returns 401`() = cronTest(validCronSecret) { client, _ ->
        val response = client.post("/api/admin/challenges/finalize-due")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST finalize-due with the wrong secret returns 401`() = cronTest(validCronSecret) { client, _ ->
        val response = client.post("/api/admin/challenges/finalize-due") { header("X-Cron-Secret", "wrong-secret") }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST finalize-due returns 401 when CRON_SECRET is not configured, even with a secret header sent`() = cronTest(null) { client, _ ->
        val response = client.post("/api/admin/challenges/finalize-due") { header("X-Cron-Secret", "anything") }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST finalize-due with the correct secret finalizes every due challenge and reports correct counters`() = cronTest(validCronSecret) { client, token ->
        val family = client.createFamily(token)
        val modelId = ChallengeTestSeed.seedModel("volkswagen", "golf r", family.id)
        val user = CommentTestSeed.seedUser(username = "spotter")

        // A: due, one participant who reached the threshold (requiredPosts=5) but is still NONE -> should grant.
        val toGrant = client.createChallenge(token, challengeRequest(familyId = family.id, startsAtLocal = "2020-01-01T09:00:00", endsAtLocal = "2020-01-02T09:00:00"))
        client.post("/api/admin/challenges/${toGrant.id}/publish") { header(HttpHeaders.Authorization, "Bearer $token") }
        repeat(5) { seedContribution(toGrant.id, user.userId, modelId) }
        seedParticipant(toGrant.id, user.userId, RewardState.NONE, contributionCountCache = 5)

        // B: due, one participant GRANTED but with zero real contributions -> should revoke.
        val toRevoke = client.createChallenge(token, challengeRequest(familyId = family.id, startsAtLocal = "2020-02-01T09:00:00", endsAtLocal = "2020-02-02T09:00:00"))
        client.post("/api/admin/challenges/${toRevoke.id}/publish") { header(HttpHeaders.Authorization, "Bearer $token") }
        seedParticipant(toRevoke.id, user.userId, RewardState.GRANTED, contributionCountCache = 1)

        // C: due, no participants -> counted as finalized, contributes zero to both counters.
        val untouched = client.createChallenge(token, challengeRequest(familyId = family.id, startsAtLocal = "2020-03-01T09:00:00", endsAtLocal = "2020-03-02T09:00:00"))
        client.post("/api/admin/challenges/${untouched.id}/publish") { header(HttpHeaders.Authorization, "Bearer $token") }

        // D: not due (still active) -> must not be counted at all.
        val active = client.createChallenge(
            token,
            challengeRequest(
                familyId = family.id,
                startsAtLocal = LocalDateTime.now().minusHours(1).withNano(0).toString(),
                endsAtLocal = LocalDateTime.now().plusDays(2).withNano(0).toString(),
            ),
        )
        client.post("/api/admin/challenges/${active.id}/publish") { header(HttpHeaders.Authorization, "Bearer $token") }

        val response = client.post("/api/admin/challenges/finalize-due") { header("X-Cron-Secret", validCronSecret) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            FinalizeDueResultDTO(finalizedChallenges = 3, grantedRewards = 1, revokedRewards = 1),
            response.body<FinalizeDueResultDTO>(),
        )
    }

    // ---------- GET /admin/challenges/finalization-health (plan §9-K3 alerting probe) ----------

    @Test
    fun `GET finalization-health without a secret returns 401`() = cronTest(validCronSecret) { client, _ ->
        val response = client.get("/api/admin/challenges/finalization-health")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET finalization-health with the wrong secret returns 401`() = cronTest(validCronSecret) { client, _ ->
        val response = client.get("/api/admin/challenges/finalization-health") { header("X-Cron-Secret", "wrong-secret") }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET finalization-health returns 200 with no stuck challenges when nothing is overdue past the threshold`() =
        cronTest(validCronSecret) { client, token ->
            val family = client.createFamily(token)

            // Ended 2h ago - due for finalize-due, but well under the 6h stuck threshold.
            val recentlyEnded = client.createChallenge(
                token,
                challengeRequest(
                    familyId = family.id,
                    startsAtLocal = LocalDateTime.now().minusDays(1).withNano(0).toString(),
                    endsAtLocal = LocalDateTime.now().minusHours(2).withNano(0).toString(),
                ),
            )
            client.post("/api/admin/challenges/${recentlyEnded.id}/publish") { header(HttpHeaders.Authorization, "Bearer $token") }

            val response = client.get("/api/admin/challenges/finalization-health") { header("X-Cron-Secret", validCronSecret) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.body<FinalizationHealthDTO>().stuckChallenges.isEmpty())
        }

    @Test
    fun `GET finalization-health returns 503 listing challenges overdue past the 6h threshold`() =
        cronTest(validCronSecret) { client, token ->
            val family = client.createFamily(token)
            val overdue = client.createChallenge(token, challengeRequest(familyId = family.id, startsAtLocal = "2020-01-01T09:00:00", endsAtLocal = "2020-01-02T09:00:00"))
            client.post("/api/admin/challenges/${overdue.id}/publish") { header(HttpHeaders.Authorization, "Bearer $token") }

            val response = client.get("/api/admin/challenges/finalization-health") { header("X-Cron-Secret", validCronSecret) }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            val stuck = response.body<FinalizationHealthDTO>().stuckChallenges
            assertEquals(1, stuck.size)
            assertEquals(overdue.id, stuck.first().id)
        }

    // ---------- Authorization ----------

    @Test
    fun `admin challenge routes reject a non-admin token with 403`() = testApplication {
        application { testChallengeAdminModule() }
        val client = createClient { install(ContentNegotiation) { json(json) } }
        val seeded = CommentTestSeed.seedUser(username = "plainuser")
        val (session) = SessionService(AuthSessionDAO(), RefreshTokenGenerator()).createSession(
            credentialId = seeded.authId,
            scope = SessionScope.FULL,
            userId = seeded.userId,
            deviceId = null,
            deviceName = null,
            userAgent = null,
            ip = null,
        )
        val userToken = jwt.generateAccessToken(session, seeded.authId, seeded.email, seeded.userId)

        val response = client.get("/api/admin/challenges") { header(HttpHeaders.Authorization, "Bearer $userToken") }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `POST admin challenges rejects a non-admin token with 403`() = testApplication {
        application { testChallengeAdminModule() }
        val client = createClient { install(ContentNegotiation) { json(json) } }
        val seeded = CommentTestSeed.seedUser(username = "plainuser3")
        val (session) = SessionService(AuthSessionDAO(), RefreshTokenGenerator()).createSession(
            credentialId = seeded.authId,
            scope = SessionScope.FULL,
            userId = seeded.userId,
            deviceId = null,
            deviceName = null,
            userAgent = null,
            ip = null,
        )
        val userToken = jwt.generateAccessToken(session, seeded.authId, seeded.email, seeded.userId)

        val response = client.post("/api/admin/challenges") {
            header(HttpHeaders.Authorization, "Bearer $userToken")
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    CreateChallengeAdminRequest.serializer(),
                    challengeRequest(familyId = java.util.UUID.randomUUID()),
                ),
            )
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `POST publish rejects a non-admin token with 403`() = testApplication {
        application { testChallengeAdminModule() }
        val client = createClient { install(ContentNegotiation) { json(json) } }
        val seeded = CommentTestSeed.seedUser(username = "plainuser4")
        val (session) = SessionService(AuthSessionDAO(), RefreshTokenGenerator()).createSession(
            credentialId = seeded.authId,
            scope = SessionScope.FULL,
            userId = seeded.userId,
            deviceId = null,
            deviceName = null,
            userAgent = null,
            ip = null,
        )
        val userToken = jwt.generateAccessToken(session, seeded.authId, seeded.email, seeded.userId)

        val response = client.post("/api/admin/challenges/${java.util.UUID.randomUUID()}/publish") {
            header(HttpHeaders.Authorization, "Bearer $userToken")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `POST finalize rejects a non-admin token with 403`() = testApplication {
        application { testChallengeAdminModule() }
        val client = createClient { install(ContentNegotiation) { json(json) } }
        val seeded = CommentTestSeed.seedUser(username = "plainuser2")
        val (session) = SessionService(AuthSessionDAO(), RefreshTokenGenerator()).createSession(
            credentialId = seeded.authId,
            scope = SessionScope.FULL,
            userId = seeded.userId,
            deviceId = null,
            deviceName = null,
            userAgent = null,
            ip = null,
        )
        val userToken = jwt.generateAccessToken(session, seeded.authId, seeded.email, seeded.userId)

        val response = client.post("/api/admin/challenges/${java.util.UUID.randomUUID()}/finalize") {
            header(HttpHeaders.Authorization, "Bearer $userToken")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `admin challenge routes reject a request with no token`() = testApplication {
        application { testChallengeAdminModule() }
        val client = createClient { install(ContentNegotiation) { json(json) } }

        val response = client.get("/api/admin/challenges")

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }
}
