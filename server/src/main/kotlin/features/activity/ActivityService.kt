package com.revio.server.features.activity

import com.revio.server.core.storage.IStorageService
import com.revio.server.core.util.resolveZone
import com.revio.server.features.activity.dto.ActivityItemDTO
import com.revio.server.features.activity.dto.ActivityResponseDTO
import com.revio.server.features.leaderboard.ILeaderboardDAO
import com.revio.server.features.leaderboard.ILeaderboardSnapshotDAO
import com.revio.server.features.post.IPostDAO
import features.activity.ActivityEventRow
import features.activity.CommentActivityRow
import features.activity.IActivityDAO
import features.activity.LikeActivityRow
import features.comment.CommentService
import features.like.LikeService
import java.time.DayOfWeek
import java.time.Instant
import java.time.temporal.TemporalAdjusters
import java.util.UUID

/**
 * How many raw rows [ActivityService.getActivity] asks [IActivityDAO] for per LIKE/COMMENT
 * source before aggregating, relative to the response `limit` — aggregation can collapse many
 * raw rows into one card, so fetching only `limit` raw rows can under-fill a page even though
 * older, un-aggregated rows exist that would fill it. Capped by [ACTIVITY_RAW_FETCH_CAP] so a
 * very small `limit` doesn't turn into an unbounded fetch (plan Partea II, Pasul 4).
 */
private const val ACTIVITY_OVERFETCH_FACTOR = 4

/** Upper bound on raw rows fetched per LIKE/COMMENT source, regardless of [ACTIVITY_OVERFETCH_FACTOR] * `limit`. */
private const val ACTIVITY_RAW_FETCH_CAP = 400

interface IActivityService {
    /**
     * Aggregates a user's activity feed: weekly SpotScore delta, today's unique interactor
     * count, and the merged/sorted timeline of LIKE, COMMENT, LEADERBOARD_UP and STREAK items.
     */
    suspend fun getActivity(userId: UUID, limit: Int, timezone: String?): ActivityResponseDTO
}

class ActivityService(
    private val activityDao: IActivityDAO,
    private val snapshotDao: ILeaderboardSnapshotDAO,
    private val leaderboardDao: ILeaderboardDAO,
    private val postDao: IPostDAO,
    private val storageService: IStorageService,
    private val snapshotZoneId: String? = System.getenv("LEADERBOARD_SNAPSHOT_ZONE"),
) : IActivityService {

    override suspend fun getActivity(userId: UUID, limit: Int, timezone: String?): ActivityResponseDTO {
        val zone = resolveZone(timezone ?: snapshotZoneId)
        val today = Instant.now().atZone(zone).toLocalDate()
        val dayStart = today.atStartOfDay(zone).toInstant()
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

        val currentScore = leaderboardDao.getUserScoreAndStreak(userId)?.spotScore ?: 0
        val weekStartBaseline = snapshotDao.getSpotScoreOnOrBefore(userId, weekStart)
        val weeklySpotScore = if (weekStartBaseline != null) {
            (currentScore - weekStartBaseline).coerceAtLeast(0)
        } else {
            // No snapshot yet for this week's Monday (e.g. cron hasn't run) — fall back to
            // summing points earned since then directly from posts.
            val weekStartInstant = weekStart.atStartOfDay(zone).toInstant()
            postDao.sumPointsSince(userId, weekStartInstant).coerceAtLeast(0)
        }

        val todayInteractions = activityDao.countTodayUniqueInteractors(userId, dayStart).toInt()

        val rawFetchLimit = (limit * ACTIVITY_OVERFETCH_FACTOR).coerceAtMost(ACTIVITY_RAW_FETCH_CAP)
        val likeItems = activityDao.getLikeItems(userId, rawFetchLimit).aggregateLikes()
        val commentItems = activityDao.getCommentItems(userId, rawFetchLimit).aggregateComments()
        val persistedItems = activityDao.getPersistedEvents(userId, limit).map { it.toDTO() }

        val items = (likeItems + commentItems + persistedItems)
            .sortedByDescending { it.createdAt }
            .take(limit)

        return ActivityResponseDTO(
            weeklySpotScore = weeklySpotScore,
            todayInteractions = todayInteractions,
            items = items,
        )
    }

    /**
     * Groups likes by `(postId, aggregation window)` — the same 60-minute calendar-floor bucket
     * [LikeService] uses to aggregate `user_notifications` rows — so a card in Activity always
     * represents the same set of actors as the matching notification (plan Partea II, Pasul 3).
     * The most recent row in each group becomes the card's actor/avatar/timestamp; [ActivityItemDTO.actorCount]
     * carries the distinct-actor count for the card's copy on Android.
     */
    private fun List<LikeActivityRow>.aggregateLikes(): List<ActivityItemDTO> =
        groupBy { row -> row.postId to LikeService.windowStartFor(row.createdAt) }
            .map { (window, rows) ->
                val (postId, windowStart) = window
                val latest = rows.maxBy { it.createdAt }
                val actorCount = rows.map { it.actorUserId }.distinct().size
                ActivityItemDTO(
                    type = "LIKE",
                    id = "like:$postId:${windowStart.epochSecond}",
                    actorUserId = latest.actorUserId,
                    actorUsername = latest.actorUsername,
                    actorAvatarUrl = latest.actorProfilePicturePath?.let(storageService::resolveUrl),
                    postId = latest.postId,
                    postThumbnailUrl = storageService.resolveUrl(latest.postImageKey),
                    brand = latest.brand,
                    model = latest.model,
                    createdAt = latest.createdAt,
                    actorCount = actorCount,
                )
            }

    /**
     * Same grouping as [aggregateLikes], but on [CommentService]'s 15-minute window. `commentText`
     * is kept only for a single-actor group — an aggregated group has no one comment to show, same
     * as [com.revio.server.features.notification.NotificationEventService] deliberately excluding
     * comment text from aggregated notification copy.
     */
    private fun List<CommentActivityRow>.aggregateComments(): List<ActivityItemDTO> =
        groupBy { row -> row.postId to CommentService.windowStartFor(row.createdAt) }
            .map { (window, rows) ->
                val (postId, windowStart) = window
                val latest = rows.maxBy { it.createdAt }
                val actorCount = rows.map { it.actorUserId }.distinct().size
                ActivityItemDTO(
                    type = "COMMENT",
                    id = "comment:$postId:${windowStart.epochSecond}",
                    actorUserId = latest.actorUserId,
                    actorUsername = latest.actorUsername,
                    actorAvatarUrl = latest.actorProfilePicturePath?.let(storageService::resolveUrl),
                    postId = latest.postId,
                    postThumbnailUrl = storageService.resolveUrl(latest.postImageKey),
                    brand = latest.brand,
                    model = latest.model,
                    commentText = if (actorCount == 1) latest.commentText else null,
                    createdAt = latest.createdAt,
                    actorCount = actorCount,
                )
            }

    private fun ActivityEventRow.toDTO(): ActivityItemDTO = when (type) {
        ActivityEventType.STREAK -> ActivityItemDTO(
            type = "STREAK",
            id = "streak:$id",
            streakDays = valueInt,
            createdAt = createdAt,
        )
        ActivityEventType.LEADERBOARD_UP -> ActivityItemDTO(
            type = "LEADERBOARD_UP",
            id = "lb:$id",
            placesMoved = valueInt,
            createdAt = createdAt,
        )
    }
}
