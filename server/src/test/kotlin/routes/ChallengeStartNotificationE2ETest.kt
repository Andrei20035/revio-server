package routes

import com.revio.server.features.auth.JwtService
import com.revio.server.features.auth.RefreshTokenGenerator
import com.revio.server.features.auth.session.AuthSessionDAO
import com.revio.server.features.auth.session.SessionScope
import com.revio.server.features.auth.session.SessionService
import com.revio.server.features.car_family.CarFamilyAdminDTO
import com.revio.server.features.car_family.CreateCarFamilyRequest
import com.revio.server.features.challenge.ChallengeAdminDTO
import com.revio.server.features.challenge.ChallengeDAO
import com.revio.server.features.challenge.CreateChallengeAdminRequest
import com.revio.server.features.notification.ChallengeStartDAO
import com.revio.server.features.notification.ChallengeStartJob
import com.revio.server.features.notification.DevicePlatform
import com.revio.server.features.notification.FirebaseProject
import com.revio.server.features.notification.NotificationCategory
import com.revio.server.features.notification.NotificationEventService
import com.revio.server.features.notification.NotificationOutboxDAO
import com.revio.server.features.notification.NotificationOutboxTable
import com.revio.server.features.notification.NotificationPolicyService
import com.revio.server.features.notification.NotificationTable
import com.revio.server.features.notification.UserDeviceDAO
import com.revio.server.features.notification.UserNotificationPrefsDAO
import com.revio.server.features.user.UserDao
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
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
import testutils.testChallengeAdminModule
import java.time.Instant
import java.util.UUID

/**
 * End-to-end for the "challenge is live" push (push-notifications plan): an admin creates and
 * publishes a challenge through the real HTTP admin API — same [testChallengeAdminModule] stack
 * [AdminChallengeWorkflowE2ETest] already exercises — then, once time has advanced past
 * `startsAt`, [ChallengeStartJob] (constructed directly with real DAOs, same style as
 * ChallengeStartJobTest) runs and fans the notification out. Confirms the whole chain lands
 * exactly one `user_notifications` row with the right category/dedupe key/challenge_id, and one
 * `notification_outbox` row per active device — none for a user whose only device is inactive.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChallengeStartNotificationE2ETest {

    private val jwt = JwtService(
        jwtSecret = TestEnv.JWT_SECRET,
        jwtIssuer = TestEnv.JWT_ISSUER,
        jwtAudience = TestEnv.JWT_AUDIENCE,
    )
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val challengeDao = ChallengeDAO()
    private val challengeStartDao = ChallengeStartDAO()
    private val userDao = UserDao()
    private val prefsDao = UserNotificationPrefsDAO()
    private val eventService = NotificationEventService()
    private val policyService = NotificationPolicyService()
    private val deviceDao = UserDeviceDAO()
    private val outboxDao = NotificationOutboxDAO()

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

    private suspend fun adminToken(): String {
        val seeded = CommentTestSeed.seedUser(username = "admin")
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

    private fun notificationRowsFor(userId: UUID) = transaction {
        NotificationTable
            .selectAll()
            .where { (NotificationTable.userId eq userId) and (NotificationTable.category eq NotificationCategory.CHALLENGES) }
            .toList()
    }

    private fun outboxRowsFor(notificationId: UUID) = transaction {
        NotificationOutboxTable.selectAll().where { NotificationOutboxTable.notificationId eq notificationId }.toList()
    }

    @Test
    fun `create, publish, advance past startsAt, run the job - active device notified, inactive device isn't`() = testApplication {
        application { testChallengeAdminModule() }
        val client = createClient { install(ContentNegotiation) { json(json) } }
        val token = adminToken()

        // 1. Create the challenge's target family via the real admin HTTP API.
        val familyResponse = client.post("/api/admin/car-families") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(CreateCarFamilyRequest.serializer(), CreateCarFamilyRequest("volkswagen", "Golf")))
        }
        assertEquals(HttpStatusCode.Created, familyResponse.status)
        val family = familyResponse.body<CarFamilyAdminDTO>()

        // 2. Create the challenge (DRAFT).
        val createRequest = CreateChallengeAdminRequest(
            title = "Weekend Golf Hunt",
            description = "Spot 5 Golfs",
            targetFamilyId = family.id,
            requiredPosts = 5,
            rewardPoints = 300,
            startsAtLocal = "2026-08-08T09:00:00",
            endsAtLocal = "2026-08-10T09:00:00",
            timezone = "Europe/Bucharest",
        )
        val createResponse = client.post("/api/admin/challenges") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(CreateChallengeAdminRequest.serializer(), createRequest))
        }
        assertEquals(HttpStatusCode.Created, createResponse.status)
        val challenge = createResponse.body<ChallengeAdminDTO>()
        assertEquals("DRAFT", challenge.status)

        // 3. Publish (DRAFT -> SCHEDULED).
        val publishResponse = client.post("/api/admin/challenges/${challenge.id}/publish") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, publishResponse.status)

        // 4. Seed one user with an active, token-bearing device, and one whose only device is
        // inactive — the fan-out's two contrasting cases.
        val activeUser = CommentTestSeed.seedUser(username = "activeuser")
        deviceDao.registerDevice(
            userId = activeUser.userId, deviceId = "device-active", fcmToken = "token-${UUID.randomUUID()}",
            firebaseProject = FirebaseProject.DEBUG, platform = DevicePlatform.ANDROID,
            appVersion = "1.0.0", timezone = "UTC", locale = null,
        )

        val inactiveUser = CommentTestSeed.seedUser(username = "inactiveuser")
        deviceDao.registerDevice(
            userId = inactiveUser.userId, deviceId = "device-inactive", fcmToken = "token-${UUID.randomUUID()}",
            firebaseProject = FirebaseProject.DEBUG, platform = DevicePlatform.ANDROID,
            appVersion = "1.0.0", timezone = "UTC", locale = null,
        )
        deviceDao.deactivate(inactiveUser.userId, "device-inactive")

        // 5. Advance time past startsAt (well inside the [2026-08-08T06:00Z, 2026-08-10T06:00Z)
        // window once Europe/Bucharest's +3h summer offset is applied) and run the real job.
        val now = Instant.parse("2026-08-09T12:00:00Z")
        val job = ChallengeStartJob(challengeDao, challengeStartDao, userDao, prefsDao, eventService, policyService, deviceDao, outboxDao)
        val result = runBlocking { job.run(now) }

        assertEquals(1, result.challengesProcessed)
        assertEquals(1, result.notified)

        // 6. The active-device user got exactly one CHALLENGES notification, correctly targeted...
        val activeRows = notificationRowsFor(activeUser.userId)
        assertEquals(1, activeRows.size)
        val notification = activeRows.single()
        assertEquals("challenge_started:${challenge.id}", notification[NotificationTable.dedupeKey])
        assertEquals(challenge.id, notification[NotificationTable.challengeId]?.value)

        // ...and exactly one outbox row, for their (only, active) device.
        val activeOutboxRows = outboxRowsFor(notification[NotificationTable.id].value)
        assertEquals(1, activeOutboxRows.size)

        // 7. The inactive-device user got nothing at all — never even a candidate, since the
        // fan-out's own enumeration only considers active, token-bearing devices.
        assertTrue(notificationRowsFor(inactiveUser.userId).isEmpty())

        // 8. A second run (the next cron tick) is a no-op: notified_started_at is already set.
        val secondResult = runBlocking { job.run(now) }
        assertEquals(0, secondResult.challengesProcessed)
        assertEquals(1, notificationRowsFor(activeUser.userId).size)
    }
}
