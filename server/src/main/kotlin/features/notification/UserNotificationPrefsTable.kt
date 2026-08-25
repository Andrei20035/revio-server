package com.revio.server.features.notification

import com.revio.server.features.user.UserTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.sql.javatime.time
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import java.time.LocalTime

/**
 * Push/inbox notification preferences, one row per user, created lazily on first write. A missing
 * row means every category is enabled with the default quiet hours — see V35__user_notification_prefs.sql
 * for the opt-out rationale. [UserNotificationPrefsDefaults] mirrors these column defaults for
 * callers that need them without a row (e.g. [UserNotificationPrefsDAO.get]).
 */
object UserNotificationPrefsTable : Table("user_notification_prefs") {
    val userId = uuid("user_id").references(UserTable.id, onDelete = ReferenceOption.CASCADE)
    val likesEnabled = bool("likes_enabled").default(true)
    val commentsEnabled = bool("comments_enabled").default(true)
    val discoveryEnabled = bool("discovery_enabled").default(true)
    val remindersEnabled = bool("reminders_enabled").default(true)
    val quietStart = time("quiet_start").default(LocalTime.of(0, 0))
    val quietEnd = time("quiet_end").default(LocalTime.of(8, 0))
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestampWithTimeZone)

    override val primaryKey = PrimaryKey(userId)
}

object UserNotificationPrefsDefaults {
    const val LIKES_ENABLED = true
    const val COMMENTS_ENABLED = true
    const val DISCOVERY_ENABLED = true
    const val REMINDERS_ENABLED = true
    val QUIET_START: LocalTime = LocalTime.of(0, 0)
    val QUIET_END: LocalTime = LocalTime.of(8, 0)
}
