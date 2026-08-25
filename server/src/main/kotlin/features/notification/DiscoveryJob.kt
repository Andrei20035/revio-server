package com.revio.server.features.notification

import com.revio.server.config.NotificationMetrics
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Outcome of one [IDiscoveryJob.run] pass — for the cron endpoint's response and logging. */
data class DiscoveryRunResult(
    val evaluated: Int,
    val sent: Int,
    val skipped: Int,
)

interface IDiscoveryJob {
    /**
     * One discovery run (plan §18, step 6.3): evaluates every account-age-eligible candidate
     * against prefs, the content threshold, the 12h feed-open gate, the weekly cap, and quiet
     * hours (deferring or skipping per [shouldSkipInsteadOfDefer]), and records+enqueues a
     * `DISCOVERY` notification for whoever passes all of them. [now] is injectable for tests;
     * production calls use the default.
     */
    suspend fun run(now: Instant = Instant.now()): DiscoveryRunResult
}

/**
 * Plan §18, step 6.3. Ties together [IDiscoveryDAO] (facts), [IUserNotificationPrefsDAO] (prefs +
 * quiet hours window), and [INotificationPolicyService] (the quiet-hours decision itself, reusing
 * step 5.4's logic rather than reimplementing it) — see [DiscoveryEligibility.kt] for the pure
 * eligibility checks this orchestrates.
 */
class DiscoveryJob(
    private val discoveryDao: IDiscoveryDAO,
    private val notificationPrefsDao: IUserNotificationPrefsDAO,
    private val notificationEventService: INotificationEventService,
    private val notificationPolicyService: INotificationPolicyService,
    private val userDeviceDao: IUserDeviceDAO,
    private val notificationOutboxDao: INotificationOutboxDAO,
) : IDiscoveryJob {

    override suspend fun run(now: Instant): DiscoveryRunResult {
        var sent = 0
        var skipped = 0

        val candidates = discoveryDao.findCandidates(now)
        for (candidate in candidates) {
            val prefs = notificationPrefsDao.get(candidate.userId)
            if (!prefs.discoveryEnabled) {
                NotificationMetrics.suppressed("discovery_prefs_disabled")
                skipped++
                continue
            }

            if (feedOpenedRecently(candidate.lastFeedOpenAt, now)) {
                NotificationMetrics.suppressed("discovery_feed_opened_recently")
                skipped++
                continue
            }

            val since = candidate.lastFeedOpenAt ?: candidate.createdAt
            val newSpotCount = discoveryDao.countNewPostsInCountrySince(candidate.country, since)
            if (!hasEnoughNewContent(newSpotCount)) {
                NotificationMetrics.suppressed("discovery_insufficient_content")
                skipped++
                continue
            }

            val weeklySentCount = discoveryDao.countDiscoverySentSince(candidate.userId, now.minus(Duration.ofDays(7)))
            if (!isUnderWeeklyCap(weeklySentCount)) {
                NotificationMetrics.suppressed("discovery_weekly_cap")
                skipped++
                continue
            }

            val zone = discoveryDao.findMostRecentDeviceTimezone(candidate.userId)?.let {
                runCatching { ZoneId.of(it) }.getOrNull()
            }
            val nowOffset = now.atOffset(ZoneOffset.UTC)
            val decision = notificationPolicyService.evaluate(
                NotificationPolicyInput(
                    category = NotificationCategory.DISCOVERY,
                    categoryEnabled = true,
                    now = nowOffset,
                    zone = zone,
                    quietStart = prefs.quietStart,
                    quietEnd = prefs.quietEnd,
                ),
            )
            if (decision.verdict == NotificationVerdict.SUPPRESS || shouldSkipInsteadOfDefer(decision, nowOffset)) {
                NotificationMetrics.suppressed("discovery_policy")
                skipped++
                continue
            }
            if (decision.verdict == NotificationVerdict.DEFER) {
                NotificationMetrics.deferred("discovery")
            }

            val dedupeKey = "discovery:${candidate.userId}:${now.atZone(ZoneOffset.UTC).toLocalDate()}"
            val notificationId = notificationEventService.record(
                recipientId = candidate.userId,
                category = NotificationCategory.DISCOVERY,
                dedupeKey = dedupeKey,
                actorId = null,
                actorUsername = null,
                title = "$newSpotCount new spots since you last looked",
                body = "See what the community found.",
            )

            userDeviceDao.findActiveByUser(candidate.userId).forEach { device ->
                notificationOutboxDao.enqueue(notificationId, device.id, notBefore = decision.notBefore)
            }
            sent++
        }

        return DiscoveryRunResult(evaluated = candidates.size, sent = sent, skipped = skipped)
    }
}
