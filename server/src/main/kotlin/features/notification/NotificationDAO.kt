package com.revio.server.features.notification

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
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
    /** Last time this row's content changed (initially set to createdAt; moves on every aggregation upsert/withdraw). */
    val updatedAt: Instant,
    /** ACCOUNT for every pre-existing moderation row; LIKES/COMMENTS/DISCOVERY/REMINDERS for a social row (V36). */
    val category: NotificationCategory,
    /** Target spot for a LIKES/COMMENTS row — null for a non-social row, or a tombstone (deleted spot, `ON DELETE SET NULL`). */
    val postId: UUID?,
    /** Target comment for a COMMENTS row — same tombstone behavior as [postId]. */
    val commentId: UUID?,
    val deepLink: String?,
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

    /**
     * Notifications for [userId], newest first, keyset-paginated: when [cursorCreatedAt] and
     * [cursorId] are both non-null, only rows strictly before that (created_at, id) pair are
     * returned. Ordered by (created_at DESC, id DESC) — the id tie-break, mirroring
     * [com.revio.server.features.post.PostDAO.listFeed], keeps pagination stable (no skipped or
     * duplicated rows) when two notifications share a created_at and under concurrent inserts.
     *
     * @param category when non-null, restricts the page to that category (e.g. ACCOUNT for the
     *   Notices inbox); null returns every category, unchanged from prior behavior.
     */
    suspend fun listForUserAfter(
        userId: UUID,
        limit: Int,
        cursorCreatedAt: Instant?,
        cursorId: UUID?,
        category: NotificationCategory? = null,
    ): List<Notification>

    /**
     * Count of [userId]'s notifications with `read_at IS NULL`.
     *
     * @param category when non-null, counts only that category; null counts every category,
     *   unchanged from prior behavior.
     */
    suspend fun countUnread(userId: UUID, category: NotificationCategory? = null): Long

    /**
     * Marks [notificationId] read, scoped to [userId] so one user can never mark another's
     * notification read. @return true if a row was updated.
     */
    suspend fun markRead(notificationId: UUID, userId: UUID): Boolean

    /**
     * Marks every unread notification for [userId] read.
     *
     * @param category when non-null, restricts the update to that category; null updates every
     *   category, unchanged from prior behavior.
     * @param includeBlocking when false, leaves `blocking = true` rows untouched — a blocking
     *   notice (e.g. post removed, account suspended) can only be acknowledged through its
     *   [com.revio.server.features.moderation.ModerationService]-driven dialog flow, never by a
     *   bulk read-all. Defaults to true, matching prior behavior.
     * @return number of rows updated.
     */
    suspend fun markAllRead(userId: UUID, category: NotificationCategory? = null, includeBlocking: Boolean = true): Int
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
        // Every caller of insert()/insertInCurrentTransaction() is a moderation notice (post
        // removal, ban, violation revocation, ...) — social rows go through
        // NotificationEventService.upsert instead, which sets its own category. Setting this
        // explicitly (rather than relying on the DB column default) makes the ACCOUNT invariant
        // for moderation notices hold in Kotlin, not just in the schema.
        it[NotificationTable.category] = NotificationCategory.ACCOUNT
    }[NotificationTable.id].value

    override suspend fun listForUser(userId: UUID, limit: Int): List<Notification> = transaction {
        NotificationTable
            .selectAll()
            .where { NotificationTable.userId eq userId }
            .orderBy(NotificationTable.createdAt to SortOrder.DESC)
            .limit(limit)
            .map { it.toNotification() }
    }

    override suspend fun listForUserAfter(
        userId: UUID,
        limit: Int,
        cursorCreatedAt: Instant?,
        cursorId: UUID?,
        category: NotificationCategory?,
    ): List<Notification> = transaction {
        val query = NotificationTable
            .selectAll()
            .where { NotificationTable.userId eq userId }

        if (cursorCreatedAt != null && cursorId != null) {
            query.andWhere {
                (NotificationTable.createdAt less cursorCreatedAt) or
                    ((NotificationTable.createdAt eq cursorCreatedAt) and (NotificationTable.id less cursorId))
            }
        }

        if (category != null) {
            query.andWhere { NotificationTable.category eq category }
        }

        query
            .orderBy(NotificationTable.createdAt to SortOrder.DESC, NotificationTable.id to SortOrder.DESC)
            .limit(limit)
            .map { it.toNotification() }
    }

    override suspend fun countUnread(userId: UUID, category: NotificationCategory?): Long = transaction {
        val query = NotificationTable
            .selectAll()
            .where { (NotificationTable.userId eq userId) and (NotificationTable.readAt.isNull()) }

        if (category != null) {
            query.andWhere { NotificationTable.category eq category }
        }

        query.count()
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

    override suspend fun markAllRead(userId: UUID, category: NotificationCategory?, includeBlocking: Boolean): Int = transaction {
        NotificationTable.update({
            var condition = (NotificationTable.userId eq userId) and (NotificationTable.readAt.isNull())
            if (category != null) condition = condition and (NotificationTable.category eq category)
            if (!includeBlocking) condition = condition and (NotificationTable.blocking eq false)
            condition
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
        updatedAt = this[NotificationTable.updatedAt],
        category = this[NotificationTable.category],
        postId = this[NotificationTable.postId]?.value,
        commentId = this[NotificationTable.commentId]?.value,
        deepLink = this[NotificationTable.deepLink],
    )
}
