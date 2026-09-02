package com.revio.server.features.notification

import com.revio.server.config.NotificationMetrics
import com.revio.server.features.leaderboard.ILeaderboardDAO
import com.revio.server.features.leaderboard.ILeaderboardDeltaService
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

private val logger = LoggerFactory.getLogger("com.revio.server.features.notification.NotificationOutboxProcessor")

/** How often [logger] re-warns about the same unconfigured Firebase project, so a stuck config issue doesn't spam every 25s dispatcher tick. */
private const val UNCONFIGURED_WARN_INTERVAL_MS = 5 * 60 * 1000L

/**
 * Backoff schedule for retriable FCM failures (5xx/429), in seconds — 30s, 2m, 8m, 30m, 2h, one
 * entry per retry (plan §14). After the retry whose delay is the schedule's last entry also
 * fails, [nextRetryDecision] gives up ([RetryDecision.Dead]) rather than scheduling a 6th attempt.
 */
private val BACKOFF_SCHEDULE_SECONDS = longArrayOf(30, 120, 480, 1800, 7200)

/** What to do after a retriable send failure, given how many attempts (including this one) have now failed. */
internal sealed class RetryDecision {
    data class Retry(val delaySeconds: Long) : RetryDecision()
    data object Dead : RetryDecision()
}

/**
 * Pure backoff decision, extracted for direct unit testing (same reasoning as
 * [classifyResponse] in PushDispatchService.kt). [attemptsAfterFailure] is the outbox row's
 * `attempts` counter *after* incrementing for the failure just observed — so 1 means "this was
 * the first failure", up to [BACKOFF_SCHEDULE_SECONDS]'s length meaning "this was the last
 * scheduled retry; if it fails too, or a further attempt still fails, give up".
 */
internal fun nextRetryDecision(attemptsAfterFailure: Int, jitter: (Long) -> Long = ::defaultJitter): RetryDecision {
    if (attemptsAfterFailure > BACKOFF_SCHEDULE_SECONDS.size) {
        return RetryDecision.Dead
    }
    val base = BACKOFF_SCHEDULE_SECONDS[attemptsAfterFailure - 1]
    return RetryDecision.Retry(jitter(base))
}

/** +0-10% jitter (uncapped at 1s minimum) so many rows scheduled for the same backoff step don't all retry in the same instant. */
private fun defaultJitter(baseSeconds: Long): Long {
    val spread = (baseSeconds / 10).coerceAtLeast(1)
    return baseSeconds + Random.nextLong(0, spread + 1)
}

private fun priorityFor(category: NotificationCategory): FcmPriority = when (category) {
    NotificationCategory.COMMENTS -> FcmPriority.HIGH
    else -> FcmPriority.NORMAL
}

internal data class OutboxNotificationInfo(
    val recipientId: UUID,
    val category: NotificationCategory,
    val title: String,
    val body: String,
    val deepLink: String?,
    val postId: UUID?,
    val commentId: UUID?,
    /** Non-null only for a day-7 inactivity reminder (plan §9 / §18, step 6.5) — see [NotificationTable.enqueuedDeltaPoints]. */
    val enqueuedDeltaPoints: Int?,
)

private fun findNotificationInfo(notificationId: UUID): OutboxNotificationInfo? = transaction {
    NotificationTable
        .select(
            NotificationTable.userId,
            NotificationTable.category,
            NotificationTable.title,
            NotificationTable.body,
            NotificationTable.deepLink,
            NotificationTable.postId,
            NotificationTable.commentId,
            NotificationTable.enqueuedDeltaPoints,
        )
        .where { NotificationTable.id eq notificationId }
        .singleOrNull()
        ?.let { row ->
            OutboxNotificationInfo(
                recipientId = row[NotificationTable.userId],
                category = row[NotificationTable.category],
                title = row[NotificationTable.title],
                body = row[NotificationTable.body],
                deepLink = row[NotificationTable.deepLink],
                postId = row[NotificationTable.postId]?.value,
                commentId = row[NotificationTable.commentId]?.value,
                enqueuedDeltaPoints = row[NotificationTable.enqueuedDeltaPoints],
            )
        }
}

