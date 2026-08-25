package com.revio.server.features.notification

/**
 * What triggered a notification — drives which icon/copy the client renders. SOCIAL covers every
 * row created through [NotificationEventService], where [NotificationTable.category] (not this
 * column) carries the specific classification (likes/comments/discovery/reminders).
 */
enum class NotificationType {
    POST_REMOVED,
    ACCOUNT_SUSPENDED,
    ACCOUNT_UNSUSPENDED,
    VIOLATION_REVOKED,
    CHALLENGE_REWARD_REVOKED,
    SOCIAL,
}
