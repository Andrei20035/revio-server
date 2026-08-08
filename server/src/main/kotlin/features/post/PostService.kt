package com.revio.server.features.post

import com.revio.server.core.storage.IStorageService
import com.revio.server.features.car_model.ICarModelDAO
import com.revio.server.features.challenge.IChallengeProgressService
import com.revio.server.features.moderation.IOrphanedStorageObjectDAO
import com.revio.server.features.moderation.ModerationReason
import com.revio.server.features.moderation.OrphanedStorageObjectDAO
import com.revio.server.features.post.dto.CreatePostDTO
import com.revio.server.features.post.dto.FeedCursorDTO
import com.revio.server.features.post.dto.FeedResponseDTO
import com.revio.server.features.post.dto.PersistPostDTO
import com.revio.server.features.post.dto.PostDTO
import com.revio.server.features.post.dto.UpdatePostRequest
import com.revio.server.features.post.dto.toDTO
import com.revio.server.features.post.dto.toFeedDTO
import com.revio.server.features.scoring.IScoringDao
import com.revio.server.features.scoring.IScoringService
import features.comment.ICommentDAO
import features.like.ILikeDAO
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

interface IPostService {
    suspend fun createPost(request: CreatePostDTO): UUID
    suspend fun findPostById(postId: UUID, currentUserId: UUID?): PostDTO?
    suspend fun listFeed(
        limit: Int,
        cursorCreatedAt: String?,
        cursorPostId: String?,
        currentUserId: UUID?,
    ): FeedResponseDTO
    suspend fun listPostsByUser(userId: UUID, limit: Int, cursorCreatedAt: String?, cursorPostId: String?, currentUserId: UUID?): FeedResponseDTO
    suspend fun deletePostAsAuthor(postId: UUID, authorId: UUID)

    /** Moderator takedown (e.g. an upheld report). Same removal mechanics as [deletePostAsAuthor], no ownership check. */
    suspend fun removePostAsModerator(postId: UUID, moderatorId: UUID)

    /**
     * Admin-initiated removal with an explicit, card-selected reason: records a moderation
     * violation and an audit log entry atomically with the removal, and — unless [notifyUser] is
     * false — a "post removed" notification. Bulk removal passes `notifyUser = false` for every
     * post and sends one aggregated notification per affected owner afterwards instead.
     * @return the created violation id and the post's owner id (so bulk removal can aggregate).
     */
    suspend fun removePostAsModerator(
        postId: UUID,
        moderatorId: UUID,
        reason: ModerationReason,
        reasonDetails: String?,
        notifyUser: Boolean = true,
    ): ModeratedRemovalResult

    suspend fun updatePostAsAuthor(postId: UUID, authorId: UUID, request: UpdatePostRequest): PostDTO
}

data class ModeratedRemovalResult(val violationId: UUID, val ownerId: UUID)

/** Who triggered a post removal — carried only for the structured log line at the end of [PostServiceImpl.removePost]. */
sealed class PostRemovalActor {
    data class Author(val userId: UUID) : PostRemovalActor()
    data class Moderator(val moderatorId: UUID) : PostRemovalActor()
}

