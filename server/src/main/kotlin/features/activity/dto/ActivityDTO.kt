package com.revio.server.features.activity.dto

import com.revio.server.core.serialization.InstantSerializer
import com.revio.server.core.serialization.UUIDSerializer
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

/**
 * Flat DTO for every activity item, discriminated by [type]. Fields not relevant to a given
 * [type] are null. Kept flat (rather than a polymorphic sealed hierarchy) to stay simple to
 * consume on Android without a custom class discriminator.
 *
 * [id] carries a type prefix (e.g. "like:<uuid>") so it stays unique across item types and is
 * stable for use as a LazyColumn key.
 */
@Serializable
data class ActivityItemDTO(
    val type: String,
    val id: String,
    @Serializable(with = UUIDSerializer::class)
    val actorUserId: UUID? = null,
    val actorUsername: String? = null,
    val actorAvatarUrl: String? = null,
    @Serializable(with = UUIDSerializer::class)
    val postId: UUID? = null,
    val postThumbnailUrl: String? = null,
    val brand: String? = null,
    val model: String? = null,
    val commentText: String? = null,
    val placesMoved: Int? = null,
    val streakDays: Int? = null,
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant,
    /**
     * Number of distinct actors folded into this LIKE/COMMENT item by [com.revio.server.features.activity.ActivityService]'s
     * per-post, per-aggregation-window grouping (same windows as the notification pipeline).
     * `1` for an un-aggregated item, and for every non-LIKE/COMMENT type. Additive field —
     * defaults to `1` so it doesn't change shape for older readers that ignore it.
     */
    val actorCount: Int = 1,
)

@Serializable
data class ActivityResponseDTO(
    val weeklySpotScore: Int,
    val todayInteractions: Int,
    val items: List<ActivityItemDTO>,
)
