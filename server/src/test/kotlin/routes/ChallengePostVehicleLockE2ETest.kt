package routes

import com.revio.server.features.auth.JwtService
import com.revio.server.features.auth.RefreshTokenGenerator
import com.revio.server.features.auth.session.AuthSessionDAO
import com.revio.server.features.auth.session.SessionScope
import com.revio.server.features.auth.session.SessionService
import com.revio.server.features.car_family.CarFamilyTable
import com.revio.server.features.car_model.CarModelTable
import com.revio.server.features.challenge.ChallengeDAO
import com.revio.server.features.challenge.ChallengeProgressDAO
import com.revio.server.features.challenge.ChallengeStatus
import com.revio.server.features.post.PostSource
import com.revio.server.features.post.PostTable
import com.revio.server.features.post.dto.UpdatePostRequest
import com.revio.server.features.user.UserTable
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
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
import testutils.testPostModule
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * End-to-end pin for the fix in PostService.updatePostAsAuthor (plan §5/§9-Pas3): a user cannot
 * edit a contributing post's car model to a different family after the fact, so the reward
 * granted at finalization always reflects the vehicle that actually earned it — not a swapped-in
 * one. Mirrors ChallengeFraudRemovalE2ETest's structure: fixture setup and finalization go
 * through the real DAOs directly (as that test's own fixture does), while the behavior under
 * test — the PATCH attempt — goes through the real HTTP route.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChallengePostVehicleLockE2ETest {

    private val jwt = JwtService(
        jwtSecret = TestEnv.JWT_SECRET,
        jwtIssuer = TestEnv.JWT_ISSUER,
        jwtAudience = TestEnv.JWT_AUDIENCE,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val challengeDao = ChallengeDAO()
    private val challengeProgressDao = ChallengeProgressDAO()

    private val rewardPoints = 200
    private val normalPoints = 10

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

    private fun postTest(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        application { testPostModule() }
        val client = createClient { install(ContentNegotiation) { json(json) } }
        block(client)
    }

    private suspend fun tokenFor(authId: UUID, userId: UUID, email: String): String {
        val (session) = SessionService(AuthSessionDAO(), RefreshTokenGenerator()).createSession(
            credentialId = authId,
            scope = SessionScope.FULL,
            userId = userId,
            deviceId = null,
            deviceName = null,
            userAgent = null,
            ip = null,
        )
        return jwt.generateAccessToken(session, authId, email, userId)
    }

    private fun seedFamilyAndModel(brand: String, familyName: String, model: String): Pair<UUID, UUID> = transaction {
        val familyId = CarFamilyTable.insert {
            it[CarFamilyTable.brand] = brand
            it[CarFamilyTable.name] = familyName
        }[CarFamilyTable.id].value
        val modelId = CarModelTable.insert {
            it[CarModelTable.brand] = brand
            it[CarModelTable.model] = model
            it[CarModelTable.familyId] = familyId
        }[CarModelTable.id].value
        familyId to modelId
    }

    private fun seedCameraPost(ownerUserId: UUID, carModelId: UUID): UUID = transaction {
        PostTable.insert {
            it[PostTable.userId] = ownerUserId
            it[PostTable.imageKey] = "posts/vehicle-lock-e2e.jpg"
            it[PostTable.carModelId] = carModelId
            it[PostTable.postSource] = PostSource.CAMERA.name
            it[PostTable.points] = normalPoints
        }[PostTable.id].value
    }

    private fun spotScore(userId: UUID): Int = transaction {
        UserTable.select(UserTable.spotScore).where { UserTable.id eq userId }.single()[UserTable.spotScore]
    }

    private fun setSpotScore(userId: UUID, score: Int) = transaction {
        UserTable.update({ UserTable.id eq userId }) { it[UserTable.spotScore] = score }
    }

    @Test
    fun `editing a contributing post's model to another family is rejected, and finalization still grants only the legitimate contribution`() =
        postTest { client ->
            val (targetFamilyId, lamborghiniHuracan) = seedFamilyAndModel("lamborghini", "Huracan", "huracan")
            val (_, opelCorsa) = seedFamilyAndModel("opel", "Corsa", "corsa")
            val user = CommentTestSeed.seedUser(username = "spotter")
            val token = tokenFor(user.authId, user.userId, user.email)

            val now = Instant.now()
            val challengeId = challengeDao.insert(
                title = "Spot a Huracan",
                description = null,
                targetFamilyId = targetFamilyId,
                requiredPosts = 1,
                rewardPoints = rewardPoints,
                startsAt = now.minus(1, ChronoUnit.HOURS),
                endsAt = now.plus(1, ChronoUnit.HOURS),
                adminTimezone = "Europe/Bucharest",
                createdBy = null,
            )
            challengeDao.updateStatus(challengeId, ChallengeStatus.SCHEDULED, publishedAt = now)

            // Post eligibil: real Huracan, contribution recorded exactly like PostService.createPost would.
            val postId = seedCameraPost(user.userId, lamborghiniHuracan)
            setSpotScore(user.userId, normalPoints)
            val evaluation = challengeProgressDao.evaluatePostContribution(challengeId, user.userId, postId, lamborghiniHuracan, now)
            assertEquals(true, evaluation.eligible, "Fixture guard: the seeded post must be a real contribution")

            // Attempt to swap the car for an Opel after the fact — must be rejected.
            val patchResponse = client.patch("/api/posts/$postId") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(UpdatePostRequest(carModelId = opelCorsa))
            }

            assertEquals(HttpStatusCode.Conflict, patchResponse.status)
            val patchBody = patchResponse.body<Map<String, String>>()
            assertEquals("CHALLENGE_POST_VEHICLE_LOCKED", patchBody["code"])

            val persistedCarModelId = transaction {
                PostTable.select(PostTable.carModelId).where { PostTable.id eq postId }.single()[PostTable.carModelId]
            }
            assertEquals(lamborghiniHuracan, persistedCarModelId, "The rejected PATCH must not have changed the post's car model")

            // Finalization must grant the reward for the real Huracan contribution — the blocked
            // edit attempt changed nothing, so this is exactly the legitimate outcome.
            val finalization = challengeProgressDao.finalizeParticipants(challengeId)

            assertEquals(1, finalization.grantedCount)
            assertEquals(normalPoints + rewardPoints, spotScore(user.userId))
        }
}