class PostServiceImpl(
    private val postDao: IPostDAO,
    private val storageService: IStorageService,
    private val carModelDao: ICarModelDAO,
    private val likeDao: ILikeDAO,
    private val commentDao: ICommentDAO,
    private val scoringService: IScoringService,
    private val scoringDao: IScoringDao,
    private val challengeProgressService: IChallengeProgressService,
    private val postRemovalDao: IPostRemovalDAO,
    // Defaulted for the same reason as PostRemovalDAO's trailing moderation deps: pre-existing
    // callers that construct PostServiceImpl with the original 9 args keep compiling unchanged.
    private val orphanedStorageDao: IOrphanedStorageObjectDAO = OrphanedStorageObjectDAO(),
) : IPostService {
    companion object {
        private val logger = LoggerFactory.getLogger(PostServiceImpl::class.java)
        private val allowedContentTypes = setOf("image/jpeg", "image/png", "image/webp")
        private const val maxCaptionLength = 1_000
        private const val defaultLimit = 20
        private const val maxLimit = 100
        private val extensions = mapOf(
            "image/jpeg" to "jpg",
            "image/png" to "png",
            "image/webp" to "webp",
        )
    }

    override suspend fun createPost(request: CreatePostDTO): UUID {
        validateCreateRequest(request)

        val ext = extensions.getValue(request.contentType)
        val today = LocalDate.now()
        val objectKey = "posts/%04d/%02d/%02d/%s.%s".format(
            today.year,
            today.monthValue,
            today.dayOfMonth,
            UUID.randomUUID(),
            ext,
        )

        storageService.uploadImage(
            bytes = request.imageBytes,
            objectKey = objectKey,
            contentType = request.contentType,
        )

        return try {
            val inserted = postDao.insert(
                PersistPostDTO(
                    userId = request.authorId,
                    carModelId = request.carModelId,
                    customBrand = request.customBrand?.trim(),
                    customModel = request.customModel?.trim(),
                    imageObjectKey = objectKey,
                    latitude = request.latitude,
                    longitude = request.longitude,
                    town = request.town?.trim()?.ifEmpty { null },
                    country = request.country?.trim()?.ifEmpty { null },
                    caption = request.caption?.trim(),
                    source = request.source,
                    createdAtTimezone = request.createdAtTimezone,
                )
            )
            // Award SpotScore and advance streak for camera posts.
            scoringService.onPostCreated(
                userId = request.authorId,
                postId = inserted.id,
                source = request.source,
                createdAtUtc = Instant.now(),
                createdAtTimezone = request.createdAtTimezone,
            )

            // Challenge contribution: evaluated against inserted.createdAt (the value Postgres
            // actually persisted), not a fresh Instant.now(). Best-effort — a failure here must
            // never fail post creation or its normal points (plan §4.3); a missed evaluation is
            // recoverable later via the admin reconcile endpoint (Etapa 5/7, plan §5).
            if (request.source == PostSource.CAMERA && request.carModelId != null) {
                runCatching {
                    challengeProgressService.evaluatePostForActiveChallenge(
                        userId = request.authorId,
                        postId = inserted.id,
                        carModelId = request.carModelId,
                        postCreatedAt = inserted.createdAt,
                    )
                }.onFailure {
                    logger.warn("Challenge evaluation failed for post {}", inserted.id, it)
                }
            }

            inserted.id
        } catch (e: Exception) {
            runCatching { storageService.deleteImage(objectKey) }
            throw PostCreationException("Failed to create post for user ${request.authorId}", e)
        }
    }

    override suspend fun findPostById(postId: UUID, currentUserId: UUID?): PostDTO? =
        postDao.findById(postId)?.let { enrichPosts(listOf(it), currentUserId).first() }

    override suspend fun listFeed(
        limit: Int,
        cursorCreatedAt: String?,
        cursorPostId: String?,
        currentUserId: UUID?,
    ): FeedResponseDTO {
        val effectiveLimit = validateLimit(limit)
        val cursor = parseCursor(cursorCreatedAt, cursorPostId)

        // Fetch one extra row to determine whether another page exists.
        // An authenticated viewer never sees their own posts in the feed.
        val rows = postDao.listFeed(effectiveLimit + 1, cursor?.first, cursor?.second, excludeUserId = currentUserId)
        val hasMore = rows.size > effectiveLimit
        val page = if (hasMore) rows.take(effectiveLimit) else rows

        val posts = enrichPosts(page, currentUserId)

        val nextCursor = if (hasMore) {
            page.last().let { FeedCursorDTO(it.createdAt, it.id) }
        } else {
            null
        }

        return FeedResponseDTO(posts = posts, nextCursor = nextCursor, hasMore = hasMore)
    }

    /**
     * Parses the (created_at, id) cursor. Both parts must be present together or both omitted
     * (first page). Malformed values are rejected with [IllegalArgumentException].
     */
    private fun parseCursor(cursorCreatedAt: String?, cursorPostId: String?): Pair<Instant, UUID>? {
        if (cursorCreatedAt == null && cursorPostId == null) return null
        require(cursorCreatedAt != null && cursorPostId != null) {
            "Both cursorCreatedAt and cursorPostId must be provided together"
        }
        val createdAt = runCatching { Instant.parse(cursorCreatedAt) }
            .getOrElse { throw IllegalArgumentException("Invalid cursorCreatedAt") }
        val postId = runCatching { UUID.fromString(cursorPostId) }
            .getOrElse { throw IllegalArgumentException("Invalid cursorPostId") }
        return createdAt to postId
    }

    override suspend fun listPostsByUser(userId: UUID, limit: Int, cursorCreatedAt: String?, cursorPostId: String?, currentUserId: UUID?): FeedResponseDTO {
        val effectiveLimit = validateLimit(limit)
        val cursor = parseCursor(cursorCreatedAt, cursorPostId)

        val rows = postDao.listByUser(userId, effectiveLimit + 1, cursor?.first, cursor?.second)
        val hasMore = rows.size > effectiveLimit
        val page = if (hasMore) rows.take(effectiveLimit) else rows

        val posts = enrichPosts(page, currentUserId)

        val nextCursor = if (hasMore) {
            page.last().let { FeedCursorDTO(it.createdAt, it.id) }
        } else {
            null
        }

        return FeedResponseDTO(posts = posts, nextCursor = nextCursor, hasMore = hasMore)
    }

    override suspend fun deletePostAsAuthor(postId: UUID, authorId: UUID) {
        val post = postDao.findById(postId) ?: throw PostNotFoundException(postId)
        if (post.userId != authorId) {
            throw PostForbiddenException(postId, authorId)
        }
        removePost(post, PostRemovalActor.Author(authorId))
    }

    override suspend fun removePostAsModerator(postId: UUID, moderatorId: UUID) {
        val post = postDao.findById(postId) ?: throw PostNotFoundException(postId)
        removePost(post, PostRemovalActor.Moderator(moderatorId))
    }

    override suspend fun removePostAsModerator(
        postId: UUID,
        moderatorId: UUID,
        reason: ModerationReason,
        reasonDetails: String?,
        notifyUser: Boolean,
    ): ModeratedRemovalResult {
        val post = postDao.findById(postId) ?: throw PostNotFoundException(postId)
        val moderation = ModerationRemoval(
            adminId = moderatorId,
            reason = reason,
            reasonDetails = reasonDetails,
            notifyUser = notifyUser,
        )
        val violationId = removePost(post, PostRemovalActor.Moderator(moderatorId), moderation)
            ?: error("Moderated removal of post $postId did not produce a violation id")
        return ModeratedRemovalResult(violationId = violationId, ownerId = post.userId)
    }

    /**
     * Shared removal mechanics for every public entry point above — only the ownership/role check
     * and [moderation] context differ between them, both decided by the caller before this is
     * reached. @return the violation id created for a moderated removal, or null for a plain one.
     */
    private suspend fun removePost(post: Post, actor: PostRemovalActor, moderation: ModerationRemoval? = null): UUID? {
        // Challenge reconciliation, points reversal, the delete itself, and (for a moderated
        // removal) the violation/audit/notification rows all happen in ONE transaction (see
        // IPostRemovalDAO.removePostAtomically). Deliberately NOT best-effort: if reconciliation
        // fails, the exception propagates, the transaction rolls back and the post is still there
        // to retry. Swallowing it would let the delete commit alone, and
        // challenge_contributions.post_id being ON DELETE CASCADE would then erase the
        // contributions without revoking the reward they earned — an inflated spot_score that
        // nothing recomputes and no reconciliation job can detect. Cascade also removes the
        // post's likes, comments and reports.
        val outcome = postRemovalDao.removePostAtomically(post.id, challengeProgressService.contributionRevokePolicy(), moderation)

        // Only once the transaction has committed is the image safe to drop. This one stays
        // best-effort: an orphaned object costs storage, it can't corrupt scores. A failure is
        // queued for retry rather than silently dropped, so a moderated removal is never undone
        // just because a file couldn't be deleted.
        runCatching { storageService.deleteImage(post.imageKey) }
            .onFailure { e ->
                logger.warn("Post {} removed but image cleanup failed", post.id, e)
                runCatching { orphanedStorageDao.recordFailure(post.imageKey, e.message) }
                    .onFailure { logger.error("Failed to queue orphaned storage object for post {}", post.id, it) }
            }

        logger.info("Post {} removed by {}", post.id, actor)
        return outcome.violationId
    }

    override suspend fun updatePostAsAuthor(postId: UUID, authorId: UUID, request: UpdatePostRequest): PostDTO {
        val post = postDao.findById(postId) ?: throw PostNotFoundException(postId)
        if (post.userId != authorId) {
            throw PostForbiddenException(postId, authorId)
        }

        validateUpdateRequest(request)

        val caption = request.caption?.trim()
        postDao.updateById(
            postId = postId,
            carModelId = request.carModelId,
            customBrand = request.customBrand?.trim(),
            customModel = request.customModel?.trim(),
            caption = caption,
        )

        val updated = postDao.findById(postId) ?: throw PostNotFoundException(postId)
        return toResponse(updated)
    }

    private suspend fun validateUpdateRequest(request: UpdatePostRequest) {
        val caption = request.caption?.trim()
        require(caption == null || caption.isNotEmpty()) { "Caption cannot be blank" }
        require(caption == null || caption.length <= maxCaptionLength) {
            "Caption must be at most $maxCaptionLength characters"
        }

        val hasCarModelId = request.carModelId != null
        val brand = request.customBrand?.trim()
        val model = request.customModel?.trim()
        val hasCustomSource = !brand.isNullOrEmpty() || !model.isNullOrEmpty()

        require(hasCarModelId.xor(hasCustomSource)) {
            "Provide either carModelId or customBrand + customModel"
        }

        if (hasCarModelId) {
            require(brand == null && model == null) {
                "customBrand/customModel cannot be sent together with carModelId"
            }
            require(carModelDao.exists(request.carModelId!!)) { "carModelId does not exist" }
        } else {
            require(!brand.isNullOrEmpty() && !model.isNullOrEmpty()) {
                "customBrand and customModel are required when carModelId is missing"
            }
        }
    }

    private suspend fun validateCreateRequest(request: CreatePostDTO) {
        require(request.imageBytes.isNotEmpty()) { "Image is required" }
        require(request.contentType in allowedContentTypes) { "Unsupported image content type" }

        val caption = request.caption?.trim()
        require(caption == null || caption.isNotEmpty()) { "Caption cannot be blank" }
        require(caption == null || caption.length <= maxCaptionLength) {
            "Caption must be at most $maxCaptionLength characters"
        }

        val hasCarModelId = request.carModelId != null
        val brand = request.customBrand?.trim()
        val model = request.customModel?.trim()
        val hasCustomSource = !brand.isNullOrEmpty() || !model.isNullOrEmpty()

        require(hasCarModelId.xor(hasCustomSource)) {
            "Provide either carModelId or customBrand + customModel"
        }

        if (hasCarModelId) {
            require(brand == null && model == null) {
                "customBrand/customModel cannot be sent together with carModelId"
            }
            require(carModelDao.exists(request.carModelId!!)) { "carModelId does not exist" }
        } else {
            require(!brand.isNullOrEmpty() && !model.isNullOrEmpty()) {
                "customBrand and customModel are required when carModelId is missing"
            }
        }

        validateCoordinates(request.latitude, request.longitude)
    }

    private fun validateCoordinates(latitude: Double?, longitude: Double?) {
        require((latitude == null) == (longitude == null)) {
            "latitude and longitude must both be provided or both be omitted"
        }
        if (latitude != null && longitude != null) {
            require(latitude in -90.0..90.0) { "latitude must be between -90 and 90" }
            require(longitude in -180.0..180.0) { "longitude must be between -180 and 180" }
        }
    }

    private fun validateLimit(limit: Int): Int {
        if (limit == 0) {
            return defaultLimit
        }
        require(limit in 1..maxLimit) { "limit must be between 1 and $maxLimit" }
        return limit
    }

    private fun toResponse(post: Post): PostDTO = post.toDTO(
        imageUrl = storageService.resolveUrl(post.imageKey),
        authorProfilePictureUrl = post.authorProfilePictureUrl?.let(storageService::resolveUrl),
    )

    private suspend fun enrichPosts(page: List<Post>, currentUserId: UUID?): List<PostDTO> {
        val postIds = page.map { it.id }
        val likeCounts = likeDao.getLikeCountsForPosts(postIds)
        val commentCounts = commentDao.getCommentCountsForPosts(postIds)
        val likedPostIds = if (currentUserId != null) {
            likeDao.getLikedPostIds(currentUserId, postIds)
        } else {
            emptySet()
        }
        return page.map { post ->
            post.toFeedDTO(
                imageUrl = storageService.resolveUrl(post.imageKey),
                authorProfilePictureUrl = post.authorProfilePictureUrl?.let(storageService::resolveUrl),
                likeCount = likeCounts[post.id] ?: 0L,
                commentCount = commentCounts[post.id] ?: 0L,
                likedByCurrentUser = post.id in likedPostIds,
            )
        }
    }
}

class PostCreationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class PostNotFoundException(val postId: UUID) : RuntimeException("Post $postId not found")

class PostForbiddenException(postId: UUID, userId: UUID) :
    RuntimeException("User $userId cannot delete post $postId")
