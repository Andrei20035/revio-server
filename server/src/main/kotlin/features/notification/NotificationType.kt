package com.revio.server.features.notification

/** What triggered a notification — drives which icon/copy the client renders. */
enum class NotificationType {
    POST_REMOVED,
    ACCOUNT_SUSPENDED,
    ACCOUNT_UNSUSPENDED,
    VIOLATION_REVOKED,
    CHALLENGE_REWARD_REVOKED,
}
