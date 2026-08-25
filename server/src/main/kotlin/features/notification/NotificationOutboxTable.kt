package com.revio.server.features.notification

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

/** Lifecycle of a single (notification, device) send attempt. See V37__notification_outbox.sql. */
enum class OutboxState {
    PENDING,
    SENT,
    ACCEPTED,
    FAILED,
    DEAD,
    DROPPED,
}

/**
 * One row per (notification, device) fan-out target. See V37__notification_outbox.sql for the
 * full rationale behind the UNIQUE (notification_id, device_id) constraint and the drainer index.
 */
object NotificationOutboxTable : UUIDTable("notification_outbox") {
    val notificationId = reference("notification_id", NotificationTable, onDelete = ReferenceOption.CASCADE)
    val deviceId = reference("device_id", UserDeviceTable, onDelete = ReferenceOption.CASCADE)
    val state = enumerationByName("state", 16, OutboxState::class).default(OutboxState.PENDING)
    val attempts = integer("attempts").default(0)
    val nextAttemptAt = timestampWithTimeZone("next_attempt_at").defaultExpression(CurrentTimestampWithTimeZone)
    val notBefore = timestampWithTimeZone("not_before").nullable()
    val expiresAt = timestampWithTimeZone("expires_at").nullable()
    val fcmMessageId = varchar("fcm_message_id", 256).nullable()
    val lastErrorCode = varchar("last_error_code", 64).nullable()
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestampWithTimeZone)

    init {
        uniqueIndex(notificationId, deviceId)
    }
}