private fun buildDataPayload(notificationId: UUID, info: OutboxNotificationInfo): Map<String, String> = buildMap {
    put("notification_id", notificationId.toString())
    put("category", info.category.name)
    info.deepLink?.let { put("deep_link", it) }
    info.postId?.let { put("post_id", it.toString()) }
    info.commentId?.let { put("comment_id", it.toString()) }
}

/**
 * Renders the single combined title/body for a collapsed backlog send (plan §14: "un user cu 4
 * evenimente amânate nu trebuie să primească 4 push-uri la 08:00"). Pure function — same
 * reasoning as [nextRetryDecision]/[renderCommentCopy] for direct unit testing. Deliberately
 * generic rather than per-post: a collapsed send always spans more than one underlying event, so
 * there is no single post/comment left to name.
 */
internal fun buildCollapsedCopy(infos: List<OutboxNotificationInfo>): Pair<String, String> {
    val likes = infos.count { it.category == NotificationCategory.LIKES }
    val comments = infos.count { it.category == NotificationCategory.COMMENTS }
    val otherCount = infos.size - likes - comments
    val parts = buildList {
        if (likes > 0) add(if (likes == 1) "1 like" else "$likes likes")
        if (comments > 0) add(if (comments == 1) "1 comment" else "$comments comments")
        if (otherCount > 0) add(if (otherCount == 1) "1 update" else "$otherCount updates")
    }
    val summary = when (parts.size) {
        0 -> "updates"
        1 -> parts[0]
        else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
    }
    return "While you were away" to "You have $summary."
}

private fun buildCollapsedDataPayload(notificationIds: List<UUID>): Map<String, String> = buildMap {
    put("notification_ids", notificationIds.joinToString(",") { it.toString() })
    put("collapsed", "true")
}

interface INotificationOutboxProcessor {
    /**
     * Drains up to [limit] due outbox rows (see [INotificationOutboxDAO.findDrainable]) and
     * attempts to send them. Never throws for an individual row's failure — every outcome
     * (success, retriable failure, terminal failure, expiry) is handled by updating that row's
     * state; a row that can't be processed (missing notification/device) is dropped rather than
     * left to jam the queue forever.
     *
     * Rows that are all due together for the same (recipient, device) are collapsed into a
     * single FCM send instead of one send per row (plan §14 / §18 step 5.5) — e.g. several
     * quiet-hours-deferred events all becoming due at once when quiet hours end. Collapsing only
     * changes how many FCM calls are made and how those rows' outbox state is updated; the
     * underlying `user_notifications` rows are never touched by this class, so the inbox always
     * keeps one row per original event.
     */
    suspend fun processDueBatch(limit: Int = 100)
}

/**
 * The outbox's actual send-with-retry logic (plan §18, step 3.6, extended by step 5.5 for
 * backlog collapsing): the piece [PushDispatcherLoop] (step 3.5) delegates its per-tick `work`
 * to. Handles, in order, per row:
 * 1. TTL: an already-expired row is dropped without ever calling FCM — this is also how a
 *    like-notification digest older than its freshness TTL (plan §8.1: 6h) is abandoned rather
 *    than sent hours late; the row's `user_notifications` entry is untouched, so it still shows
 *    up in the inbox.
 * 2. Grouping: the remaining due rows are grouped by (recipient, device). A group of one sends
 *    exactly as before; a group of more than one is collapsed into a single combined FCM send
 *    (plan §18, step 5.5).
 * 3. The result: accepted -> done (every row in the group, for a collapsed send); a terminal FCM
 *    error (UNREGISTERED/INVALID_ARGUMENT) -> dead-lettered *and* the device deactivated (no
 *    further pushes to a token FCM has rejected outright); a retriable error -> backoff-scheduled
 *    retry per row, or dead-lettered once that row's own retry budget
 *    ([BACKOFF_SCHEDULE_SECONDS]) is exhausted; missing credentials -> left untouched, so the
 *    next tick retries once the config is fixed without spending an attempt on it.
 */
