package service

import com.revio.server.features.notification.DevicePlatform
import com.revio.server.features.notification.FirebaseProject
import com.revio.server.features.notification.InactivityDAO
import com.revio.server.features.notification.InactivityJob
import com.revio.server.features.notification.NotificationEventService
import com.revio.server.features.notification.NotificationOutboxDAO
import com.revio.server.features.notification.NotificationPolicyService
import com.revio.server.features.notification.NotificationTable
import com.revio.server.features.notification.UserDeviceDAO
import com.revio.server.features.notification.UserNotificationPrefsDAO
import com.revio.server.features.leaderboard.LeaderboardDAO
import com.revio.server.features.leaderboard.LeaderboardDeltaDAO
import com.revio.server.features.leaderboard.LeaderboardDeltaService
import com.revio.server.features.post.PostTable
import com.revio.server.features.user.UserDao
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
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
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Real Testcontainers Postgres, end-to-end coverage for InactivityJob (plan §18, step 6.4): the
 * 24h last_app_open gate, a new post resetting eligibility for a full 3+7 cycle, silence past
 * day 7, and same-day repeated runs never duplicating.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InactivityJobTest {

    private val inactivityDao = InactivityDAO()
    private val userDao = UserDao()
    private val prefsDao = UserNotificationPrefsDAO()
    private val eventService = NotificationEventService()
    private val policyService = NotificationPolicyService()
    private val deviceDao = UserDeviceDAO()
    private val outboxDao = NotificationOutboxDAO()
    private val leaderboardDao = LeaderboardDAO()
    private val leaderboardDeltaService = LeaderboardDeltaService(leaderboardDao, LeaderboardDeltaDAO())

    private val job = InactivityJob(
        inactivityDao, userDao, prefsDao, eventService, policyService, deviceDao, outboxDao,
        leaderboardDao, leaderboardDeltaService,
    )

    @BeforeAll
    fun setup() = TestDatabaseFactory.start()

    @AfterAll
    fun tearDown() = TestDatabaseFactory.stop()

    @BeforeEach
    fun clean() = TestDatabaseFactory.cleanDatabase()

    private suspend fun seedUtcDevice(userId: UUID, lastSeenAt: Instant) {
        deviceDao.registerDevice(
            userId = userId, deviceId = "device-1", fcmToken = "token-${UUID.randomUUID()}",
            firebaseProject = FirebaseProject.DEBUG, platform = DevicePlatform.ANDROID,
            appVersion = "1.0.0", timezone = "UTC", locale = null,
        )
        transaction {
            com.revio.server.features.notification.UserDeviceTable.update({
                com.revio.server.features.notification.UserDeviceTable.userId eq userId
            }) {
                it[com.revio.server.features.notification.UserDeviceTable.lastSeenAt] = lastSeenAt.atOffset(java.time.ZoneOffset.UTC)
            }
        }
    }

    private fun seedPostAt(userId: UUID, at: Instant): UUID {
        val post = CommentTestSeed.seedPost(userId)
        transaction { PostTable.update({ PostTable.id eq post.postId }) { it[PostTable.createdAt] = at } }
        return post.postId
    }

    private fun notificationRowsFor(userId: UUID) = transaction {
        NotificationTable.selectAll().where { NotificationTable.userId eq userId }.toList()
    }

    // ---------- last_app_open 24h gate ----------

    @Test
    fun `day-3 does not appear if the app was opened within the last 24h`() = runTest {
        val now = Instant.parse("2026-06-16T12:00:00Z")
        val user = CommentTestSeed.seedUser("recentopen")
        seedPostAt(user.userId, now.minus(Duration.ofDays(3)))
        seedUtcDevice(user.userId, lastSeenAt = now.minus(Duration.ofHours(1)))

        val result = job.run(now)

        assertEquals(0, result.sent)
        assertEquals(0, notificationRowsFor(user.userId).size)
    }

    @Test
    fun `day-3 fires when the app has not been opened in over 24h`() = runTest {
        val now = Instant.parse("2026-06-16T12:00:00Z")
        val user = CommentTestSeed.seedUser("staleopen")
        seedPostAt(user.userId, now.minus(Duration.ofDays(3)))
        seedUtcDevice(user.userId, lastSeenAt = now.minus(Duration.ofHours(25)))

        val result = job.run(now)

        assertEquals(1, result.sent)
        val row = notificationRowsFor(user.userId).single()
        assertEquals("The leaderboard keeps moving", row[NotificationTable.title])
    }

    // ---------- day 7 fires with different (leaderboard-conditioned, plan §9/step 6.5) copy ----------

    @Test
    fun `day-7 fires with rank-1 copy for the sole user on the leaderboard`() = runTest {
        val now = Instant.parse("2026-06-16T12:00:00Z")
        val user = CommentTestSeed.seedUser("dayseven")
        seedPostAt(user.userId, now.minus(Duration.ofDays(7)))
        seedUtcDevice(user.userId, lastSeenAt = now.minus(Duration.ofHours(25)))

        val result = job.run(now)

        assertEquals(1, result.sent)
        val row = notificationRowsFor(user.userId).single()
        // A single user on the leaderboard is necessarily rank 1 -> the rank-1 copy, not the day-3 one.
        assertEquals("You're still holding #1", row[NotificationTable.title])
    }

    // ---------- new post resets eligibility, full 3+7 cycle ----------

    @Test
    fun `a new post resets eligibility, allowing a full day-3 then day-7 cycle again`() = runTest {
        val start = Instant.parse("2026-06-01T12:00:00Z")
        val user = CommentTestSeed.seedUser("fullcycle")
        seedPostAt(user.userId, start)
        seedUtcDevice(user.userId, lastSeenAt = start.minus(Duration.ofHours(25)))

        // First cycle: day 3 and day 7 both fire.
        job.run(start.plus(Duration.ofDays(3)))
        job.run(start.plus(Duration.ofDays(7)))
        assertEquals(2, notificationRowsFor(user.userId).size)

        // User posts again — this moves MAX(posts.created_at) forward, resetting day 0.
        val newPostAt = start.plus(Duration.ofDays(10))
        seedPostAt(user.userId, newPostAt)
        transaction {
            com.revio.server.features.notification.UserDeviceTable.update({
                com.revio.server.features.notification.UserDeviceTable.userId eq user.userId
            }) {
                it[com.revio.server.features.notification.UserDeviceTable.lastSeenAt] =
                    newPostAt.minus(Duration.ofHours(25)).atOffset(java.time.ZoneOffset.UTC)
            }
        }

        // Immediately after the new post, day-3/day-7 must not fire again yet.
        job.run(newPostAt.plus(Duration.ofDays(1)))
        assertEquals(2, notificationRowsFor(user.userId).size, "day 1 of the new episode must not fire")

        // A full new cycle from the new post.
        job.run(newPostAt.plus(Duration.ofDays(3)))
        job.run(newPostAt.plus(Duration.ofDays(7)))
        assertEquals(4, notificationRowsFor(user.userId).size, "the new episode gets its own day-3 and day-7")
    }

    // ---------- silence past day 7 (D8) ----------

    @Test
    fun `nothing is sent past day 7 until a new post`() = runTest {
        val start = Instant.parse("2026-06-01T12:00:00Z")
        val user = CommentTestSeed.seedUser("pastseven")
        seedPostAt(user.userId, start)
        seedUtcDevice(user.userId, lastSeenAt = start.minus(Duration.ofHours(25)))

        job.run(start.plus(Duration.ofDays(7)))
        assertEquals(1, notificationRowsFor(user.userId).size)

        // Days 8, 15, 30 — nothing more, ever, without a new post.
        job.run(start.plus(Duration.ofDays(8)))
        job.run(start.plus(Duration.ofDays(15)))
        job.run(start.plus(Duration.ofDays(30)))

        assertEquals(1, notificationRowsFor(user.userId).size, "silence past day 7 until a new post (D8)")
    }

    // ---------- repeated same-day runs never duplicate ----------

    @Test
    fun `repeated runs on the same day do not produce duplicates`() = runTest {
        val now = Instant.parse("2026-06-16T12:00:00Z")
        val user = CommentTestSeed.seedUser("samedayrepeat")
        seedPostAt(user.userId, now.minus(Duration.ofDays(3)))
        seedUtcDevice(user.userId, lastSeenAt = now.minus(Duration.ofHours(25)))

        job.run(now)
        job.run(now.plusSeconds(3600))
        job.run(now.plusSeconds(7200))

        assertEquals(1, notificationRowsFor(user.userId).size)
    }
}
