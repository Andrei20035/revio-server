package dao

import com.revio.server.features.notification.UserNotificationPrefsDAO
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import testutils.TestDatabaseFactory
import testutils.UserTestSeed
import java.time.LocalTime

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserNotificationPrefsDaoTest {

    private val dao = UserNotificationPrefsDAO()

    @BeforeAll
    fun setup() {
        TestDatabaseFactory.start()
    }

    @AfterAll
    fun tearDown() {
        TestDatabaseFactory.stop()
    }

    @BeforeEach
    fun clean() {
        TestDatabaseFactory.cleanDatabase()
    }

    private fun seedUser(email: String = "user@example.com", username: String = "alice") =
        UserTestSeed.seedUser(UserTestSeed.seedAuthCredential(email).authCredentialId, username = username)

    @Test
    fun `a user with no row reads as every category enabled with default quiet hours`() = runTest {
        val userId = seedUser()

        val prefs = dao.get(userId)

        assertTrue(prefs.likesEnabled)
        assertTrue(prefs.commentsEnabled)
        assertTrue(prefs.discoveryEnabled)
        assertTrue(prefs.remindersEnabled)
        assertEquals(LocalTime.of(0, 0), prefs.quietStart)
        assertEquals(LocalTime.of(8, 0), prefs.quietEnd)
    }

    @Test
    fun `a partial update does not reset the other fields`() = runTest {
        val userId = seedUser()

        // First write turns discovery off, leaving everything else at its default.
        val afterFirst = dao.update(userId, discoveryEnabled = false)
        assertTrue(afterFirst.likesEnabled)
        assertTrue(afterFirst.commentsEnabled)
        assertEquals(false, afterFirst.discoveryEnabled)
        assertTrue(afterFirst.remindersEnabled)

        // Second, unrelated write turns likes off — discovery must stay off, not bounce back to true.
        val afterSecond = dao.update(userId, likesEnabled = false)
        assertEquals(false, afterSecond.likesEnabled)
        assertTrue(afterSecond.commentsEnabled)
        assertEquals(false, afterSecond.discoveryEnabled)
        assertTrue(afterSecond.remindersEnabled)

        // Reading back independently confirms the row, not just the returned value, is correct.
        val reread = dao.get(userId)
        assertEquals(false, reread.likesEnabled)
        assertEquals(false, reread.discoveryEnabled)
        assertTrue(reread.commentsEnabled)
        assertTrue(reread.remindersEnabled)
    }

    @Test
    fun `updating quiet hours does not affect category toggles`() = runTest {
        val userId = seedUser()
        dao.update(userId, remindersEnabled = false)

        val updated = dao.update(userId, quietStart = LocalTime.of(22, 30), quietEnd = LocalTime.of(7, 0))

        assertEquals(LocalTime.of(22, 30), updated.quietStart)
        assertEquals(LocalTime.of(7, 0), updated.quietEnd)
        assertEquals(false, updated.remindersEnabled)
    }
}
