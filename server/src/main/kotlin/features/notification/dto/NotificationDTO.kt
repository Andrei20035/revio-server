package com.revio.server.features.notification.dto

import com.revio.server.core.serialization.InstantSerializer
import com.revio.server.core.serialization.UUIDSerializer
import com.revio.server.features.notification.Notification
import com.revio.server.features.notification.NotificationCategory
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
    @Serializable(with = InstantSerializer::class)
    val updatedAt: Instant,
    val category: NotificationCategory = NotificationCategory.ACCOUNT,
    /** Target spot for a LIKES/COMMENTS row (D3 deep link) — null for a non-social row, or a tombstone. */
    @Serializable(with = UUIDSerializer::class)
    val postId: UUID? = null,
    @Serializable(with = UUIDSerializer::class)
    val commentId: UUID? = null,
    val deepLink: String? = null,
)

fun Notification.toDTO() = NotificationDTO(
    id = id,
    type = type,
    title = title,
    body = body,
    blocking = blocking,
    createdAt = createdAt,
    readAt = readAt,
    updatedAt = updatedAt,
    category = category,
    postId = postId,
    commentId = commentId,
    deepLink = deepLink,
)

/**
 * Opaque cursor for keyset notification pagination, mirroring [com.revio.server.features.post.dto.FeedCursorDTO].
 * Points at the last notification of the current page; the next request returns notifications
 * strictly older than this (created_at, id) pair.
 */
@Serializable
data class NotificationCursorDTO(
    @Serializable(with = InstantSerializer::class)
    val lastCreatedAt: Instant,
    @Serializable(with = UUIDSerializer::class)
    val lastNotificationId: UUID,
)

@Serializable
data class NotificationListResponseDTO(
    val unreadCount: Long,
    val items: List<NotificationDTO>,
    val nextCursor: NotificationCursorDTO? = null,
    val hasMore: Boolean = false,
)
