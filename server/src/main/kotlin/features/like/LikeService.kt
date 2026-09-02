package features.like

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
import com.revio.server.features.user.IUserDAO
import org.jetbrains.exposed.exceptions.ExposedSQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

class LikePostNotFoundException(postId: UUID) : RuntimeException("Post $postId not found")

/** Debounce before a like notification's first scheduled send — plan §8.1 / §18 step 5.1. */
private const val LIKE_NOTIFICATION_DEBOUNCE_SECONDS = 60L

/** Freshness TTL for a like notification (plan §8.1: "6h") — a digest older than this is stale and abandoned rather than delivered late (step 4.3). */
private const val LIKE_NOTIFICATION_FRESHNESS_HOURS = 6L

interface ILikeService {
    suspend fun toggleLike(userId: UUID, postId: UUID): LikeStatusDTO
    suspend fun getLikeStatus(postId: UUID, userId: UUID?): LikeStatusDTO
}

class LikeService(
    private val likeDao: ILikeDAO,
    private val postDao: IPostDAO,
    private val scoringService: IScoringService,
    private val notificationEventService: INotificationEventService,
    private val userDeviceDao: IUserDeviceDAO,
    private val notificationOutboxDao: INotificationOutboxDAO,
    private val likeNotificationCursorDao: ILikeNotificationCursorDAO,
    private val userDao: IUserDAO,
    private val notificationPrefsDao: IUserNotificationPrefsDAO,
    private val likesPushEnabledProvider: () -> String? = { System.getenv("ENABLE_LIKES_PUSH") },
    private val notificationPolicyService: INotificationPolicyService = NotificationPolicyService(),
) : ILikeService {

    companion object {
        /**
         * Rolling aggregation window for like notifications (plan §8.1: "fereastră rulantă de
         * 60 min per post"), implemented the same way as CommentService's 15-minute window —
         * as a calendar-floor bucket rather than a persisted "first event" timestamp, so the
         * boundary is a pure function of the current instant and directly unit-testable.
         */
        internal const val AGGREGATION_WINDOW_SECONDS = 60L * 60

        /** Floors [instant] to its 60-minute bucket — see [AGGREGATION_WINDOW_SECONDS]. */
        internal fun windowStartFor(instant: Instant): Instant {
            val epochSecond = instant.epochSecond
            return Instant.ofEpochSecond(epochSecond - (epochSecond % AGGREGATION_WINDOW_SECONDS))
        }
    }

    override suspend fun toggleLike(userId: UUID, postId: UUID): LikeStatusDTO {
        val alreadyLiked = likeDao.hasUserLikedPost(userId, postId)
        val ownerInfo = postDao.getOwnerAndSource(postId) ?: throw LikePostNotFoundException(postId)

        if (alreadyLiked) {
            likeDao.unlikePost(userId, postId)
            scoringService.onPostUnliked(
                postOwnerId = ownerInfo.ownerId,
                postId = postId,
                unlikerId = userId,
                source = ownerInfo.source,
            )

            if (ownerInfo.ownerId != userId) {
                handleUnlikeNotification(recipientId = ownerInfo.ownerId, postId = postId, likerId = userId)
            }
        } else {
            try {
                likeDao.likePost(userId, postId)
            } catch (e: ExposedSQLException) {
                // sqlState 23503 = FK violation → postId doesn't exist
                if (e.sqlState == "23503") throw LikePostNotFoundException(postId)
                throw e
            }
            scoringService.onPostLiked(
                postOwnerId = ownerInfo.ownerId,
                postId = postId,
                likerId = userId,
                source = ownerInfo.source,
            )

            if (ownerInfo.ownerId != userId) {
                handleLikeNotification(recipientId = ownerInfo.ownerId, postId = postId, likerId = userId)
            }
        }

        val count = likeDao.getLikeCount(postId)
        val liked = !alreadyLiked
        return LikeStatusDTO(liked = liked, count = count)
    }

    override suspend fun getLikeStatus(postId: UUID, userId: UUID?): LikeStatusDTO {
        val count = likeDao.getLikeCount(postId)
        val liked = userId?.let { likeDao.hasUserLikedPost(it, postId) } ?: false
        return LikeStatusDTO(liked = liked, count = count)
    }

    /**
     * Notification aggregation for a like from someone other than the post owner (plan §18,
     * step 5.2). Idempotency is on the pair (post, liker), not on the notification event: a
     * liker who has already been permanently committed (their earlier contribution's window
     * rolled over — see [ILikeNotificationCursorDAO]) is a silent no-op here, and never
     * produces a second push, no matter how many times they unlike and re-like (plan §8.1).
     *
     * A liker who has never contributed, or whose prior contribution's window has since rolled
     * over (committed lazily here, since nothing else observes this post between events), is
     * treated as a fresh contribution to the *current* window and aggregated into that window's
     * `user_notifications` row exactly like the naive step 5.1 hook did — just keyed by
     * `like:{postId}:{windowStart}` instead of the old permanent `like:{postId}`, so a later
     * window opens its own row instead of aggregating into one that may already have gone out.
     */
    private suspend fun handleLikeNotification(recipientId: UUID, postId: UUID, likerId: UUID) {
        val now = Instant.now()
        val currentWindow = windowStartFor(now)

        val existing = likeNotificationCursorDao.find(postId, likerId)
        if (existing != null) {
            if (existing.committed) return // already announced — never a second push (plan §8.1)
            if (existing.windowStartedAt == currentWindow) return // already contributing to the open window

            // Their earlier contribution's window has rolled over without anyone closing it out
            // (no other like/unlike on this post observed the rollover first) — finalize it now,
            // lazily, before treating this like as the fresh contribution it is.
            likeNotificationCursorDao.markCommitted(postId, likerId)
        }

        likeNotificationCursorDao.insert(postId, likerId, currentWindow)

        // actorUsername renders the copy's threshold-appropriate title/body inside recordLike
        // itself (plan §8.1 / §18 step 5.3) — null falls back to "Someone" there.
        val actorUsername = userDao.getUserById(likerId)?.username
        val dedupeKey = "like:$postId:${currentWindow.epochSecond}"
        val notificationId = notificationEventService.recordLike(
            recipientId = recipientId,
            dedupeKey = dedupeKey,
            actorId = likerId,
            actorUsername = actorUsername,
            postId = postId,
        )

        // Debounced, not immediate: scheduled LIKE_NOTIFICATION_DEBOUNCE_SECONDS out so a burst
        // of likes on the same post gets a chance to aggregate into this same event before
        // anything is actually sent. enqueue is idempotent on (notification_id, device_id), so
        // later likes joining the same window just no-op here.
        val notBefore = now.plusSeconds(LIKE_NOTIFICATION_DEBOUNCE_SECONDS).atOffset(ZoneOffset.UTC)
        enqueuePushIfEligible(recipientId, notificationId, notBefore)
    }

    /**
     * Fans a just-recorded LIKES notification out to the outbox, for internal rollout only (plan
     * §18, step 5.7): gated first by [likesPushEnabledProvider] (`ENABLE_LIKES_PUSH`, off by
     * default — the "behind flag" switch, independent of any per-user preference), then by the
     * recipient's own `likes` notification preference and [INotificationPolicyService] (quiet
     * hours plus hourly/daily caps). In any suppress case, the notification row itself is
     * untouched — it's already been recorded above and stays in the inbox; only the push attempt
     * is skipped. Mirrors [features.comment.CommentService]'s `enqueuePushIfEligible` (step 4.5).
     * The effective schedule is the later of [notBefore]'s 60s debounce and a quiet-hours defer.
     * Each row also carries an [LIKE_NOTIFICATION_FRESHNESS_HOURS]-hour `expiresAt` (step 4.3) —
     * the processor drops rather than sends a like digest that's gone stale by dispatch time.
     * One outbox row is enqueued per active device; enqueue is idempotent on
     * (notification_id, device_id), so calling this again for the same window/device is harmless.
     */
    private suspend fun enqueuePushIfEligible(recipientId: UUID, notificationId: UUID, notBefore: OffsetDateTime) {
        if (likesPushEnabledProvider() != "true") return
        val prefs = notificationPrefsDao.get(recipientId)
        if (!prefs.likesEnabled) return

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
        val likes = setOf(NotificationCategory.LIKES)
        val comments = setOf(NotificationCategory.COMMENTS)
        val nonAccountCategories = NotificationCategory.entries
            .filterTo(mutableSetOf()) { it != NotificationCategory.ACCOUNT }

        val decision = notificationPolicyService.evaluate(
            NotificationPolicyInput(
                category = NotificationCategory.LIKES,
                categoryEnabled = prefs.likesEnabled,
                now = now,
                zone = zone,
                quietStart = prefs.quietStart,
                quietEnd = prefs.quietEnd,
                hourlyCount = notificationOutboxDao.countAcceptedNotificationsSince(recipientId, likes, hourStart),
                dailyCount = notificationOutboxDao.countAcceptedNotificationsSince(recipientId, likes, dayStart),
                otherSocialHourlyCount = notificationOutboxDao.countAcceptedNotificationsSince(recipientId, comments, hourStart),
                otherSocialDailyCount = notificationOutboxDao.countAcceptedNotificationsSince(recipientId, comments, dayStart),
                dailyTotalCount = notificationOutboxDao.countAcceptedNotificationsSince(recipientId, nonAccountCategories, dayStart),
            ),
        )
        if (decision.verdict == NotificationVerdict.SUPPRESS) return

        val effectiveNotBefore = decision.notBefore
            ?.takeIf { it.isAfter(notBefore) }
            ?: notBefore
        val expiresAt = now.plusHours(LIKE_NOTIFICATION_FRESHNESS_HOURS)
        devices.forEach { device ->
            notificationOutboxDao.enqueue(notificationId, device.id, notBefore = effectiveNotBefore, expiresAt = expiresAt)
        }
    }

    /**
     * Notification withdrawal for an unlike from someone other than the post owner (plan §18,
     * step 5.2). Only ever touches the aggregation when the liker's contribution is still
     * reversible: no participation row at all (they were never counted — e.g. a race with the
     * debounce window opening), or one already permanently committed, are both no-ops — the
     * latter is exactly "unlike după trimitere -> nu retragem push-ul" (plan §8.1): the row of
     * `user_notifications` (and the inbox count it drives) is left as-is.
     */
    private suspend fun handleUnlikeNotification(recipientId: UUID, postId: UUID, likerId: UUID) {
        val existing = likeNotificationCursorDao.find(postId, likerId) ?: return
        if (existing.committed) return

        val currentWindow = windowStartFor(Instant.now())
        if (existing.windowStartedAt != currentWindow) {
            // The window they contributed to has already rolled over — treat it as committed
            // (same lazy finalization as the like path) rather than retracting anything.
            likeNotificationCursorDao.markCommitted(postId, likerId)
            return
        }

        val dedupeKey = "like:$postId:${existing.windowStartedAt.epochSecond}"
        notificationEventService.withdrawLikeActor(recipientId, dedupeKey)
        likeNotificationCursorDao.delete(postId, likerId)
    }
}
