package com.revio.server.features.notification

import com.revio.server.features.moderation.ModerationReason
import com.revio.server.features.moderation.label
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
}
