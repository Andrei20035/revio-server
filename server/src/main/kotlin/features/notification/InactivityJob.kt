package com.revio.server.features.notification

import com.revio.server.config.NotificationMetrics
import com.revio.server.features.leaderboard.ILeaderboardDAO
import com.revio.server.features.leaderboard.ILeaderboardDeltaService
import com.revio.server.features.user.IUserDAO
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Outcome of one [IInactivityJob.run] pass — for the cron endpoint's response and logging. */
data class InactivityRunResult(
    val evaluated: Int,
    val sent: Int,
    val skipped: Int,
)

interface IInactivityJob {
    /**
     * One inactivity run (plan §18, step 6.4): evaluates every user who has ever posted against
     * ban state, the `reminders` preference, the 24h `last_app_open` gate, and the local day-3/
     * day-7 milestone computed fresh from `MAX(posts.created_at)` every time — see
     * [InactivityEligibility.kt] for why that alone is enough to reset on a new post and stay
     * silent forever past day 7 (D8), with no extra state. [now] is injectable for tests;
     * production calls use the default.
     */
    suspend fun run(now: Instant = Instant.now()): InactivityRunResult
}

/**
 * Plan §18, step 6.4. Ties together [IInactivityDAO] (facts), [IUserDAO] (ban state),
 * [IUserNotificationPrefsDAO] (the `reminders` pref + quiet hours window), and
 * [INotificationPolicyService] (the quiet-hours decision itself, reusing step 5.4's logic) — see
 * [InactivityEligibility.kt] for the pure day-boundary/copy logic this orchestrates.
 */
class InactivityJob(
    private val inactivityDao: IInactivityDAO,
    private val userDao: IUserDAO,
    private val notificationPrefsDao: IUserNotificationPrefsDAO,
    private val notificationEventService: INotificationEventService,
    private val notificationPolicyService: INotificationPolicyService,
    private val userDeviceDao: IUserDeviceDAO,
    private val notificationOutboxDao: INotificationOutboxDAO,
    private val leaderboardDao: ILeaderboardDAO,
    private val leaderboardDeltaService: ILeaderboardDeltaService,
) : IInactivityJob {

    override suspend fun run(now: Instant): InactivityRunResult {
        var sent = 0
        var skipped = 0

        val candidates = inactivityDao.findCandidates()
        for (candidate in candidates) {
            val banState = userDao.findBanState(candidate.userId)
            if (banState?.isActive(now) == true) {
                NotificationMetrics.suppressed("inactivity_banned")
                skipped++
                continue
            }

            val prefs = notificationPrefsDao.get(candidate.userId)
            if (!prefs.remindersEnabled) {
                NotificationMetrics.suppressed("inactivity_prefs_disabled")
                skipped++
                continue
            }

            val lastAppOpen = inactivityDao.findLastAppOpen(candidate.userId)
            if (lastAppOpenTooRecent(lastAppOpen, now)) {
                NotificationMetrics.suppressed("inactivity_app_opened_recently")
                skipped++
                continue
            }

            // A scheduled category needs a real zone both to compute the local day boundary and
            // (below) for the quiet-hours decision — unlike discovery, there's no ">6h -> skip"
            // refinement here (plan §8.4: "Fiind un reminder programat, nu are TTL scurt").
            val zone = inactivityDao.findMostRecentDeviceTimezone(candidate.userId)?.let {
                runCatching { ZoneId.of(it) }.getOrNull()
            }
            if (zone == null) {
                NotificationMetrics.suppressed("inactivity_no_timezone")
                skipped++
                continue
            }

            val lastPostLocalDate = candidate.lastPostAt.atZone(zone).toLocalDate()
            val todayLocalDate = now.atZone(zone).toLocalDate()
            val milestone = milestoneFor(daysSinceLastPost(lastPostLocalDate, todayLocalDate))
            if (milestone == null) {
                NotificationMetrics.suppressed("inactivity_no_milestone")
                skipped++
                continue
            }

            val decision = notificationPolicyService.evaluate(
                NotificationPolicyInput(
                    category = NotificationCategory.REMINDERS,
                    categoryEnabled = true,
                    now = now.atOffset(ZoneOffset.UTC),
                    zone = zone,
                    quietStart = prefs.quietStart,
                    quietEnd = prefs.quietEnd,
                ),
            )
            if (decision.verdict == NotificationVerdict.SUPPRESS) {
                NotificationMetrics.suppressed("inactivity_policy")
                skipped++
                continue
            }
            if (decision.verdict == NotificationVerdict.DEFER) {
                NotificationMetrics.deferred("reminders")
            }

            // Day 3's copy is fixed (plan §8.4: kept as-is). Day 7's is leaderboard-conditioned
            // (plan §9 / §18, step 6.5) — rendered here from the *enqueue-time* rank/delta;
            // enqueuedDeltaPoints is stored so the outbox processor can recompute a fresh delta
            // at actual dispatch and fall back to generic copy if it has drifted too far by then.
            val (title, body, enqueuedDeltaPoints) = if (milestone == InactivityMilestone.DAY_7) {
                val rank = leaderboardDao.getUserRank(candidate.userId)
                val delta = leaderboardDeltaService.computeDelta(candidate.userId)?.pointsToGuaranteeMoveUp
                val (t, b) = renderDay7CopyAtEnqueue(rank, delta)
                Triple(t, b, delta)
            } else {
                val (t, b) = copyFor(milestone)
                Triple(t, b, null)
            }

            val notificationId = notificationEventService.record(
                recipientId = candidate.userId,
                category = NotificationCategory.REMINDERS,
                dedupeKey = dedupeKeyFor(milestone, lastPostLocalDate),
                actorId = null,
                actorUsername = null,
                title = title,
                body = body,
                enqueuedDeltaPoints = enqueuedDeltaPoints,
            )

            userDeviceDao.findActiveByUser(candidate.userId).forEach { device ->
                notificationOutboxDao.enqueue(notificationId, device.id, notBefore = decision.notBefore)
            }
            sent++
        }

        return InactivityRunResult(evaluated = candidates.size, sent = sent, skipped = skipped)
    }
}
