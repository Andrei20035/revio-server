package com.revio.server.features.notification

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Fraction of a challenge's own `[startsAt, endsAt)` window a quiet-hours defer may consume
 * before the "challenge is live" push is skipped outright rather than delivered late (plan §9: a
 * fixed absolute hour limit — like [DISCOVERY_MAX_DEFER_HOURS] — would be too lenient for a short
 * challenge and needlessly strict for a long one; a threshold relative to the challenge's own
 * duration covers both extremes with one rule). Expressed as a divisor rather than a fraction so
 * the comparison stays exact [Duration] arithmetic: dividing by 4 is exactly 25%, with no
 * floating-point rounding.
 */
internal const val CHALLENGE_START_MAX_DEFER_DIVISOR = 4L

/**
 * Plan §9: "skip dacă `notBefore` ar consuma >25% din `[startsAt, endsAt)`" — e.g. a 48h weekend
 * challenge tolerates up to a 12h defer (quiet hours, at most ~8h, never actually reaches that);
 * a 4h challenge tolerates only 1h. [decision] is whatever [INotificationPolicyService.evaluate]
 * already decided for this candidate (quiet hours / missing timezone / caps) — this only adds the
 * challenge-specific "too far out, don't bother" refinement on top of a
 * [NotificationVerdict.DEFER] verdict. A [NotificationVerdict.SUPPRESS] is left as-is by the
 * caller; this function only ever turns a DEFER into an effective skip, never the reverse — same
 * contract as [shouldSkipInsteadOfDefer].
 */
internal fun shouldSkipChallengeStartInsteadOfDefer(
    decision: NotificationPolicyDecision,
    now: OffsetDateTime,
    challengeStartsAt: Instant,
    challengeEndsAt: Instant,
): Boolean {
    if (decision.verdict != NotificationVerdict.DEFER) return false
    val notBefore = decision.notBefore ?: return false

    val windowDuration = Duration.between(challengeStartsAt, challengeEndsAt)
    if (windowDuration.isZero || windowDuration.isNegative) return true

    val maxDefer = windowDuration.dividedBy(CHALLENGE_START_MAX_DEFER_DIVISOR)
    // Compared as a Duration, not .toHours() (which truncates towards zero and would treat a
    // maxDefer+1m gap as "within budget" at this boundary) — same reasoning as
    // shouldSkipInsteadOfDefer.
    return Duration.between(now, notBefore) > maxDefer
}
