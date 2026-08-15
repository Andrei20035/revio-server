package com.revio.server.features.post.dto

import com.revio.server.features.post.Post
import com.revio.server.core.serialization.InstantSerializer
import com.revio.server.core.serialization.UUIDSerializer
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

@Serializable
data class PostDTO(
    @Serializable(with = UUIDSerializer::class)
    val id: UUID,
    @Serializable(with = UUIDSerializer::class)
    val userId: UUID,
    val username: String,
    val authorProfilePictureUrl: String? = null,
    @Serializable(with = UUIDSerializer::class)
    val carModelId: UUID? = null,
    val brand: String,
    val model: String,
    val imageUrl: String,
    val caption: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val town: String? = null,
    val country: String? = null,
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant,
    val likeCount: Long = 0,
    val commentCount: Long = 0,
    val likedByCurrentUser: Boolean = false,
    val authorIsEarlySpotter: Boolean = false,
    val authorEarlySpotterNumber: Int? = null,
    /**
     * Whether this post's brand/model can no longer be changed because it has contributed to a
     * challenge (see PostService.updatePostAsAuthor / PostVehicleLockedException). Only computed
     * on GET /posts/{id} (PostService.findPostById) — [toDTO] and [toFeedDTO] leave it at the
     * default `false` so feed pagination doesn't pay for an extra query per post.
     */
    val vehicleLocked: Boolean = false,
)

fun Post.toDTO(
    imageUrl: String,
    authorProfilePictureUrl: String? = this.authorProfilePictureUrl,
) = PostDTO(
    id = this.id,
    userId = this.userId,
    username = this.username,
    authorProfilePictureUrl = authorProfilePictureUrl,
    carModelId = this.carModelId,
    brand = this.brand,
    model = this.model,
    imageUrl = imageUrl,
    caption = this.caption,
    latitude = this.latitude,
    longitude = this.longitude,
    town = this.town,
    country = this.country,
    createdAt = this.createdAt,
    authorIsEarlySpotter = this.authorIsEarlySpotter,
    authorEarlySpotterNumber = this.authorEarlySpotterNumber,
)

/** Feed variant that also carries engagement counters and the current user's like state. */
fun Post.toFeedDTO(
    imageUrl: String,
    authorProfilePictureUrl: String?,
    likeCount: Long,
    commentCount: Long,
    likedByCurrentUser: Boolean,
) = PostDTO(
    id = this.id,
    userId = this.userId,
    username = this.username,
    authorProfilePictureUrl = authorProfilePictureUrl,
    carModelId = this.carModelId,
    brand = this.brand,
    model = this.model,
    imageUrl = imageUrl,
    caption = this.caption,
    latitude = this.latitude,
    longitude = this.longitude,
    town = this.town,
    country = this.country,
    createdAt = this.createdAt,
    likeCount = likeCount,
    commentCount = commentCount,
    likedByCurrentUser = likedByCurrentUser,
    authorIsEarlySpotter = this.authorIsEarlySpotter,
    authorEarlySpotterNumber = this.authorEarlySpotterNumber,
)
