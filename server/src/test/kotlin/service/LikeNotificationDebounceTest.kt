package service

import com.revio.server.features.notification.NotificationEventService
import com.revio.server.features.notification.NotificationOutboxDAO
import com.revio.server.features.notification.NotificationOutboxTable
import com.revio.server.features.notification.NotificationTable
import com.revio.server.features.notification.UserDeviceDAO
import com.revio.server.features.post.PostDAO
import com.revio.server.features.scoring.IScoringService
import com.revio.server.features.notification.UserNotificationPrefsDAO
import com.revio.server.features.user.UserDao
import features.like.LikeDAO
import features.like.LikeNotificationCursorDAO
import features.like.LikeService
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
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
import java.time.Instant

/**
 * Real end-to-end coverage for the like notification hook + 60s debounce (plan §18, step 5.1):
 * LikeService + real LikeDAO/PostDAO/UserDeviceDAO/NotificationOutboxDAO against Testcontainers
 * Postgres, scoring mocked out (irrelevant here). "device" here is registered directly via
 * UserDeviceDAO so the outbox has a real fan-out target.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LikeNotificationDebounceTest {

    private val likeDao = LikeDAO()
    private val postDao = PostDAO()
    private val scoringService = mockk<IScoringService>(relaxed = true)
    private val notificationEventService = NotificationEventService()
    private val userDeviceDao = UserDeviceDAO()
    private val notificationOutboxDao = NotificationOutboxDAO()
    private val likeNotificationCursorDao = LikeNotificationCursorDAO()
    private val userDao = UserDao()
    private val notificationPrefsDao = UserNotificationPrefsDAO()

    // Flag on (plan §18, step 5.7) — this class is specifically about the debounce/enqueue
    // mechanics, not the flag/prefs gate itself (see LikeServiceTest for that coverage).
    private val service = LikeService(
        likeDao,
        postDao,
        scoringService,
        notificationEventService,
        userDeviceDao,
        notificationOutboxDao,
        likeNotificationCursorDao,
        userDao,
        notificationPrefsDao,
        likesPushEnabledProvider = { "true" },
    )

    @BeforeAll
    fun setup() = TestDatabaseFactory.start()

    @AfterAll
    fun tearDown() = TestDatabaseFactory.stop()

    @BeforeEach
    fun clean() = TestDatabaseFactory.cleanDatabase()

    @Test
    fun `a self-like creates no notification row at all`() = runTest {
        val owner = CommentTestSeed.seedUser("owner1")
        val post = CommentTestSeed.seedPost(owner.userId)

        service.toggleLike(owner.userId, post.postId)

        val rows = transaction { NotificationTable.selectAll().where { NotificationTable.userId eq owner.userId }.toList() }
        assertEquals(0, rows.size)
    }

    @Test
    fun `the first like from someone else schedules the outbox row about 60s out, not immediately`() = runTest {
        val owner = CommentTestSeed.seedUser("owner2")
        val post = CommentTestSeed.seedPost(owner.userId)
        val alice = CommentTestSeed.seedUser("alice2")
        val deviceId = userDeviceDao.registerDevice(
            userId = owner.userId,
            deviceId = "device-1",
            fcmToken = "token-1",
            firebaseProject = com.revio.server.features.notification.FirebaseProject.DEBUG,
            platform = com.revio.server.features.notification.DevicePlatform.ANDROID,
            appVersion = "1.0.0",
            timezone = null,
            locale = null,
        )

        val before = Instant.now()
        service.toggleLike(alice.userId, post.postId)
        val after = Instant.now()

        val notificationRow = transaction {
            NotificationTable.selectAll().where { NotificationTable.userId eq owner.userId }.single()
        }
        assertEquals(1, notificationRow[NotificationTable.actorCount])

        val outboxRow = transaction {
            NotificationOutboxTable
                .selectAll()
                .where {
                    (NotificationOutboxTable.notificationId eq notificationRow[NotificationTable.id].value) and
                        (NotificationOutboxTable.deviceId eq deviceId)
                }
                .single()
        }
        val scheduledFor = outboxRow[NotificationOutboxTable.nextAttemptAt].toInstant()
        assertTrue(scheduledFor.isAfter(before.plusSeconds(50)), "expected ~60s out, was $scheduledFor")
        assertTrue(scheduledFor.isBefore(after.plusSeconds(70)), "expected ~60s out, was $scheduledFor")
        assertTrue(scheduledFor.isAfter(after.plusSeconds(1)), "must not be scheduled immediately (before=$before, after=$after, scheduledFor=$scheduledFor)")
    }

    @Test
    fun `a like with no active devices records the event but enqueues nothing`() = runTest {
        val owner = CommentTestSeed.seedUser("owner3")
        val post = CommentTestSeed.seedPost(owner.userId)
        val alice = CommentTestSeed.seedUser("alice3")

        service.toggleLike(alice.userId, post.postId)

        val notificationRow = transaction {
            NotificationTable.selectAll().where { NotificationTable.userId eq owner.userId }.single()
        }
        val outboxRows = transaction {
            NotificationOutboxTable
                .selectAll()
                .where { NotificationOutboxTable.notificationId eq notificationRow[NotificationTable.id].value }
                .toList()
        }
        assertEquals(0, outboxRows.size)
    }
}