class NotificationOutboxProcessor(
    private val outboxDao: INotificationOutboxDAO,
    private val deviceDao: IUserDeviceDAO,
    private val pushDispatchService: IPushDispatchService,
    private val leaderboardDao: ILeaderboardDAO,
    private val leaderboardDeltaService: ILeaderboardDeltaService,
) : INotificationOutboxProcessor {

    private data class Resolved(
        val entry: NotificationOutboxEntry,
        val info: OutboxNotificationInfo,
        val device: UserDevice,
    )

    /** Last time (epoch ms) [logger] warned about each project's unconfigured FCM credential — see [UNCONFIGURED_WARN_INTERVAL_MS]. */
    private val lastUnconfiguredWarnAtMs = ConcurrentHashMap<FirebaseProject, AtomicLong>()

    override suspend fun processDueBatch(limit: Int) {
        val now = OffsetDateTime.now(ZoneOffset.UTC)

        val resolved = mutableListOf<Resolved>()
        for (entry in outboxDao.findDrainable(limit)) {
            if (entry.expiresAt != null && !now.isBefore(entry.expiresAt)) {
                outboxDao.markDropped(entry.id)
                NotificationMetrics.outboxDroppedExpired()
                continue
            }

            val info = findNotificationInfo(entry.notificationId)?.let { withFreshLeaderboardCopyIfNeeded(entry.notificationId, it) }
            val device = deviceDao.findById(entry.deviceId)

            if (info == null || device == null || device.fcmToken == null) {
                // Nothing sensible to send to (orphaned notification row, deleted device, or a
                // device already deactivated between enqueue and drain) — drop rather than retry
                // forever against something that will never become sendable again.
                outboxDao.markDropped(entry.id)
                continue
            }

            resolved += Resolved(entry, info, device)
        }

        for (group in resolved.groupBy { it.info.recipientId to it.device.id }.values) {
            if (group.size == 1) {
                sendOne(group.single(), now)
            } else {
                sendCollapsed(group, now)
            }
        }
    }

    /**
     * Plan §9 / §18, step 6.5: "delta se recalculează la dispatch, nu la enqueue." Only ever
     * touches a day-7 inactivity reminder ([OutboxNotificationInfo.enqueuedDeltaPoints] non-null
     * is exactly that marker — every other category/row is returned unchanged). Recomputes the
     * user's current rank/delta right now — quiet-hours deferral can put hours between enqueue
     * and this actual send, during which the leaderboard moves — and re-renders the copy from
     * that fresh value, falling back to generic copy via [renderDay7Copy]/[deltaHasDrifted] if it
     * has drifted by more than 30% from what was stored at enqueue time.
     *
     * The recomputed copy is also written back to the `user_notifications` row itself, not just
     * used for the FCM payload — the inbox is meant to show exactly what was sent (plan §14), so
     * a recipient checking the inbox after the fact must see the same (possibly-fallen-back-to-
     * generic) text the push actually carried, not the stale enqueue-time draft.
     */
    private suspend fun withFreshLeaderboardCopyIfNeeded(notificationId: UUID, info: OutboxNotificationInfo): OutboxNotificationInfo {
        if (info.category != NotificationCategory.REMINDERS || info.enqueuedDeltaPoints == null) return info

        val rank = leaderboardDao.getUserRank(info.recipientId)
        val currentDelta = leaderboardDeltaService.computeDelta(info.recipientId)?.pointsToGuaranteeMoveUp
        val (title, body) = renderDay7Copy(rank, currentDelta, info.enqueuedDeltaPoints)

        transaction {
            NotificationTable.update({ NotificationTable.id eq notificationId }) {
                it[NotificationTable.title] = title
                it[NotificationTable.body] = body
            }
        }

        return info.copy(title = title, body = body)
    }

    private suspend fun sendOne(resolved: Resolved, now: OffsetDateTime) {
        val (entry, info, device) = resolved
        val result = pushDispatchService.send(
            project = device.firebaseProject,
            fcmToken = device.fcmToken!!,
            title = info.title,
            body = info.body,
            data = buildDataPayload(entry.notificationId, info),
            priority = priorityFor(info.category),
            ttlSeconds = entry.expiresAt?.let { Duration.between(now, it).seconds.coerceAtLeast(0) },
        )
        applyResult(listOf(entry), device.id, result)
    }

    /**
     * Sends one FCM message covering every row in [group] (plan §18, step 5.5) instead of one
     * per row. [group] is already known to share the same recipient and device — that's the
     * grouping key in [processDueBatch]. The result of the single send is applied identically to
     * every row in the group: all accepted together, or each retried/dead-lettered on its own
     * schedule on failure (their `attempts` counters are independent even though they were sent
     * together).
     */
    private suspend fun sendCollapsed(group: List<Resolved>, now: OffsetDateTime) {
        val device = group.first().device
        val infos = group.map { it.info }
        val (title, body) = buildCollapsedCopy(infos)
        val priority = if (infos.any { it.category == NotificationCategory.COMMENTS }) FcmPriority.HIGH else FcmPriority.NORMAL
        val soonestExpiry = group.mapNotNull { it.entry.expiresAt }.minOrNull()

        val result = pushDispatchService.send(
            project = device.firebaseProject,
            fcmToken = device.fcmToken!!,
            title = title,
            body = body,
            data = buildCollapsedDataPayload(group.map { it.entry.notificationId }),
            priority = priority,
            ttlSeconds = soonestExpiry?.let { Duration.between(now, it).seconds.coerceAtLeast(0) },
        )
        applyResult(group.map { it.entry }, device.id, result)
    }

    private suspend fun applyResult(entries: List<NotificationOutboxEntry>, deviceId: UUID, result: FcmSendResult) {
        when (result) {
            is FcmSendResult.Accepted -> entries.forEach {
                outboxDao.markAccepted(it.id, result.fcmMessageId)
                val ageMs = Duration.between(it.createdAt, OffsetDateTime.now(ZoneOffset.UTC)).toMillis().coerceAtLeast(0)
                NotificationMetrics.outboxAccepted(ageMs)
            }
            is FcmSendResult.Terminal -> {
                entries.forEach {
                    outboxDao.markDead(it.id, result.reason.name)
                    NotificationMetrics.outboxDead()
                }
                deviceDao.deactivateById(deviceId)
                NotificationMetrics.deviceDeactivated("fcm_${result.reason.name.lowercase()}")
            }
            is FcmSendResult.Retriable -> entries.forEach { applyRetryOrDeadLetter(it, "HTTP_${result.httpStatus}", result.retryAfterSeconds) }
            is FcmSendResult.Unknown -> entries.forEach { applyRetryOrDeadLetter(it, "HTTP_${result.httpStatus}", retryAfterSeconds = null) }
            is FcmSendResult.Unconfigured -> {
                NotificationMetrics.outboxUnconfigured(result.project.name)
                val lastWarnAt = lastUnconfiguredWarnAtMs.getOrPut(result.project) { AtomicLong(0) }
                val now = System.currentTimeMillis()
                if (now - lastWarnAt.get() >= UNCONFIGURED_WARN_INTERVAL_MS) {
                    lastWarnAt.set(now)
                    logger.warn(
                        "Skipping {} outbox row(s) for project={}: no usable FCM credential configured",
                        entries.size,
                        result.project,
                    )
                }
            }
        }
    }

    private suspend fun applyRetryOrDeadLetter(entry: NotificationOutboxEntry, errorCode: String, retryAfterSeconds: Long?) {
        NotificationMetrics.outboxFailed(errorCode)
        val attempts = entry.attempts + 1
        when (val decision = nextRetryDecision(attempts)) {
            is RetryDecision.Retry -> {
                val delaySeconds = retryAfterSeconds ?: decision.delaySeconds
                outboxDao.markRetriableFailure(
                    id = entry.id,
                    attempts = attempts,
                    nextAttemptAt = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(delaySeconds),
                    lastErrorCode = errorCode,
                )
            }
            RetryDecision.Dead -> {
                outboxDao.markDead(entry.id, errorCode)
                NotificationMetrics.outboxDead()
            }
        }
    }
}
