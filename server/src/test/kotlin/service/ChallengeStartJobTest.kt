package service

import com.revio.server.features.challenge.ChallengeDAO
import com.revio.server.features.challenge.ChallengeStatus
import com.revio.server.features.notification.ChallengeStartDAO
import com.revio.server.features.notification.ChallengeStartJob
import com.revio.server.features.notification.DevicePlatform
import com.revio.server.features.notification.FirebaseProject
import com.revio.server.features.notification.IChallengeStartDAO
import com.revio.server.features.notification.NotificationCategory
import com.revio.server.features.notification.NotificationEventService
import com.revio.server.features.notification.NotificationOutboxDAO
import com.revio.server.features.notification.NotificationOutboxTable
import com.revio.server.features.notification.NotificationPolicyService
import com.revio.server.features.notification.NotificationTable
import com.revio.server.features.notification.UserDeviceDAO
import com.revio.server.features.notification.UserNotificationPrefsDAO
import com.revio.server.features.user.BanState
import com.revio.server.features.user.IUserDAO
import com.revio.server.features.user.UserDao
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import testutils.ChallengeTestSeed
import testutils.CommentTestSeed
import testutils.TestDatabaseFactory
import java.time.Instant
import java.util.UUID

/**
 * Real Testcontainers Postgres, end-to-end coverage for ChallengeStartJob (push-notifications
 * plan, "challenge is live" work): which challenges are due, the fan-out itself, idempotency
 * across re-runs, and the job's resilience (a per-user failure doesn't stop the page; a
 * page-level failure leaves `notified_started_at` unset for the next tick to retry) — exercised
 * with real DAOs rather than mocked out, same style as DiscoveryJobTest.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChallengeStartJobTest {

    private val now = Instant.parse("2026-06-16T18:30:00Z") // well outside quiet hours (00:00-08:00)

    private val challengeDao = ChallengeDAO()
    private val challengeStartDao = ChallengeStartDAO()
    private val userDao = UserDao()
    private val prefsDao = UserNotificationPrefsDAO()
    private val eventService = NotificationEventService()
    private val policyService = NotificationPolicyService()
    private val deviceDao = UserDeviceDAO()
    private val outboxDao = NotificationOutboxDAO()

    private val job = ChallengeStartJob(challengeDao, challengeStartDao, userDao, prefsDao, eventService, policyService, deviceDao, outboxDao)

    @BeforeAll
    fun setup() = TestDatabaseFactory.start()

    @AfterAll
    fun tearDown() = TestDatabaseFactory.stop()

    @BeforeEach
    fun clean() = TestDatabaseFactory.cleanDatabase()

    private fun seedChallenge(
        status: ChallengeStatus,
        startsAt: Instant,
        endsAt: Instant,
    ): UUID {
        val familyId = ChallengeTestSeed.seedFamily()
        return ChallengeTestSeed.seedChallenge(familyId = familyId, startsAt = startsAt, endsAt = endsAt, status = status)
    }

    private suspend fun seedEligibleUser(username: String, timezone: String = "UTC"): UUID {
        val userId = CommentTestSeed.seedUser(username).userId
        deviceDao.registerDevice(
            userId = userId, deviceId = "device-1", fcmToken = "token-${UUID.randomUUID()}",
            firebaseProject = FirebaseProject.DEBUG, platform = DevicePlatform.ANDROID,
            appVersion = "1.0.0", timezone = timezone, locale = null,
        )
        return userId
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

    // ---------- which challenges are due ----------

    @Test
    fun `a DRAFT challenge is ignored even if its window would otherwise be active`() = runTest {
        val challengeId = seedChallenge(ChallengeStatus.DRAFT, startsAt = now.minusSeconds(3600), endsAt = now.plusSeconds(3600))
        val userId = seedEligibleUser("draftignored")

        val result = job.run(now)

        assertEquals(0, result.challengesProcessed)
        assertEquals(0, notificationRowsFor(userId).size)
        assertNull(challengeDao.findById(challengeId)!!.notifiedStartedAt)
    }

    @Test
    fun `a CANCELLED challenge is ignored even if its window would otherwise be active`() = runTest {
        val challengeId = seedChallenge(ChallengeStatus.CANCELLED, startsAt = now.minusSeconds(3600), endsAt = now.plusSeconds(3600))
        val userId = seedEligibleUser("cancelledignored")

        val result = job.run(now)

        assertEquals(0, result.challengesProcessed)
        assertEquals(0, notificationRowsFor(userId).size)
        assertNull(challengeDao.findById(challengeId)!!.notifiedStartedAt)
    }

    @Test
    fun `a SCHEDULED challenge that hasn't started yet is ignored`() = runTest {
        val challengeId = seedChallenge(ChallengeStatus.SCHEDULED, startsAt = now.plusSeconds(3600), endsAt = now.plusSeconds(7200))
        val userId = seedEligibleUser("futureignored")

        val result = job.run(now)

        assertEquals(0, result.challengesProcessed)
        assertEquals(0, notificationRowsFor(userId).size)
        assertNull(challengeDao.findById(challengeId)!!.notifiedStartedAt)
    }

    @Test
    fun `a SCHEDULED challenge that has already ended is ignored`() = runTest {
        val challengeId = seedChallenge(ChallengeStatus.SCHEDULED, startsAt = now.minusSeconds(7200), endsAt = now.minusSeconds(3600))
        val userId = seedEligibleUser("endedignored")

        val result = job.run(now)

        assertEquals(0, result.challengesProcessed)
        assertEquals(0, notificationRowsFor(userId).size)
        assertNull(challengeDao.findById(challengeId)!!.notifiedStartedAt)
    }

    // ---------- fan-out + idempotency ----------

    @Test
    fun `an active SCHEDULED challenge notifies an eligible user`() = runTest {
        val challengeId = seedChallenge(ChallengeStatus.SCHEDULED, startsAt = now.minusSeconds(3600), endsAt = now.plusSeconds(3600))
        val userId = seedEligibleUser("activenotified")

        val result = job.run(now)

        assertEquals(1, result.challengesProcessed)
        assertEquals(1, result.notified)
        val rows = notificationRowsFor(userId)
        assertEquals(1, rows.size)
        val notification = rows.single()
        assertEquals(challengeId, notification[NotificationTable.challengeId]?.value)
        assertEquals(1, outboxRowsFor(notification[NotificationTable.id].value).size)
        assertNotNull(challengeDao.findById(challengeId)!!.notifiedStartedAt)
    }

    @Test
    fun `a second run produces no new notifications or outbox rows`() = runTest {
        val challengeId = seedChallenge(ChallengeStatus.SCHEDULED, startsAt = now.minusSeconds(3600), endsAt = now.plusSeconds(3600))
        val userId = seedEligibleUser("secondrun")

        job.run(now)
        val secondResult = job.run(now)

        assertEquals(0, secondResult.challengesProcessed)
        val rows = notificationRowsFor(userId)
        assertEquals(1, rows.size)
        assertEquals(1, outboxRowsFor(rows.single()[NotificationTable.id].value).size)
        assertEquals(challengeId, rows.single()[NotificationTable.challengeId]?.value)
    }

    // ---------- resilience ----------

    private class ThrowingChallengeStartDAO : IChallengeStartDAO {
        override suspend fun findEligibleUserIdsPage(cursor: UUID?, limit: Int): List<UUID> =
            throw RuntimeException("challenge-start DAO boom")
    }

    @Test
    fun `notified_started_at is left unset when the fan-out itself fails before completing`() = runTest {
        val challengeId = seedChallenge(ChallengeStatus.SCHEDULED, startsAt = now.minusSeconds(3600), endsAt = now.plusSeconds(3600))
        val brokenJob = ChallengeStartJob(challengeDao, ThrowingChallengeStartDAO(), userDao, prefsDao, eventService, policyService, deviceDao, outboxDao)

        assertThrows(RuntimeException::class.java) {
            kotlinx.coroutines.runBlocking { brokenJob.run(now) }
        }

        assertNull(challengeDao.findById(challengeId)!!.notifiedStartedAt)
        assertEquals(0, transaction { NotificationTable.selectAll().where { NotificationTable.category eq NotificationCategory.CHALLENGES }.count() })
    }

    /** Delegates every [IUserDAO] call to [delegate] except [findBanState] for [poisonUserId], which throws. */
    private class PoisonUserDAO(private val delegate: IUserDAO, private val poisonUserId: UUID) : IUserDAO by delegate {
        override suspend fun findBanState(userId: UUID): BanState? {
            if (userId == poisonUserId) throw RuntimeException("poisoned user boom")
            return delegate.findBanState(userId)
        }
    }

    @Test
    fun `an exception processing one user doesn't stop the rest of the page, and the challenge still ends up marked notified`() = runTest {
        val challengeId = seedChallenge(ChallengeStatus.SCHEDULED, startsAt = now.minusSeconds(3600), endsAt = now.plusSeconds(3600))
        val poisonUserId = seedEligibleUser("poisoned")
        val goodUserId = seedEligibleUser("good")
        val resilientJob = ChallengeStartJob(
            challengeDao, challengeStartDao, PoisonUserDAO(userDao, poisonUserId), prefsDao, eventService, policyService, deviceDao, outboxDao,
        )

        val result = resilientJob.run(now)

        assertEquals(1, result.notified)
        assertEquals(0, notificationRowsFor(poisonUserId).size)
        assertEquals(1, notificationRowsFor(goodUserId).size)
        assertNotNull(challengeDao.findById(challengeId)!!.notifiedStartedAt)
    }

    // ---------- pagination ----------

    @Test
    fun `pagination walks more than one page of eligible users`() = runTest {
        val challengeId = seedChallenge(ChallengeStatus.SCHEDULED, startsAt = now.minusSeconds(3600), endsAt = now.plusSeconds(3600))
        val userIds = listOf("paged1", "paged2", "paged3").map { seedEligibleUser(it) }
        val pagedJob = ChallengeStartJob(challengeDao, challengeStartDao, userDao, prefsDao, eventService, policyService, deviceDao, outboxDao, pageSize = 2)

        val result = pagedJob.run(now)

        assertEquals(3, result.notified)
        userIds.forEach { userId -> assertEquals(1, notificationRowsFor(userId).size) }
        assertNotNull(challengeDao.findById(challengeId)!!.notifiedStartedAt)
    }

    // ---------- quiet-hours defer vs. the challenge-relative skip threshold (plan §9) ----------

    @Test
    fun `a quiet-hours defer well within the 25pct-of-window budget still sends, deferred`() = runTest {
        // 2026-08-09T04:00:00Z is 07:00 local in Europe/Bucharest (EEST, UTC+3) — inside quiet
        // hours (00:00-08:00), with quiet_end only ~1h away. For a 48h challenge window, the
        // 25% budget is 12h, so this defer is comfortably within it.
        val localNow = Instant.parse("2026-08-09T04:00:00Z")
        val challengeId = seedChallenge(ChallengeStatus.SCHEDULED, startsAt = localNow.minusSeconds(3600), endsAt = localNow.plus(java.time.Duration.ofHours(47)))
        val userId = seedEligibleUser("quietbudgetok", timezone = "Europe/Bucharest")

        val result = job.run(localNow)

        assertEquals(1, result.notified)
        val rows = notificationRowsFor(userId)
        assertEquals(1, rows.size)
        val outboxRow = outboxRowsFor(rows.single()[NotificationTable.id].value).single()
        assertNotNull(outboxRow[NotificationOutboxTable.notBefore], "a deferred challenge-start send must carry a notBefore")
        assertNotNull(challengeDao.findById(challengeId)!!.notifiedStartedAt)
    }

    @Test
    fun `a quiet-hours defer beyond the 25pct-of-window budget is skipped entirely, not deferred`() = runTest {
        // 2026-08-08T22:00:00Z is 01:00 local in Europe/Bucharest — quiet_end (08:00 local) is
        // 7h away. For a 2h challenge window, the 25% budget is only 30 minutes, so this defer
        // is well past it.
        val localNow = Instant.parse("2026-08-08T22:00:00Z")
        val challengeId = seedChallenge(ChallengeStatus.SCHEDULED, startsAt = localNow, endsAt = localNow.plus(java.time.Duration.ofHours(2)))
        val userId = seedEligibleUser("quietbudgetexceeded", timezone = "Europe/Bucharest")

        val result = job.run(localNow)

        assertEquals(0, result.notified)
        assertEquals(0, notificationRowsFor(userId).size)
        assertNotNull(challengeDao.findById(challengeId)!!.notifiedStartedAt, "still marked notified — nothing left to retry")
    }
}
