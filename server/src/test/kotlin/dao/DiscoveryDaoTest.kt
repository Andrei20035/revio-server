package dao

import com.revio.server.features.notification.DiscoveryDAO
import com.revio.server.features.notification.NotificationCategory
import com.revio.server.features.notification.NotificationEventService
import com.revio.server.features.notification.UserDeviceDAO
import com.revio.server.features.notification.FirebaseProject
import com.revio.server.features.notification.DevicePlatform
import com.revio.server.features.post.PostTable
import com.revio.server.features.user.UserTable
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import testutils.CommentTestSeed
import testutils.TestDatabaseFactory
import testutils.UserTestSeed
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Real Testcontainers Postgres coverage for DiscoveryDAO (plan §18, step 6.3): the
 * country-scoped content count, the account-age candidate filter, the most-recent-device
 * timezone lookup, and the weekly discovery-sent count.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DiscoveryDaoTest {

    private val dao = DiscoveryDAO()
    private val eventService = NotificationEventService()
    private val deviceDao = UserDeviceDAO()

    @BeforeAll
    fun setup() = TestDatabaseFactory.start()

    @AfterAll
    fun tearDown() = TestDatabaseFactory.stop()

    @BeforeEach
    fun clean() = TestDatabaseFactory.cleanDatabase()

    private fun backdateUserCreatedAt(userId: UUID, at: Instant) = transaction {
        UserTable.update({ UserTable.id eq userId }) { it[UserTable.createdAt] = at }
    }

    private fun setPostCreatedAt(postId: UUID, country: String, at: Instant) = transaction {
        PostTable.update({ PostTable.id eq postId }) {
            it[PostTable.country] = country
            it[PostTable.createdAt] = at
        }
    }

    // ---------- findCandidates: account-age filter ----------

    @Test
    fun `findCandidates excludes a brand new account`() = runTest {
        val now = Instant.parse("2026-06-15T12:00:00Z")
        val user = CommentTestSeed.seedUser("brandnew")
        backdateUserCreatedAt(user.userId, now.minusSeconds(3600))

        val candidates = dao.findCandidates(now)

        assertTrue(candidates.none { it.userId == user.userId })
    }

    @Test
    fun `findCandidates includes an account exactly 3 days old`() = runTest {
        val now = Instant.parse("2026-06-15T12:00:00Z")
        val user = CommentTestSeed.seedUser("threedaysold")
        backdateUserCreatedAt(user.userId, now.minus(Duration.ofDays(3)))

        val candidates = dao.findCandidates(now)

        val candidate = candidates.single { it.userId == user.userId }
        assertEquals("RO", candidate.country)
    }

    // ---------- countNewPostsInCountrySince ----------

    @Test
    fun `countNewPostsInCountrySince only counts posts in the given country after the cutoff`() = runTest {
        val since = Instant.parse("2026-06-15T00:00:00Z")
        val author = CommentTestSeed.seedUser("author1")

        val counted = CommentTestSeed.seedPost(author.userId)
        setPostCreatedAt(counted.postId, "RO", since.plusSeconds(60))

        val tooOld = CommentTestSeed.seedPost(author.userId)
        setPostCreatedAt(tooOld.postId, "RO", since.minusSeconds(60))

        val wrongCountry = CommentTestSeed.seedPost(author.userId)
        setPostCreatedAt(wrongCountry.postId, "US", since.plusSeconds(60))

        assertEquals(1, dao.countNewPostsInCountrySince("RO", since))
    }

    // ---------- findMostRecentDeviceTimezone ----------

    @Test
    fun `findMostRecentDeviceTimezone returns null when the user has no devices`() = runTest {
        val user = CommentTestSeed.seedUser("nodevice")
        assertNull(dao.findMostRecentDeviceTimezone(user.userId))
    }

    @Test
    fun `findMostRecentDeviceTimezone returns the timezone of the most recently seen device`() = runTest {
        val user = CommentTestSeed.seedUser("twodevices")
        deviceDao.registerDevice(
            userId = user.userId, deviceId = "old-device", fcmToken = "token-old",
            firebaseProject = FirebaseProject.DEBUG, platform = DevicePlatform.ANDROID,
            appVersion = "1.0.0", timezone = "America/New_York", locale = null,
        )
        deviceDao.registerDevice(
            userId = user.userId, deviceId = "new-device", fcmToken = "token-new",
            firebaseProject = FirebaseProject.DEBUG, platform = DevicePlatform.ANDROID,
            appVersion = "1.0.0", timezone = "Europe/Bucharest", locale = null,
        )

        assertEquals("Europe/Bucharest", dao.findMostRecentDeviceTimezone(user.userId))
    }

    // ---------- countDiscoverySentSince ----------

    @Test
    fun `countDiscoverySentSince only counts DISCOVERY category rows within the window`() = runTest {
        val user = CommentTestSeed.seedUser("weeklycount")
        val since = Instant.now().minus(Duration.ofDays(7))

        transaction {
            eventService.record(
                recipientId = user.userId, category = NotificationCategory.DISCOVERY,
                dedupeKey = "discovery:${user.userId}:d1", actorId = null, actorUsername = null,
                title = "t", body = "",
            )
            eventService.record(
                recipientId = user.userId, category = NotificationCategory.LIKES,
                dedupeKey = "like:other:w1", actorId = null, actorUsername = null,
                title = "t", body = "",
            )
        }

        assertEquals(1, dao.countDiscoverySentSince(user.userId, since))
    }
}
