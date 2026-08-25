package dao

import com.revio.server.features.notification.DevicePlatform
import com.revio.server.features.notification.FirebaseProject
import com.revio.server.features.notification.InactivityDAO
import com.revio.server.features.notification.UserDeviceDAO
import com.revio.server.features.post.PostTable
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

/**
 * Real Testcontainers Postgres coverage for InactivityDAO (plan §18, step 6.4): the
 * ever-posted candidate list, most-recent-device timezone, and last_app_open lookup.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InactivityDaoTest {

    private val dao = InactivityDAO()
    private val deviceDao = UserDeviceDAO()

    @BeforeAll
    fun setup() = TestDatabaseFactory.start()

    @AfterAll
    fun tearDown() = TestDatabaseFactory.stop()

    @BeforeEach
    fun clean() = TestDatabaseFactory.cleanDatabase()

    @Test
    fun `findCandidates excludes a user with zero posts`() = runTest {
        val user = CommentTestSeed.seedUser("noposts")
        val candidates = dao.findCandidates()
        assertTrue(candidates.none { it.userId == user.userId })
    }

    @Test
    fun `findCandidates includes a user with at least one post, using their most recent post's timestamp`() = runTest {
        val user = CommentTestSeed.seedUser("hasposts")
        val older = CommentTestSeed.seedPost(user.userId)
        val newer = CommentTestSeed.seedPost(user.userId)
        val olderAt = java.time.Instant.now().minusSeconds(3600)
        val newerAt = java.time.Instant.now()
        transaction {
            PostTable.update({ PostTable.id eq older.postId }) { it[PostTable.createdAt] = olderAt }
            PostTable.update({ PostTable.id eq newer.postId }) { it[PostTable.createdAt] = newerAt }
        }

        val matches = dao.findCandidates().filter { it.userId == user.userId }

        assertEquals(1, matches.size, "one row per user, not per post")
        assertEquals(newerAt, matches.single().lastPostAt)
    }

    @Test
    fun `findMostRecentDeviceTimezone returns null with no devices`() = runTest {
        val user = CommentTestSeed.seedUser("notz")
        assertNull(dao.findMostRecentDeviceTimezone(user.userId))
    }

    @Test
    fun `findMostRecentDeviceTimezone returns the most recently seen device's zone`() = runTest {
        val user = CommentTestSeed.seedUser("twotz")
        deviceDao.registerDevice(
            userId = user.userId, deviceId = "d-old", fcmToken = "t-old",
            firebaseProject = FirebaseProject.DEBUG, platform = DevicePlatform.ANDROID,
            appVersion = "1.0.0", timezone = "America/New_York", locale = null,
        )
        deviceDao.registerDevice(
            userId = user.userId, deviceId = "d-new", fcmToken = "t-new",
            firebaseProject = FirebaseProject.DEBUG, platform = DevicePlatform.ANDROID,
            appVersion = "1.0.0", timezone = "Europe/Bucharest", locale = null,
        )

        assertEquals("Europe/Bucharest", dao.findMostRecentDeviceTimezone(user.userId))
    }

    @Test
    fun `findLastAppOpen returns null with no devices`() = runTest {
        val user = CommentTestSeed.seedUser("nolastopen")
        assertNull(dao.findLastAppOpen(user.userId))
    }

    @Test
    fun `findLastAppOpen returns the most recent last_seen_at across devices`() = runTest {
        val user = CommentTestSeed.seedUser("lastopen")
        deviceDao.registerDevice(
            userId = user.userId, deviceId = "d1", fcmToken = "t1",
            firebaseProject = FirebaseProject.DEBUG, platform = DevicePlatform.ANDROID,
            appVersion = "1.0.0", timezone = null, locale = null,
        )

        val lastOpen = dao.findLastAppOpen(user.userId)
        assertTrue(lastOpen != null)
    }
}
