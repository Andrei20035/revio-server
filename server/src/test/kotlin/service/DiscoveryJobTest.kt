package service

import com.revio.server.features.notification.DevicePlatform
import com.revio.server.features.notification.DiscoveryDAO
import com.revio.server.features.notification.DiscoveryJob
import com.revio.server.features.notification.FirebaseProject
import com.revio.server.features.notification.NotificationEventService
import com.revio.server.features.notification.NotificationOutboxDAO
import com.revio.server.features.notification.NotificationOutboxTable
import com.revio.server.features.notification.NotificationPolicyService
import com.revio.server.features.notification.NotificationTable
import com.revio.server.features.notification.UserDeviceDAO
import com.revio.server.features.notification.UserNotificationPrefsDAO
import com.revio.server.features.post.PostTable
import com.revio.server.features.user.UserTable
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
 * Real Testcontainers Postgres, end-to-end coverage for DiscoveryJob (plan §18, step 6.3):
 * content threshold, the 12h feed-open gate, the weekly cap, and quiet hours' >6h defer-vs-skip
 * rule — the four acceptance bullets, exercised together with real DAOs/NotificationPolicyService
 * rather than mocked out.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DiscoveryJobTest {

    private val discoveryDao = DiscoveryDAO()
    private val prefsDao = UserNotificationPrefsDAO()
    private val eventService = NotificationEventService()
    private val policyService = NotificationPolicyService()
    private val deviceDao = UserDeviceDAO()
    private val outboxDao = NotificationOutboxDAO()

    private val job = DiscoveryJob(discoveryDao, prefsDao, eventService, policyService, deviceDao, outboxDao)

    @BeforeAll
    fun setup() = TestDatabaseFactory.start()

    @AfterAll
    fun tearDown() = TestDatabaseFactory.stop()

    @BeforeEach
    fun clean() = TestDatabaseFactory.cleanDatabase()

    private fun backdateAccount(userId: UUID, at: Instant) = transaction {
        UserTable.update({ UserTable.id eq userId }) { it[UserTable.createdAt] = at }
    }

    private fun seedRoPosts(authorId: UUID, count: Int, createdAt: Instant) = repeat(count) {
        val post = CommentTestSeed.seedPost(authorId)
        transaction {
            PostTable.update({ PostTable.id eq post.postId }) {
                it[PostTable.country] = "RO"
                it[PostTable.createdAt] = createdAt
            }
        }
    }

    private suspend fun seedUtcDevice(userId: UUID) {
        deviceDao.registerDevice(
            userId = userId, deviceId = "device-1", fcmToken = "token-${UUID.randomUUID()}",
            firebaseProject = FirebaseProject.DEBUG, platform = DevicePlatform.ANDROID,
            appVersion = "1.0.0", timezone = "UTC", locale = null,
        )
    }

    private fun notificationRowsFor(userId: UUID) = transaction {
        NotificationTable.selectAll().where { NotificationTable.userId eq userId }.toList()
    }

    // ---------- content threshold ----------

    @Test
    fun `below the content threshold is skipped, no notification sent`() = runTest {
        val now = Instant.parse("2026-06-16T18:30:00Z") // Tuesday, well outside quiet hours
        val user = CommentTestSeed.seedUser("belowthreshold")
        backdateAccount(user.userId, now.minus(Duration.ofDays(10)))
        seedUtcDevice(user.userId)
        seedRoPosts(user.userId, count = 4, createdAt = now.minusSeconds(60)) // 4 < threshold of 5

        val result = job.run(now)

        assertEquals(0, result.sent)
        assertEquals(0, notificationRowsFor(user.userId).size)
    }

    @Test
    fun `at least 5 new spots meets the content threshold and sends`() = runTest {
        val now = Instant.parse("2026-06-16T18:30:00Z")
        val user = CommentTestSeed.seedUser("atthreshold")
        backdateAccount(user.userId, now.minus(Duration.ofDays(10)))
        seedUtcDevice(user.userId)
        seedRoPosts(user.userId, count = 5, createdAt = now.minusSeconds(60))

        val result = job.run(now)

        assertEquals(1, result.sent)
        val rows = notificationRowsFor(user.userId)
        assertEquals(1, rows.size)
        assertEquals(true, rows.single()[NotificationTable.title].contains("5 new spots"))
    }

    // ---------- 12h feed-open gate ----------

    @Test
    fun `feed opened within the last 12h skips, even with enough content`() = runTest {
        val now = Instant.parse("2026-06-16T18:30:00Z")
        val user = CommentTestSeed.seedUser("feedopenrecent")
        backdateAccount(user.userId, now.minus(Duration.ofDays(10)))
        seedUtcDevice(user.userId)
        seedRoPosts(user.userId, count = 10, createdAt = now.minusSeconds(60))
        transaction {
            UserTable.update({ UserTable.id eq user.userId }) {
                it[UserTable.lastFeedOpenAt] = now.minus(Duration.ofHours(1))
            }
        }

        val result = job.run(now)

        assertEquals(0, result.sent)
        assertEquals(0, notificationRowsFor(user.userId).size)
    }

    @Test
    fun `feed opened 13h ago no longer suppresses`() = runTest {
        val now = Instant.parse("2026-06-16T18:30:00Z")
        val user = CommentTestSeed.seedUser("feedopenold")
        backdateAccount(user.userId, now.minus(Duration.ofDays(10)))
        seedUtcDevice(user.userId)
        val lastOpen = now.minus(Duration.ofHours(13))
        transaction {
            UserTable.update({ UserTable.id eq user.userId }) { it[UserTable.lastFeedOpenAt] = lastOpen }
        }
        seedRoPosts(user.userId, count = 5, createdAt = lastOpen.plusSeconds(60))

        val result = job.run(now)

        assertEquals(1, result.sent)
    }

    // ---------- weekly cap ----------

    @Test
    fun `3 already sent this week caps the 4th send`() = runTest {
        val now = Instant.parse("2026-06-16T18:30:00Z")
        val user = CommentTestSeed.seedUser("weeklycapped")
        backdateAccount(user.userId, now.minus(Duration.ofDays(10)))
        seedUtcDevice(user.userId)
        seedRoPosts(user.userId, count = 10, createdAt = now.minusSeconds(60))
        transaction {
            repeat(3) { i ->
                eventService.record(
                    recipientId = user.userId,
                    category = com.revio.server.features.notification.NotificationCategory.DISCOVERY,
                    dedupeKey = "discovery:${user.userId}:day$i",
                    actorId = null, actorUsername = null, title = "t", body = "",
                )
            }
        }

        val result = job.run(now)

        assertEquals(0, result.sent)
    }

    // ---------- quiet hours: defer within 6h still sends (deferred), beyond 6h skips ----------

    @Test
    fun `evaluated at 00_30 local (UTC device) defers past the 6h max and is skipped entirely`() = runTest {
        // 00:30 is inside quiet hours (00:00-08:00); the deferred target (08:00) is 7.5h away.
        val now = Instant.parse("2026-06-17T00:30:00Z")
        val user = CommentTestSeed.seedUser("quiethoursfar")
        backdateAccount(user.userId, now.minus(Duration.ofDays(10)))
        seedUtcDevice(user.userId)
        seedRoPosts(user.userId, count = 5, createdAt = now.minusSeconds(60))

        val result = job.run(now)

        assertEquals(0, result.sent)
        assertEquals(0, notificationRowsFor(user.userId).size)
    }

    @Test
    fun `evaluated at 03_00 local (UTC device) defers within the 6h max and still records, deferred`() = runTest {
        // 03:00 is inside quiet hours; the deferred target (08:00) is only 5h away - within budget.
        val now = Instant.parse("2026-06-17T03:00:00Z")
        val user = CommentTestSeed.seedUser("quiethoursclose")
        backdateAccount(user.userId, now.minus(Duration.ofDays(10)))
        seedUtcDevice(user.userId)
        seedRoPosts(user.userId, count = 5, createdAt = now.minusSeconds(60))

        val result = job.run(now)

        assertEquals(1, result.sent)
        val rows = notificationRowsFor(user.userId)
        assertEquals(1, rows.size)

        val notificationId = rows.single()[NotificationTable.id].value
        val outboxRow = transaction {
            NotificationOutboxTable.selectAll().where { NotificationOutboxTable.notificationId eq notificationId }.single()
        }
        assertNotNull(outboxRow[NotificationOutboxTable.notBefore], "a deferred discovery send must carry a notBefore")
    }

    // ---------- discovery preference off ----------

    @Test
    fun `discovery preference off skips regardless of everything else`() = runTest {
        val now = Instant.parse("2026-06-16T18:30:00Z")
        val user = CommentTestSeed.seedUser("prefoff")
        backdateAccount(user.userId, now.minus(Duration.ofDays(10)))
        seedUtcDevice(user.userId)
        seedRoPosts(user.userId, count = 10, createdAt = now.minusSeconds(60))
        prefsDao.update(user.userId, discoveryEnabled = false)

        val result = job.run(now)

        assertEquals(0, result.sent)
    }

    // ---------- account age ----------

    @Test
    fun `a brand new account is not a candidate at all`() = runTest {
        val now = Instant.parse("2026-06-16T18:30:00Z")
        val user = CommentTestSeed.seedUser("toonew")
        // createdAt stays at "now" (default), well under the 3-day minimum.
        seedUtcDevice(user.userId)
        seedRoPosts(user.userId, count = 10, createdAt = now.minusSeconds(60))

        val result = job.run(now)

        assertEquals(0, notificationRowsFor(user.userId).size)
    }
}
