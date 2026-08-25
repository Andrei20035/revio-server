package com.revio.server.features.notification.dto

import com.revio.server.core.serialization.LocalTimeSerializer
import com.revio.server.features.notification.UserNotificationPrefs
import kotlinx.serialization.Serializable
import java.time.LocalTime

@Serializable
data class NotificationPrefsDTO(
    val likesEnabled: Boolean,
    val commentsEnabled: Boolean,
    val discoveryEnabled: Boolean,
    val remindersEnabled: Boolean,
    @Serializable(with = LocalTimeSerializer::class)
    val quietStart: LocalTime,
    @Serializable(with = LocalTimeSerializer::class)
    val quietEnd: LocalTime,
)

fun UserNotificationPrefs.toDTO() = NotificationPrefsDTO(
    likesEnabled = likesEnabled,
    commentsEnabled = commentsEnabled,
    discoveryEnabled = discoveryEnabled,
    remindersEnabled = remindersEnabled,
    quietStart = quietStart,
    quietEnd = quietEnd,
)

/** All fields optional — a `PUT` only changes what it sends, per [UserNotificationPrefs] semantics. */
@Serializable
data class UpdateNotificationPrefsRequest(
    val likesEnabled: Boolean? = null,
    val commentsEnabled: Boolean? = null,
    val discoveryEnabled: Boolean? = null,
    val remindersEnabled: Boolean? = null,
    @Serializable(with = LocalTimeSerializer::class)
    val quietStart: LocalTime? = null,
    @Serializable(with = LocalTimeSerializer::class)
    val quietEnd: LocalTime? = null,
)
