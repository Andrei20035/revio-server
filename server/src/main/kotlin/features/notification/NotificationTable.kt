package com.revio.server.features.notification

import com.revio.server.features.user.UserTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Pull-based in-app notifications (no push infrastructure exists yet — the client polls, same as
 * the activity feed). `blocking` marks a notification that must be shown as a dialog the user
 * cannot dismiss without acknowledging, on next app open — used for post-removal and ban notices.
 */
object NotificationTable : UUIDTable("user_notifications") {
    val userId = uuid("user_id").references(UserTable.id, onDelete = ReferenceOption.CASCADE)
    val type = enumerationByName("type", 32, NotificationType::class)
    val title = text("title")
    val body = text("body")
    val blocking = bool("blocking").default(false)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val readAt = timestamp("read_at").nullable()
}
