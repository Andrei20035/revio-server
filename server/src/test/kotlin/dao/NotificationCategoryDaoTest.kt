package dao

import com.revio.server.features.moderation.BanDAO
import com.revio.server.features.notification.NotificationCategory
import com.revio.server.features.notification.NotificationDAO
import com.revio.server.features.notification.NotificationTable
import com.revio.server.features.notification.NotificationType
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import testutils.TestDatabaseFactory
import testutils.UserTestSeed
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationCategoryDaoTest {

    private val notificationDao = NotificationDAO()

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

    /** Inserts a notification then stamps its category — [NotificationDAO.insert] always defaults to ACCOUNT. */
    private suspend fun seedNotification(
        userId: UUID,
        category: NotificationCategory,
        readAt: Boolean = false,
        blocking: Boolean = false,
    ): UUID {
        val id = notificationDao.insert(userId, NotificationType.POST_REMOVED, "title", "body", blocking = blocking)
        transaction {
            NotificationTable.update({ NotificationTable.id eq id }) {
                it[NotificationTable.category] = category
                if (readAt) it[NotificationTable.readAt] = java.time.Instant.now()
            }
        }
        return id
    }

    private fun isRead(id: UUID): Boolean = transaction {
        NotificationTable
            .selectAll()
            .where { NotificationTable.id eq id }
            .single()[NotificationTable.readAt] != null
    }

    @Test
    fun `listForUserAfter with category ACCOUNT returns only ACCOUNT rows`() = runTest {
        val userId = seedUser()
        val accountId = seedNotification(userId, NotificationCategory.ACCOUNT)
        seedNotification(userId, NotificationCategory.LIKES)
        seedNotification(userId, NotificationCategory.COMMENTS)

        val rows = notificationDao.listForUserAfter(userId, 10, null, null, category = NotificationCategory.ACCOUNT)

        assertEquals(listOf(accountId), rows.map { it.id })
    }

    @Test
    fun `listForUserAfter with null category returns every category`() = runTest {
        val userId = seedUser()
        seedNotification(userId, NotificationCategory.ACCOUNT)
        seedNotification(userId, NotificationCategory.LIKES)
        seedNotification(userId, NotificationCategory.COMMENTS)

        val rows = notificationDao.listForUserAfter(userId, 10, null, null, category = null)

        assertEquals(3, rows.size)
    }

    @Test
    fun `countUnread with category ACCOUNT ignores an unread LIKE`() = runTest {
        val userId = seedUser()
        seedNotification(userId, NotificationCategory.ACCOUNT)
        seedNotification(userId, NotificationCategory.LIKES)

        val unreadAccount = notificationDao.countUnread(userId, category = NotificationCategory.ACCOUNT)

        assertEquals(1L, unreadAccount)
    }

    @Test
    fun `countUnread with null category counts every category`() = runTest {
        val userId = seedUser()
        seedNotification(userId, NotificationCategory.ACCOUNT)
        seedNotification(userId, NotificationCategory.LIKES)

        val unreadAll = notificationDao.countUnread(userId, category = null)

        assertEquals(2L, unreadAll)
    }

    @Test
    fun `countUnread with category ACCOUNT excludes an already-read ACCOUNT row`() = runTest {
        val userId = seedUser()
        seedNotification(userId, NotificationCategory.ACCOUNT, readAt = true)
        seedNotification(userId, NotificationCategory.ACCOUNT)

        val unreadAccount = notificationDao.countUnread(userId, category = NotificationCategory.ACCOUNT)

        assertEquals(1L, unreadAccount)
    }

    @Test
    fun `countUnread with default arguments matches explicit null category`() = runTest {
        val userId = seedUser()
        seedNotification(userId, NotificationCategory.ACCOUNT)
        seedNotification(userId, NotificationCategory.LIKES)

        assertEquals(notificationDao.countUnread(userId), notificationDao.countUnread(userId, category = null))
        assertTrue(notificationDao.countUnread(userId) == 2L)
    }

    @Test
    fun `markAllRead with category ACCOUNT and includeBlocking false does not touch an unread LIKE`() = runTest {
        val userId = seedUser()
        val likeId = seedNotification(userId, NotificationCategory.LIKES)
        seedNotification(userId, NotificationCategory.ACCOUNT)

        notificationDao.markAllRead(userId, category = NotificationCategory.ACCOUNT, includeBlocking = false)

        assertTrue(!isRead(likeId))
    }

    @Test
    fun `markAllRead with category ACCOUNT and includeBlocking false does not touch a blocking POST_REMOVED`() = runTest {
        val userId = seedUser()
        val blockingId = seedNotification(userId, NotificationCategory.ACCOUNT, blocking = true)

        notificationDao.markAllRead(userId, category = NotificationCategory.ACCOUNT, includeBlocking = false)

        assertTrue(!isRead(blockingId))
    }

    @Test
    fun `markAllRead with category ACCOUNT and includeBlocking false marks a non-blocking VIOLATION_REVOKED`() = runTest {
        val userId = seedUser()
        val violationRevokedId = seedNotification(userId, NotificationCategory.ACCOUNT, blocking = false)

        notificationDao.markAllRead(userId, category = NotificationCategory.ACCOUNT, includeBlocking = false)

        assertTrue(isRead(violationRevokedId))
    }

    @Test
    fun `markAllRead with category ACCOUNT and includeBlocking false returns the updated row count`() = runTest {
        val userId = seedUser()
        seedNotification(userId, NotificationCategory.ACCOUNT, blocking = false)
        seedNotification(userId, NotificationCategory.ACCOUNT, blocking = true)
        seedNotification(userId, NotificationCategory.LIKES, blocking = false)

        val updated = notificationDao.markAllRead(userId, category = NotificationCategory.ACCOUNT, includeBlocking = false)

        assertEquals(1, updated)
    }

    @Test
    fun `markAllRead with default arguments marks every unread row regardless of category or blocking`() = runTest {
        val userId = seedUser()
        val accountId = seedNotification(userId, NotificationCategory.ACCOUNT, blocking = false)
        val blockingId = seedNotification(userId, NotificationCategory.ACCOUNT, blocking = true)
        val likeId = seedNotification(userId, NotificationCategory.LIKES, blocking = false)

        val updated = notificationDao.markAllRead(userId)

        assertEquals(3, updated)
        assertTrue(isRead(accountId))
        assertTrue(isRead(blockingId))
        assertTrue(isRead(likeId))
    }

    @Test
    fun `a moderation notice inserted via BanDAO gets category ACCOUNT`() = runTest {
        val userId = seedUser()
        val adminId = seedUser(email = "admin@example.com", username = "admin")
        val banDao = BanDAO(notificationDao = notificationDao)

        banDao.banUserAtomically(
            userId = userId,
            bannedUntil = null,
            permanent = true,
            reason = "test",
            adminId = adminId,
            notificationBody = "You have been suspended.",
        )

        val category = transaction {
            NotificationTable
                .selectAll()
                .where { NotificationTable.userId eq userId }
                .single()[NotificationTable.category]
        }
        assertEquals(NotificationCategory.ACCOUNT, category)
    }
}
