package com.revio.server.features.notification

import com.revio.server.features.moderation.ModerationReason
import com.revio.server.features.moderation.label
import com.revio.server.features.notification.dto.NotificationCursorDTO
import com.revio.server.features.notification.dto.NotificationListResponseDTO
import com.revio.server.features.notification.dto.toDTO
import java.time.Instant
import java.util.UUID

interface INotificationService {
    suspend fun create(userId: UUID, type: NotificationType, title: String, body: String, blocking: Boolean = false): UUID

    /** [INotificationDAO.insertInCurrentTransaction] — see that doc for why this variant exists. */
    fun createInCurrentTransaction(userId: UUID, type: NotificationType, title: String, body: String, blocking: Boolean = false): UUID

    /**
     * Builds and creates the "post removed" notification body from the card's fixed template.
     * [reasonDetails] is only used (and required) when [reason] is [ModerationReason.OTHER].
     */
    fun buildPostRemovedNotificationBody(reason: ModerationReason, reasonDetails: String?): String

    /** Read-through to the DAO — used by the admin moderation-detail screen to show a user's recent notifications. */
    suspend fun listForUser(userId: UUID, limit: Int): List<Notification>

    /**
     * Builds the `GET /notifications` response: unread count plus a keyset-paginated page of
     * [limit] items (already validated/clamped by the caller). [cursorCreatedAt]/[cursorNotificationId]
     * must both be null (first page) or both be present (subsequent page) — a partial pair throws
     * [IllegalArgumentException], mirroring [com.revio.server.features.post.PostService]'s feed cursor.
     *
     * @param category when non-null, restricts both the page and the unread count to that
     *   category (e.g. ACCOUNT for the Notices inbox); null returns every category, unchanged
     *   from prior behavior.
     */
    suspend fun listForUserPage(
        userId: UUID,
        limit: Int,
        cursorCreatedAt: String?,
        cursorNotificationId: String?,
        category: NotificationCategory? = null,
    ): NotificationListResponseDTO

    /** [INotificationDAO.markRead] — see that doc; scoped to [userId] so one user can't mark another's read. */
    suspend fun markRead(notificationId: UUID, userId: UUID): Boolean

    /**
     * [INotificationDAO.markAllRead].
     *
     * @param category when non-null, restricts the update to that category, and — matching
     *   [INotificationDAO.markAllRead]'s `includeBlocking` doc — leaves `blocking = true` rows
     *   untouched, since a category-scoped read-all is the Notices inbox opening, not an explicit
     *   moderation-dialog acknowledgement. Null updates every category including blocking rows,
     *   unchanged from prior behavior.
     */
    suspend fun markAllRead(userId: UUID, category: NotificationCategory? = null): Int
}

class NotificationService(
    private val notificationDao: INotificationDAO,
) : INotificationService {

    override suspend fun create(
        userId: UUID,
        type: NotificationType,
        title: String,
        body: String,
        blocking: Boolean,
    ): UUID = notificationDao.insert(userId, type, title, body, blocking)

    override fun createInCurrentTransaction(
        userId: UUID,
        type: NotificationType,
        title: String,
        body: String,
        blocking: Boolean,
    ): UUID = notificationDao.insertInCurrentTransaction(userId, type, title, body, blocking)

    override fun buildPostRemovedNotificationBody(reason: ModerationReason, reasonDetails: String?): String {
        val label = if (reason == ModerationReason.OTHER) {
            reasonDetails?.takeIf { it.isNotBlank() } ?: reason.label
        } else {
            reason.label
        }
        return "Your post was removed. Reason: $label. Please keep future posts relevant to the " +
            "Revio car community. Accounts may be suspended after 3 violations."
    }

    override suspend fun listForUser(userId: UUID, limit: Int): List<Notification> =
        notificationDao.listForUser(userId, limit)

    override suspend fun listForUserPage(
        userId: UUID,
        limit: Int,
        cursorCreatedAt: String?,
        cursorNotificationId: String?,
        category: NotificationCategory?,
    ): NotificationListResponseDTO {
        val cursor = parseCursor(cursorCreatedAt, cursorNotificationId)

        // Fetch one extra row to determine whether another page exists, exactly as
        // PostService.listFeed does for the feed's own keyset cursor.
        val rows = notificationDao.listForUserAfter(userId, limit + 1, cursor?.first, cursor?.second, category)
        val hasMore = rows.size > limit
        val page = if (hasMore) rows.take(limit) else rows

        val nextCursor = if (hasMore) {
            page.last().let { NotificationCursorDTO(it.createdAt, it.id) }
        } else {
            null
        }

        val unreadCount = notificationDao.countUnread(userId, category)

        return NotificationListResponseDTO(
            unreadCount = unreadCount,
            items = page.map { it.toDTO() },
            nextCursor = nextCursor,
            hasMore = hasMore,
        )
    }

    override suspend fun markRead(notificationId: UUID, userId: UUID): Boolean =
        notificationDao.markRead(notificationId, userId)

    override suspend fun markAllRead(userId: UUID, category: NotificationCategory?): Int =
        notificationDao.markAllRead(userId, category, includeBlocking = category == null)

    private fun parseCursor(cursorCreatedAt: String?, cursorNotificationId: String?): Pair<Instant, UUID>? {
        if (cursorCreatedAt == null && cursorNotificationId == null) return null
        require(cursorCreatedAt != null && cursorNotificationId != null) {
            "Both cursorCreatedAt and cursorNotificationId must be provided together"
        }
        val createdAt = runCatching { Instant.parse(cursorCreatedAt) }
            .getOrElse { throw IllegalArgumentException("Invalid cursorCreatedAt") }
        val id = runCatching { UUID.fromString(cursorNotificationId) }
            .getOrElse { throw IllegalArgumentException("Invalid cursorNotificationId") }
        return createdAt to id
    }
}
