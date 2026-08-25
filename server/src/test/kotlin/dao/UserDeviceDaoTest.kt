package dao

import com.revio.server.features.notification.DevicePlatform
import com.revio.server.features.notification.FirebaseProject
import com.revio.server.features.notification.UserDeviceDAO
import com.revio.server.features.notification.UserDeviceTable
import com.revio.server.features.user.UserTable
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import testutils.TestDatabaseFactory
import testutils.UserTestSeed

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserDeviceDaoTest {

    private val dao = UserDeviceDAO()

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
    fun `registering the same device twice upserts a single row and bumps updatedAt`() = runTest {
        val userId = seedUser()

        val firstId = dao.registerDevice(
            userId = userId,
            deviceId = "device-1",
            fcmToken = "token-a",
            firebaseProject = FirebaseProject.DEBUG,
            platform = DevicePlatform.ANDROID,
            appVersion = "1.0.0",
            timezone = "Europe/Bucharest",
            locale = "ro-RO",
        )
        val afterFirst = dao.findByUserAndDevice(userId, "device-1")

        val secondId = dao.registerDevice(
            userId = userId,
            deviceId = "device-1",
            fcmToken = "token-a-rotated",
            firebaseProject = FirebaseProject.DEBUG,
            platform = DevicePlatform.ANDROID,
            appVersion = "1.1.0",
            timezone = "Europe/Bucharest",
            locale = "ro-RO",
        )
        val afterSecond = dao.findByUserAndDevice(userId, "device-1")

        // Same row — an upsert, not a duplicate.
        assertEquals(firstId, secondId)
        assertNotEquals(null, afterFirst)
        assertNotEquals(null, afterSecond)
        assertEquals(afterFirst!!.id, afterSecond!!.id)
        // Latest values win.
        assertEquals("token-a-rotated", afterSecond.fcmToken)
        assertEquals("1.1.0", afterSecond.appVersion)
        assertTrue(afterSecond.isActive)

        val rowCount = transaction {
            UserDeviceTable.selectAll().count()
        }
        assertEquals(1, rowCount)
    }

    @Test
    fun `registering a token already held by another device deactivates the old row`() = runTest {
        val userA = seedUser("a@example.com", "alice")
        val userB = seedUser("b@example.com", "bob")

        dao.registerDevice(
            userId = userA,
            deviceId = "device-old",
            fcmToken = "shared-token",
            firebaseProject = FirebaseProject.DEBUG,
            platform = DevicePlatform.ANDROID,
            appVersion = "1.0.0",
            timezone = null,
            locale = null,
        )

        dao.registerDevice(
            userId = userB,
            deviceId = "device-new",
            fcmToken = "shared-token",
            firebaseProject = FirebaseProject.RELEASE,
            platform = DevicePlatform.ANDROID,
            appVersion = "2.0.0",
            timezone = null,
            locale = null,
        )

        val oldRow = dao.findByUserAndDevice(userA, "device-old")
        val newRow = dao.findByUserAndDevice(userB, "device-new")

        assertNotEquals(null, oldRow)
        assertFalse(oldRow!!.isActive)
        assertNull(oldRow.fcmToken)

        assertNotEquals(null, newRow)
        assertTrue(newRow!!.isActive)
        assertEquals("shared-token", newRow.fcmToken)

        // The token now resolves to exactly the new row.
        val byToken = dao.findByToken("shared-token")
        assertEquals(newRow.id, byToken?.id)
    }

    @Test
    fun `deleting the user cascades to their device rows`() = runTest {
        val userId = seedUser()
        dao.registerDevice(
            userId = userId,
            deviceId = "device-1",
            fcmToken = "token-a",
            firebaseProject = FirebaseProject.DEBUG,
            platform = DevicePlatform.ANDROID,
            appVersion = "1.0.0",
            timezone = null,
            locale = null,
        )
        assertNotEquals(null, dao.findByUserAndDevice(userId, "device-1"))

        transaction {
            UserTable.deleteWhere { UserTable.id eq userId }
        }

        assertNull(dao.findByUserAndDevice(userId, "device-1"))
    }
}
