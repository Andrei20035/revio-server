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
import com.revio.server.features.notification.NotificationTable
import com.revio.server.features.post.PostSource
import com.revio.server.features.post.PostTable
import com.revio.server.features.user.UserTable
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
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The plan's own challenge-fraud scenario (plan §5): a user completes a 5-post challenge with 5
 * posts that turn out to be fake. An admin removes all 5 in one bulk-remove call. Deleting each
 * post already reconciles its own challenge contribution and reverses its normal points (see
 * PostRemovalDaoAtomicityTest); dropping below the challenge's required_posts additionally
 * revokes the completion reward. No new mechanics are needed for the fraud case itself — this
 * test only pins the aggregate outcome end to end, through the real HTTP admin route: both
 * reversals apply, and the owner receives exactly one notification, not five.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChallengeFraudRemovalE2ETest {

    private val jwt = JwtService(
        jwtSecret = TestEnv.JWT_SECRET,
        jwtIssuer = TestEnv.JWT_ISSUER,
        jwtAudience = TestEnv.JWT_AUDIENCE,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val challengeDao = ChallengeDAO()
    private val challengeProgressDao = ChallengeProgressDAO()

    private val requiredPosts = 5
    private val rewardPoints = 300
    private val normalPointsPerPost = 10

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

    private fun postExists(postId: UUID): Boolean = transaction {
        PostTable.select(PostTable.id).where { PostTable.id eq postId }.any()
    }

    private fun spotScore(userId: UUID): Int = transaction {
        UserTable.select(UserTable.spotScore).where { UserTable.id eq userId }.single()[UserTable.spotScore]
    }

    private fun setSpotScore(userId: UUID, score: Int) = transaction {
        UserTable.update({ UserTable.id eq userId }) { it[UserTable.spotScore] = score }
    }

    private fun notificationCount(userId: UUID): Long = transaction {
        NotificationTable.select(NotificationTable.id).where { NotificationTable.userId eq userId }.count()
    }

    private fun seedCameraPost(ownerUserId: UUID, carModelId: UUID): UUID = transaction {
        PostTable.insert {
            it[PostTable.userId] = ownerUserId
            it[PostTable.imageKey] = "posts/fraud-test.jpg"
            it[PostTable.carModelId] = carModelId
            it[PostTable.postSource] = PostSource.CAMERA.name
            it[PostTable.points] = normalPointsPerPost
        }[PostTable.id].value
    }

    /**
     * A user who "completed" a 5-post challenge: all 5 posts contributed, the 300-point reward
     * is GRANTED, and spot_score is 350 (300 reward + 10 normal points x 5 posts) — built through
     * the production ChallengeDAO/ChallengeProgressDAO, exactly like PostRemovalDaoAtomicityTest's
     * own fixture, just with 5 posts instead of 2.
     */
    private suspend fun fraudulentChallengeFixture(): Pair<UUID, List<UUID>> {
        val user = CommentTestSeed.seedUser(username = "fraudster")
        val familyId = transaction {
            CarFamilyTable.insert {
                it[CarFamilyTable.brand] = "volkswagen"
                it[CarFamilyTable.name] = "Golf"
            }[CarFamilyTable.id].value
        }
        val carModelId = transaction {
            CarModelTable.insert {
                it[CarModelTable.brand] = "volkswagen"
                it[CarModelTable.model] = "golf r"
                it[CarModelTable.familyId] = familyId
            }[CarModelTable.id].value
        }

        val now = Instant.now()
        val challengeId = challengeDao.insert(
            title = "Spot 5 Golfs",
            description = null,
            targetFamilyId = familyId,
            requiredPosts = requiredPosts,
            rewardPoints = rewardPoints,
            startsAt = now.minus(1, ChronoUnit.HOURS),
            endsAt = now.plus(1, ChronoUnit.HOURS),
            adminTimezone = "Europe/Bucharest",
            createdBy = null,
        )
        challengeDao.updateStatus(challengeId, ChallengeStatus.SCHEDULED, publishedAt = now)

        val postIds = (1..requiredPosts).map { seedCameraPost(user.userId, carModelId) }
        setSpotScore(user.userId, normalPointsPerPost * requiredPosts)
        postIds.forEach { postId ->
            challengeProgressDao.evaluatePostContribution(challengeId, user.userId, postId, carModelId, now)
        }
        challengeProgressDao.finalizeParticipants(challengeId)

        assertEquals(normalPointsPerPost * requiredPosts + rewardPoints, spotScore(user.userId), "Fixture guard")

        return user.userId to postIds
    }

    @Test
    fun `bulk-removing all 5 fraudulent posts reverses the reward and normal points and sends one notification`() =
        moderationTest { client ->
            val (fraudsterId, postIds) = fraudulentChallengeFixture()
            val admin = CommentTestSeed.seedUser(username = "moderator")
            val token = tokenFor(admin.authId, admin.userId, admin.email, isAdmin = true)

            val postIdsJson = postIds.joinToString(",") { "\"$it\"" }
            val resp = client.post("/api/admin/posts/bulk-remove") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody("""{"postIds":[$postIdsJson],"reason":"FAKE_OR_STOLEN_CONTENT"}""")
            }

            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(requiredPosts, body["removedCount"]?.jsonPrimitive?.int)
            assertEquals(1, body["notifiedUserCount"]?.jsonPrimitive?.int)

            postIds.forEach { assertFalse(postExists(it), "Every fraudulent post must be gone") }
            assertEquals(0, spotScore(fraudsterId), "350 - 300 (reward revoked) - 50 (5x10 normal points) = 0")
            assertEquals(1L, notificationCount(fraudsterId), "One aggregated notification, not five")
        }
}
