package com.revio.server.features.notification

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

data class Notification(
    val id: UUID,
    val userId: UUID,
    val type: NotificationType,
    val title: String,
    val body: String,
    val blocking: Boolean,
    val createdAt: Instant,
    val readAt: Instant?,
)

interface INotificationDAO {
    /** Inserts a notification in its own transaction. */
    suspend fun insert(userId: UUID, type: NotificationType, title: String, body: String, blocking: Boolean): UUID

    /**
     * Same insert, but participates in a transaction already open on the calling thread — needed
     * so a moderation action (e.g. a post removal) and its notification commit or roll back
     * together.
     */
    fun insertInCurrentTransaction(userId: UUID, type: NotificationType, title: String, body: String, blocking: Boolean): UUID

    /** Notifications for [userId], newest first. */
    suspend fun listForUser(userId: UUID, limit: Int): List<Notification>

    /** Count of [userId]'s notifications with `read_at IS NULL`. */
    suspend fun countUnread(userId: UUID): Long

    /**
     * Marks [notificationId] read, scoped to [userId] so one user can never mark another's
     * notification read. @return true if a row was updated.
     */
    suspend fun markRead(notificationId: UUID, userId: UUID): Boolean

    /** Marks every unread notification for [userId] read. @return number of rows updated. */
    suspend fun markAllRead(userId: UUID): Int
}

class NotificationDAO : INotificationDAO {

    override suspend fun insert(
        userId: UUID,
        type: NotificationType,
        title: String,
        body: String,
        blocking: Boolean,
    ): UUID = transaction {
        insertInCurrentTransaction(userId, type, title, body, blocking)
    }

    override fun insertInCurrentTransaction(
        userId: UUID,
        type: NotificationType,
        title: String,
        body: String,
        blocking: Boolean,
    ): UUID = NotificationTable.insert {
        it[NotificationTable.userId] = userId
        it[NotificationTable.type] = type
        it[NotificationTable.title] = title
        it[NotificationTable.body] = body
        it[NotificationTable.blocking] = blocking
    }[NotificationTable.id].value

    override suspend fun listForUser(userId: UUID, limit: Int): List<Notification> = transaction {
        NotificationTable
            .selectAll()
            .where { NotificationTable.userId eq userId }
            .orderBy(NotificationTable.createdAt to SortOrder.DESC)
            .limit(limit)
            .map { it.toNotification() }
    }

    override suspend fun countUnread(userId: UUID): Long = transaction {
        NotificationTable
            .selectAll()
            .where { (NotificationTable.userId eq userId) and (NotificationTable.readAt.isNull()) }
            .count()
    }

    override suspend fun markRead(notificationId: UUID, userId: UUID): Boolean = transaction {
        // Not conditioned on readAt IS NULL: re-marking an already-read notification is a no-op
        // success (bumps read_at), not a failure — the route stays idempotent under retries.
        val updated = NotificationTable.update({
            (NotificationTable.id eq notificationId) and (NotificationTable.userId eq userId)
        }) {
            it[NotificationTable.readAt] = Instant.now()
        }
        updated > 0
    }

    override suspend fun markAllRead(userId: UUID): Int = transaction {
        NotificationTable.update({
            (NotificationTable.userId eq userId) and (NotificationTable.readAt.isNull())
        }) {
            it[NotificationTable.readAt] = Instant.now()
        }
    }

    private fun ResultRow.toNotification() = Notification(
        id = this[NotificationTable.id].value,
        userId = this[NotificationTable.userId],
        type = this[NotificationTable.type],
        title = this[NotificationTable.title],
        body = this[NotificationTable.body],
        blocking = this[NotificationTable.blocking],
        createdAt = this[NotificationTable.createdAt],
        readAt = this[NotificationTable.readAt],
    )
}
