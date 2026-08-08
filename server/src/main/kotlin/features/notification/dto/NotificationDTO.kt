package com.revio.server.features.notification.dto

import com.revio.server.core.serialization.InstantSerializer
import com.revio.server.core.serialization.UUIDSerializer
import com.revio.server.features.notification.Notification
import com.revio.server.features.notification.NotificationType
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

@Serializable
data class NotificationDTO(
    @Serializable(with = UUIDSerializer::class)
    val id: UUID,
    val type: NotificationType,
    val title: String,
    val body: String,
    val blocking: Boolean,
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant,
    @Serializable(with = InstantSerializer::class)
    val readAt: Instant? = null,
)

fun Notification.toDTO() = NotificationDTO(
    id = id,
    type = type,
    title = title,
    body = body,
    blocking = blocking,
    createdAt = createdAt,
    readAt = readAt,
)

@Serializable
data class NotificationListResponseDTO(
    val unreadCount: Long,
    val items: List<NotificationDTO>,
)
