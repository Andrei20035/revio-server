package dao

import com.revio.server.features.notification.DevicePlatform
import com.revio.server.features.notification.FirebaseProject
import com.revio.server.features.notification.NotificationDAO
import com.revio.server.features.notification.NotificationOutboxDAO
import com.revio.server.features.notification.NotificationOutboxTable
import com.revio.server.features.notification.NotificationTable
import com.revio.server.features.notification.NotificationType
import com.revio.server.features.notification.OutboxState
import com.revio.server.features.notification.UserDeviceDAO
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import testutils.TestDatabaseFactory
import testutils.UserTestSeed
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationOutboxDaoTest {

    private val outboxDao = NotificationOutboxDAO()
    private val notificationDao = NotificationDAO()
    private val deviceDao = UserDeviceDAO()

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

    private suspend fun seedUser(email: String = "user@example.com", username: String = "alice") =
        UserTestSeed.seedUser(UserTestSeed.seedAuthCredential(email).authCredentialId, username = username)

    private suspend fun seedNotification(userId: UUID): UUID =
        notificationDao.insert(userId, NotificationType.POST_REMOVED, "title", "body", blocking = false)

    private suspend fun seedDevice(userId: UUID, deviceId: String = "device-1"): UUID =
        deviceDao.registerDevice(
            userId = userId,
            deviceId = deviceId,
            fcmToken = "token-$deviceId",
            firebaseProject = FirebaseProject.DEBUG,
            platform = DevicePlatform.ANDROID,
            appVersion = "1.0.0",
            timezone = null,
            locale = null,
        )

    @Test
    fun `enqueueing the same notification and device twice creates only one row`() = runTest {
        val userId = seedUser()
        val notificationId = seedNotification(userId)
        val deviceId = seedDevice(userId)

        outboxDao.enqueue(notificationId, deviceId)
        outboxDao.enqueue(notificationId, deviceId)

        val rowCount = transaction {
            NotificationOutboxTable
                .selectAll()
                .where {
                    (NotificationOutboxTable.notificationId eq notificationId) and
                        (NotificationOutboxTable.deviceId eq deviceId)
                }
                .count()
        }
        assertEquals(1, rowCount)

        val entry = outboxDao.find(notificationId, deviceId)
        assertEquals(OutboxState.PENDING, entry?.state)
    }

    @Test
    fun `two devices for the same notification get two independent outbox rows`() = runTest {
        val userId = seedUser()
        val notificationId = seedNotification(userId)
        val deviceA = seedDevice(userId, "device-a")
        val deviceB = seedDevice(userId, "device-b")

        outboxDao.enqueue(notificationId, deviceA)
        outboxDao.enqueue(notificationId, deviceB)

        assertTrue(outboxDao.find(notificationId, deviceA) != null)
        assertTrue(outboxDao.find(notificationId, deviceB) != null)
    }

    @Test
    fun `findDrainable returns only PENDING and FAILED rows due now, oldest first`() = runTest {
        val userId = seedUser()
        val past = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5)
        val future = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1)

        val duePending = seedNotification(userId).also { outboxDao.enqueue(it, seedDevice(userId, "d-due-pending"), notBefore = past) }
        val notYetDue = seedNotification(userId).also { outboxDao.enqueue(it, seedDevice(userId, "d-not-due"), notBefore = future) }

        val drainable = outboxDao.findDrainable(limit = 10)

        assertTrue(drainable.any { it.notificationId == duePending })
        assertTrue(drainable.none { it.notificationId == notYetDue })
    }

    @Test
    fun `deleting the parent notification cascades to its outbox rows`() = runTest {
        val userId = seedUser()
        val notificationId = seedNotification(userId)
        val deviceId = seedDevice(userId)
        outboxDao.enqueue(notificationId, deviceId)

        transaction {
            NotificationTable.deleteWhere { NotificationTable.id eq notificationId }
        }

        assertEquals(null, outboxDao.find(notificationId, deviceId))
    }

    @Test
    fun `countQueued counts only PENDING and FAILED rows`() = runTest {
        val userId = seedUser()

        val pendingId = seedNotification(userId).also { outboxDao.enqueue(it, seedDevice(userId, "d-pending")) }
        val failedId = seedNotification(userId).also { outboxDao.enqueue(it, seedDevice(userId, "d-failed")) }
        val acceptedId = seedNotification(userId).also { outboxDao.enqueue(it, seedDevice(userId, "d-accepted")) }

        val failedEntry = outboxDao.find(failedId, deviceDao.findByUserAndDevice(userId, "d-failed")!!.id)!!
        outboxDao.markRetriableFailure(failedEntry.id, attempts = 1, nextAttemptAt = OffsetDateTime.now(ZoneOffset.UTC), lastErrorCode = "HTTP_500")

        val acceptedEntry = outboxDao.find(acceptedId, deviceDao.findByUserAndDevice(userId, "d-accepted")!!.id)!!
        outboxDao.markAccepted(acceptedEntry.id, "projects/x/messages/1")

        assertEquals(2, outboxDao.countQueued())
    }
}
