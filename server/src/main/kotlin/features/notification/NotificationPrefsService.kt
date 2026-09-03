package com.revio.server.features.notification

import com.revio.server.features.notification.dto.UpdateNotificationPrefsRequest
import java.util.UUID

interface INotificationPrefsService {
    /** A user with no row reads as every category enabled with the default quiet hours. */
    suspend fun get(userId: UUID): UserNotificationPrefs

    /**
     * Applies a partial update. Quiet hours are always re-checked against the effective
     * (request value, or current/default if omitted) pair — [IllegalArgumentException] when
     * `quietStart >= quietEnd`, since the window is non-circular (00:00-08:00 by default, not
     * wrapping midnight; see D5 in the push-notifications plan).
     */
    suspend fun update(userId: UUID, request: UpdateNotificationPrefsRequest): UserNotificationPrefs
}

class NotificationPrefsService(
    private val prefsDao: IUserNotificationPrefsDAO,
) : INotificationPrefsService {

    override suspend fun get(userId: UUID): UserNotificationPrefs = prefsDao.get(userId)

    override suspend fun update(userId: UUID, request: UpdateNotificationPrefsRequest): UserNotificationPrefs {
        val current = prefsDao.get(userId)
        val effectiveStart = request.quietStart ?: current.quietStart
        val effectiveEnd = request.quietEnd ?: current.quietEnd
        require(effectiveStart < effectiveEnd) { "quietStart must be before quietEnd" }

        return prefsDao.update(
            userId = userId,
            likesEnabled = request.likesEnabled,
            commentsEnabled = request.commentsEnabled,
            discoveryEnabled = request.discoveryEnabled,
            remindersEnabled = request.remindersEnabled,
            challengesEnabled = request.challengesEnabled,
            quietStart = request.quietStart,
            quietEnd = request.quietEnd,
        )
    }
}
