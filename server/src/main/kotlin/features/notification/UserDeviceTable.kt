package com.revio.server.features.notification

import com.revio.server.features.user.UserTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

/** Which Firebase project a device's token was issued by — debug and release use separate projects. */
enum class FirebaseProject {
    DEBUG,
    RELEASE,
}

/** Client platform for a registered device. Only ANDROID exists today. */
enum class DevicePlatform {
    ANDROID,
}

/**
 * Push notification device registry — one row per (user, device_id) pair. See
 * V34__user_devices.sql for the full rationale: registration is an upsert on
 * (user_id, device_id), and fcm_token is globally UNIQUE so a token
 * reappearing under a different user forces the stale row to give it up.
 */
object UserDeviceTable : UUIDTable("user_devices") {
    val userId = reference("user_id", UserTable, onDelete = ReferenceOption.CASCADE)
    val deviceId = varchar("device_id", 128)
    val fcmToken = varchar("fcm_token", 4096).uniqueIndex().nullable()
    val firebaseProject = enumerationByName("firebase_project", 16, FirebaseProject::class)
    val platform = enumerationByName("platform", 16, DevicePlatform::class)
    val appVersion = varchar("app_version", 32)
    val timezone = varchar("timezone", 64).nullable()
    val locale = varchar("locale", 16).nullable()
    val isActive = bool("is_active").default(true)
    val lastSeenAt = timestampWithTimeZone("last_seen_at").defaultExpression(CurrentTimestampWithTimeZone)
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestampWithTimeZone)

    init {
        uniqueIndex(userId, deviceId)
    }
}
