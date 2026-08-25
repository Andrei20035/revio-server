package com.revio.server.features.notification

import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.random.Random

/** What a single notification event should do next, decided by [NotificationPolicyService]. */
enum class NotificationVerdict {
    /** Rendered in the inbox only — no push is attempted. */
    SUPPRESS,

    /** Push deferred to [NotificationPolicyDecision.notBefore] (quiet hours). */
    DEFER,

    /** Push may proceed now. */
    DISPATCH,
}

data class NotificationPolicyDecision(
    val verdict: NotificationVerdict,
    val notBefore: OffsetDateTime? = null,
)

/**
 * Everything [NotificationPolicyService.evaluate] needs to decide one notification's fate. All
 * values are pre-computed by the caller — counts from `notification_outbox`, prefs from
 * `user_notification_prefs`, cooldown state from whatever per-post bookkeeping the aggregation
 * step keeps — so this service can stay pure, with no DB or network access of its own.
 */
data class NotificationPolicyInput(
    val category: NotificationCategory,
    /** The user's preference for [category]. Ignored for [NotificationCategory.ACCOUNT]. */
    val categoryEnabled: Boolean,
    /** The instant being evaluated — dispatch time, not enqueue time (see plan §9/§14). */
    val now: OffsetDateTime,
    /** The device/user's timezone, or null if unknown/unset. */
    val zone: ZoneId?,
    val quietStart: LocalTime,
    val quietEnd: LocalTime,
    /** This event's own category's sends in the last rolling hour, across all of the user's posts. */
    val hourlyCount: Int = 0,
    /** This event's own category's sends today. */
    val dailyCount: Int = 0,
    /**
     * The *other* social category's sends in the last rolling hour (comments' count when
     * evaluating a like, or vice versa) — plan §15's priority ordering: "ACCOUNT > COMMENTS >
     * LIKES > REMINDERS > DISCOVERY". A like sees this added to its own [hourlyCount] against the
     * shared 3/hour social cap, so a saturated cap made up of comments still suppresses the next
     * like; a comment's own fate never looks at this value, so comments always get their own
     * full share of the cap regardless of how many likes already went out. Ignored for any
     * category other than [NotificationCategory.LIKES].
     */
    val otherSocialHourlyCount: Int = 0,
    /** Same priority carve-out as [otherSocialHourlyCount], for the daily social cap. */
    val otherSocialDailyCount: Int = 0,
    /** Sends across every category (except ACCOUNT) today — the absolute per-user cap. */
    val dailyTotalCount: Int = 0,
    /** True if this category+target is within its per-post/per-event cooldown window. */
    val cooldownActive: Boolean = false,
)

/** [0,20] minutes of jitter applied to a quiet-hours defer target — plan §14 / §18 step 5.4. */
internal fun defaultQuietHoursJitterMinutes(): Long = Random.nextLong(0, 21)

interface INotificationPolicyService {
    /**
     * Pure decision function: prefs -> quiet hours -> caps -> cooldown -> verdict (see plan §7's
     * architecture diagram). Each stage is a gate — the first one that doesn't pass decides the
     * verdict outright, later stages are not consulted. No I/O: every fact the decision needs
     * must already be in [input].
     *
     * [jitterMinutes] supplies the [0,20]-minute jitter added to a quiet-hours defer target (plan
     * §14: without it, every user in a timezone would leave the queue at exactly quiet_end,
     * bursting both the server and a wave of simultaneous phone notifications). Injectable for
     * deterministic tests — same reasoning as [nextRetryDecision]'s `jitter` parameter.
     */
    fun evaluate(input: NotificationPolicyInput, jitterMinutes: () -> Long = ::defaultQuietHoursJitterMinutes): NotificationPolicyDecision
}

class NotificationPolicyService : INotificationPolicyService {

