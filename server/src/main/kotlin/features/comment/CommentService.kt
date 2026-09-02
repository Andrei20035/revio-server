package features.comment

import com.revio.server.core.storage.IStorageService
import com.revio.server.features.comment.dto.CommentDTO
import com.revio.server.features.comment.dto.toDTO
import com.revio.server.features.notification.INotificationEventService
import com.revio.server.features.notification.INotificationOutboxDAO
import com.revio.server.features.notification.INotificationPolicyService
import com.revio.server.features.notification.IUserDeviceDAO
import com.revio.server.features.notification.IUserNotificationPrefsDAO
import com.revio.server.features.notification.NotificationCategory
import com.revio.server.features.notification.NotificationPolicyInput
import com.revio.server.features.notification.NotificationPolicyService
import com.revio.server.features.notification.NotificationVerdict
import com.revio.server.features.post.IPostDAO
import com.revio.server.features.scoring.IScoringService
import org.jetbrains.exposed.exceptions.ExposedSQLException
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

class PostNotFoundException(postId: UUID) : RuntimeException("Post $postId does not exist")
class CommentNotFoundException(commentId: UUID) : RuntimeException("Comment $commentId not found")
class CommentForbiddenException : RuntimeException("Not authorized to delete this comment")
class CommentValidationException(msg: String) : RuntimeException(msg)

interface ICommentService {
    suspend fun addComment(userId: UUID, postId: UUID, commentText: String): CommentDTO
    suspend fun deleteComment(commentId: UUID, requesterId: UUID)
    suspend fun getCommentsForPost(postId: UUID): List<CommentDTO>
}

