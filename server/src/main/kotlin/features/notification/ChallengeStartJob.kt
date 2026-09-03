package com.revio.server.features.notification

import com.revio.server.config.NotificationMetrics
import com.revio.server.features.challenge.Challenge
import com.revio.server.features.challenge.IChallengeDAO
import com.revio.server.features.user.IUserDAO
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

private val logger = LoggerFactory.getLogger("com.revio.server.features.notification.ChallengeStartJob")

/** Fixed product copy for the "challenge is live" push (push-notifications plan, §12) — no per-challenge variation today. */
private const val CHALLENGE_STARTED_TITLE = "🏁 New challenge is live"
private const val CHALLENGE_STARTED_BODY = "Tap to see the details and start spotting."

/** Recognised by [com.revio.social.core.notifications.PendingDeepLink] on the Android side (D-value "challenge"). */
private const val CHALLENGE_DEEP_LINK = "challenge"

/** How many pages [IChallengeStartDAO.findEligibleUserIdsPage] returns are walked per due challenge. */
private const val DEFAULT_PAGE_SIZE = 500

/** How many due challenges one [ChallengeStartJob.run] processes — defensively capped, same as [com.revio.server.features.challenge.ChallengeAdminRoutes]' finalize-due sweep. */
private const val CHALLENGE_LIMIT = 100

/** Outcome of one [IChallengeStartJob.run] pass — for the cron endpoint's response and logging. */
data class ChallengeStartRunResult(
    val challengesProcessed: Int,
    val notified: Int,
    val skipped: Int,
)

interface IChallengeStartJob {
    /**
     * One challenge-start run: detects every SCHEDULED challenge whose window has just opened
     * (`starts_at <= now < ends_at`) and hasn't had its "challenge is live" fan-out yet
     * ([IChallengeDAO.findDueForStartNotification]), then broadcasts the notification to every
     * eligible user, paginated so the candidate set is never loaded into memory at once (plan
     * §7). [now] is injectable for tests; production calls use the default.
     */
    suspend fun run(now: Instant = Instant.now()): ChallengeStartRunResult
}

/**
 * Push-notifications plan, "challenge is live" work: the job the external cron calls every 5
 * minutes ([challengeStartRoutes]). Structurally mirrors
 * [com.revio.server.features.challenge.ChallengeFinalizationService] — `notified_started_at` is
 * written only after the whole fan-out for a challenge has completed, so a crash mid-run leaves
 * it NULL and the next tick picks the challenge back up; per-user failures are caught and logged
 * so one bad row never stops the rest of the page (mirrors
 * [com.revio.server.features.challenge.ChallengeAdminRoutes]'s finalize-due sweep).
 */
class ChallengeStartJob(
    private val challengeDao: IChallengeDAO,
    private val challengeStartDao: IChallengeStartDAO,
    private val userDao: IUserDAO,
    private val notificationPrefsDao: IUserNotificationPrefsDAO,
    private val notificationEventService: INotificationEventService,
    private val notificationPolicyService: INotificationPolicyService,
    private val userDeviceDao: IUserDeviceDAO,
    private val notificationOutboxDao: INotificationOutboxDAO,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
) : IChallengeStartJob {

    override suspend fun run(now: Instant): ChallengeStartRunResult {
        var challengesProcessed = 0
        var notified = 0
        var skipped = 0

        for (challenge in challengeDao.findDueForStartNotification(now, CHALLENGE_LIMIT)) {
            var cursor: UUID? = null
            while (true) {
                val page = challengeStartDao.findEligibleUserIdsPage(cursor, pageSize)
                if (page.isEmpty()) break

                for (userId in page) {
                    try {
                        if (processUser(challenge, userId, now)) notified++ else skipped++
                    } catch (e: Exception) {
                        logger.error(
                            "challenge-start: notifying user {} for challenge {} failed, page continues",
                            userId,
                            challenge.id,
                            e,
                        )
                    }
                }

                cursor = page.last()
                if (page.size < pageSize) break
            }

            challengeDao.markStartNotified(challenge.id, now)
            challengesProcessed++
        }

        return ChallengeStartRunResult(challengesProcessed, notified, skipped)
    }

    /** @return true if a notification was recorded and enqueued for [userId]; false if skipped. */
    private suspend fun processUser(challenge: Challenge, userId: UUID, now: Instant): Boolean {
        val banState = userDao.findBanState(userId)
        if (banState?.isActive(now) == true) {
            NotificationMetrics.suppressed("challenge_start_banned")
            return false
        }

        val prefs = notificationPrefsDao.get(userId)
        if (!prefs.challengesEnabled) {
            NotificationMetrics.suppressed("challenge_start_prefs_disabled")
            return false
        }

        // The enumeration (IChallengeStartDAO) already filters on an active, token-bearing
        // device, but re-fetches here for the fan-out targets themselves — a device could have
        // been deactivated between enumeration and this user's turn, in which case this is
        // simply empty and there is nothing to enqueue.
        val devices = userDeviceDao.findActiveByUser(userId)
        if (devices.isEmpty()) return false

        val zone = devices.firstNotNullOfOrNull { device -> device.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } }
        val nowOffset = now.atOffset(ZoneOffset.UTC)
        val decision = notificationPolicyService.evaluate(
            NotificationPolicyInput(
                category = NotificationCategory.CHALLENGES,
                categoryEnabled = true,
                now = nowOffset,
                zone = zone,
                quietStart = prefs.quietStart,
                quietEnd = prefs.quietEnd,
            ),
        )
        if (decision.verdict == NotificationVerdict.SUPPRESS) {
            NotificationMetrics.suppressed("challenge_start_policy")
            return false
        }
        if (shouldSkipChallengeStartInsteadOfDefer(decision, nowOffset, challenge.startsAt, challenge.endsAt)) {
            NotificationMetrics.suppressed("challenge_start_defer_too_far")
            return false
        }
        if (decision.verdict == NotificationVerdict.DEFER) {
            NotificationMetrics.deferred("challenges")
        }

        val notificationId = notificationEventService.recordBroadcast(
            recipientId = userId,
            category = NotificationCategory.CHALLENGES,
            dedupeKey = "challenge_started:${challenge.id}",
            targetType = NotificationTargetType.CHALLENGE,
            challengeId = challenge.id,
            title = CHALLENGE_STARTED_TITLE,
            body = CHALLENGE_STARTED_BODY,
            deepLink = CHALLENGE_DEEP_LINK,
        )

        devices.forEach { device ->
            notificationOutboxDao.enqueue(notificationId, device.id, notBefore = decision.notBefore)
        }
        return true
    }
}