    override fun evaluate(input: NotificationPolicyInput, jitterMinutes: () -> Long): NotificationPolicyDecision {
        // ACCOUNT (moderation) is exempt from prefs, quiet hours, and caps entirely — see D6/D5's
        // "ACCOUNT exceptat" notes in the plan.
        if (input.category == NotificationCategory.ACCOUNT) {
            return NotificationPolicyDecision(NotificationVerdict.DISPATCH)
        }

        if (!input.categoryEnabled) {
            return NotificationPolicyDecision(NotificationVerdict.SUPPRESS)
        }

        val zone = input.zone
        if (zone == null) {
            // Unknown timezone: scheduled categories (discovery, reminders) can't be quiet-hours
            // checked, so they're suppressed outright rather than risking a 3am push. Social
            // categories are about "now" (someone just liked/commented) and are dispatched as-is.
            return if (input.category in SCHEDULED_CATEGORIES) {
                NotificationPolicyDecision(NotificationVerdict.SUPPRESS)
            } else {
                evaluateCapsAndCooldown(input)
            }
        }

        val localDateTime = input.now.atZoneSameInstant(zone)
        val localTime = localDateTime.toLocalTime()
        val inQuietWindow = !localTime.isBefore(input.quietStart) && localTime.isBefore(input.quietEnd)
        if (inQuietWindow) {
            // Deferred to quiet_end on the SAME local calendar day — never "tomorrow" (see D5's
            // implementation note: the 00:00-08:00 window is non-circular by construction, since
            // quiet_start < quiet_end is enforced when prefs are written) — plus [0,20] min of
            // jitter (plan §14/§18 step 5.4) so a whole timezone's worth of deferred pushes
            // doesn't leave the queue at exactly the same instant.
            val deferTarget = localDateTime.toLocalDate().atTime(input.quietEnd).atZone(zone).toOffsetDateTime()
                .plusMinutes(jitterMinutes())
            return NotificationPolicyDecision(NotificationVerdict.DEFER, notBefore = deferTarget)
        }

        return evaluateCapsAndCooldown(input)
    }

    private fun evaluateCapsAndCooldown(input: NotificationPolicyInput): NotificationPolicyDecision {
        if (input.dailyTotalCount >= TOTAL_DAILY_CAP) {
            return NotificationPolicyDecision(NotificationVerdict.SUPPRESS)
        }
        if (input.category in SOCIAL_CATEGORIES) {
            // Priority ordering at cap saturation (plan §15): comments outrank likes, so only a
            // like's count against the shared cap includes the other category's sends. A comment
            // is judged solely on prior comments — never suppressed just because likes filled
            // the shared budget first.
            val includeOtherCategory = input.category == NotificationCategory.LIKES
            val hourlyAgainstCap = input.hourlyCount + if (includeOtherCategory) input.otherSocialHourlyCount else 0
            val dailyAgainstCap = input.dailyCount + if (includeOtherCategory) input.otherSocialDailyCount else 0

            if (hourlyAgainstCap >= SOCIAL_HOURLY_CAP) {
                return NotificationPolicyDecision(NotificationVerdict.SUPPRESS)
            }
            if (dailyAgainstCap >= SOCIAL_DAILY_CAP) {
                return NotificationPolicyDecision(NotificationVerdict.SUPPRESS)
            }
        }
        if (input.cooldownActive) {
            return NotificationPolicyDecision(NotificationVerdict.SUPPRESS)
        }
        return NotificationPolicyDecision(NotificationVerdict.DISPATCH)
    }

    companion object {
        private val SCHEDULED_CATEGORIES = setOf(NotificationCategory.DISCOVERY, NotificationCategory.REMINDERS)
        private val SOCIAL_CATEGORIES = setOf(NotificationCategory.LIKES, NotificationCategory.COMMENTS)

        // See plan §15 — "Social (likes + comments)" row.
        private const val SOCIAL_HOURLY_CAP = 3
        private const val SOCIAL_DAILY_CAP = 8

        // See plan §15 — "Total absolut" row.
        private const val TOTAL_DAILY_CAP = 10
    }
}