class CommentService(
    private val commentDao: ICommentDAO,
    private val storageService: IStorageService,
    private val postDao: IPostDAO,
    private val scoringService: IScoringService,
    private val notificationEventService: INotificationEventService,
    private val notificationPrefsDao: IUserNotificationPrefsDAO,
    private val userDeviceDao: IUserDeviceDAO,
    private val notificationOutboxDao: INotificationOutboxDAO,
    private val commentsPushEnabledProvider: () -> String? = { System.getenv("ENABLE_COMMENTS_PUSH") },
    private val notificationPolicyService: INotificationPolicyService = NotificationPolicyService(),
) : ICommentService {

    companion object {
        const val MAX_COMMENT_LENGTH = 1000

        /** Rolling aggregation window for comment notifications (plan §18, step 4.2). */
        internal const val AGGREGATION_WINDOW_SECONDS = 15L * 60

        /**
         * Floors [instant] to its 15-minute calendar bucket — the notification aggregation
         * window's start. Two comments at least [AGGREGATION_WINDOW_SECONDS] apart can never
         * floor to the same bucket, which is exactly what "a comment 16 minutes later opens a
         * new window" (this step's acceptance criterion) requires. Internal (not private) so
         * this exact floor math can be unit-tested directly, same reasoning as
         * PushDispatchService.classifyResponse/NotificationOutboxProcessor.nextRetryDecision.
         */
        internal fun windowStartFor(instant: Instant): Instant {
            val epochSecond = instant.epochSecond
            return Instant.ofEpochSecond(epochSecond - (epochSecond % AGGREGATION_WINDOW_SECONDS))
        }
    }

    override suspend fun addComment(userId: UUID, postId: UUID, commentText: String): CommentDTO {
        val text = commentText.trim()
        if (text.isBlank()) throw CommentValidationException("Comment text cannot be blank")
        if (text.length > MAX_COMMENT_LENGTH) {
            throw CommentValidationException("Comment text exceeds $MAX_COMMENT_LENGTH characters")
        }

        // Check before inserting: is this user's first comment on this post?
        val isFirstComment = !commentDao.hasUserCommentedOnPost(userId, postId)
        val ownerInfo = postDao.getOwnerAndSource(postId)

        return try {
            val comment = commentDao.addComment(userId, postId, text).toResponse()

            // Award first-commenter points if this is the user's first comment ever on this post
            // and not a self-comment. Independent of the notification block below: scoring only
            // ever fires once per (user, post), but a notification event aggregates every comment.
            if (isFirstComment && ownerInfo != null && ownerInfo.ownerId != userId) {
                scoringService.onFirstCommentByUser(
                    postOwnerId = ownerInfo.ownerId,
                    postId = postId,
                    commenterId = userId,
                    source = ownerInfo.source,
                )
            }

            // Notification aggregation (plan §18, step 4.2): one event row per rolling 15-min
            // window on this post, self-comment excluded (double-checked the same way as
            // scoring). actor_count only grows when this is the *first* comment this user has
            // made within the current window — repeat comments from the same user in the same
            // window are silently absorbed into the row already recorded for their first one,
            // so "3 comments from the same user" is always exactly 1 actor.
            if (ownerInfo != null && ownerInfo.ownerId != userId) {
                val windowStart = windowStartFor(comment.createdAt)
                val alreadyCountedInWindow = commentDao.hasUserCommentedOnPostInWindow(
                    userId = userId,
                    postId = postId,
                    windowStart = windowStart,
                    excludingCommentId = comment.id,
                )
                if (!alreadyCountedInWindow) {
                    // recordComment renders title/body itself from the row's actual (race-free)
                    // actor count — see plan §8.2's thresholds / step 4.3. The comment's own text
                    // is never passed in, so it can never leak into the rendered copy (D7).
                    val notificationId = notificationEventService.recordComment(
                        recipientId = ownerInfo.ownerId,
                        dedupeKey = "comment:$postId:${windowStart.epochSecond}",
                        actorId = userId,
                        actorUsername = comment.username,
                        postId = postId,
                        commentId = comment.id,
                    )
                    enqueuePushIfEligible(recipientId = ownerInfo.ownerId, notificationId = notificationId)
                }
            }

            comment
        } catch (e: ExposedSQLException) {
            if (e.sqlState == "23503") throw PostNotFoundException(postId)
            throw e
        }
    }

    override suspend fun deleteComment(commentId: UUID, requesterId: UUID) {
        val comment = commentDao.getCommentById(commentId)
            ?: throw CommentNotFoundException(commentId)
        if (comment.userId != requesterId) throw CommentForbiddenException()

        val ownerInfo = postDao.getOwnerAndSource(comment.postId)
        commentDao.deleteComment(commentId)

        // Notification cancellation (plan §18, step 4.4): mirrors the same eligibility gate as
        // creation (self-comment excluded). Only withdraws the actor if this was their *last*
        // remaining comment in the aggregation window — if they still have another comment there,
        // their contribution is still represented and nothing changes.
        if (ownerInfo != null && ownerInfo.ownerId != comment.userId) {
            val windowStart = windowStartFor(comment.createdAt)
            val stillHasOtherCommentInWindow = commentDao.hasUserCommentedOnPostInWindow(
                userId = comment.userId,
                postId = comment.postId,
                windowStart = windowStart,
                excludingCommentId = commentId,
            )
            if (!stillHasOtherCommentInWindow) {
                notificationEventService.withdrawCommentActor(
                    recipientId = ownerInfo.ownerId,
                    dedupeKey = "comment:${comment.postId}:${windowStart.epochSecond}",
                )
            }
        }
    }

    override suspend fun getCommentsForPost(postId: UUID): List<CommentDTO> =
        commentDao.getCommentsForPost(postId).map { it.toResponse() }

    /**
     * Fans a just-recorded COMMENTS notification out to the outbox, for internal rollout only
     * (plan §18, step 4.5): gated first by [commentsPushEnabledProvider] (`ENABLE_COMMENTS_PUSH`,
     * off by default — this is the "behind flag, rollout intern întâi" switch, independent of any
     * per-user preference), then by the recipient's own `comments` notification preference and
     * [INotificationPolicyService] (quiet hours plus hourly/daily caps). In any suppress case, the
     * notification row itself is untouched — it's already been recorded above and stays in the
     * inbox; only the push attempt is skipped. One outbox row is enqueued per active device;
     * enqueue is idempotent on (notification_id, device_id), so calling this again for the same
     * window/device (e.g. a second distinct actor joining before the first send drains) is
     * harmless.
     */
    private suspend fun enqueuePushIfEligible(recipientId: UUID, notificationId: UUID) {
        if (commentsPushEnabledProvider() != "true") return
        val prefs = notificationPrefsDao.get(recipientId)
        if (!prefs.commentsEnabled) return

        val devices = userDeviceDao.findActiveByUser(recipientId)
        if (devices.isEmpty()) return

        val now = Instant.now().atOffset(ZoneOffset.UTC)
        val zone = devices.firstNotNullOfOrNull { device ->
            device.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        }
        val dayStart = zone
            ?.let { now.atZoneSameInstant(it).toLocalDate().atStartOfDay(it).toOffsetDateTime() }
            ?: now.toLocalDate().atStartOfDay().atOffset(ZoneOffset.UTC)
        val hourStart = now.minusHours(1)
        val comments = setOf(NotificationCategory.COMMENTS)
        val nonAccountCategories = NotificationCategory.entries
            .filterTo(mutableSetOf()) { it != NotificationCategory.ACCOUNT }

        val decision = notificationPolicyService.evaluate(
            NotificationPolicyInput(
                category = NotificationCategory.COMMENTS,
                categoryEnabled = prefs.commentsEnabled,
                now = now,
                zone = zone,
                quietStart = prefs.quietStart,
                quietEnd = prefs.quietEnd,
                hourlyCount = notificationOutboxDao.countAcceptedNotificationsSince(recipientId, comments, hourStart),
                dailyCount = notificationOutboxDao.countAcceptedNotificationsSince(recipientId, comments, dayStart),
                dailyTotalCount = notificationOutboxDao.countAcceptedNotificationsSince(recipientId, nonAccountCategories, dayStart),
            ),
        )
        if (decision.verdict == NotificationVerdict.SUPPRESS) return

        devices.forEach { device ->
            notificationOutboxDao.enqueue(notificationId, device.id, notBefore = decision.notBefore)
        }
    }

    private fun Comment.toResponse(): CommentDTO = toDTO(
        profilePictureUrl = profilePicturePath?.let(storageService::resolveUrl),
    )
}
