package com.revio.server.features.notification

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/** ≥5 new spots since the user's last feed open (plan §8.3's eligibility row). */
internal const val DISCOVERY_CONTENT_THRESHOLD = 5

/** The user's account must be at least this old (plan §8.3). */
internal const val DISCOVERY_MIN_ACCOUNT_AGE_DAYS = 3L

/** Max 3/week, not cumulative with backlog (plan §8.3/§15). */
internal const val DISCOVERY_WEEKLY_CAP = 3

/** Feed opened within this window before the run → skip entirely (plan §8.3's "Anulare" row). */
internal const val DISCOVERY_FEED_OPEN_SUPPRESS_HOURS = 12L

/**
 * If quiet hours would push the send further than this out, skip rather than deliver a stale
 * digest later (plan §8.3's "Quiet hours" row: "un 'today's spots' livrat mâine dimineață e
 * mort").
 */
internal const val DISCOVERY_MAX_DEFER_HOURS = 6L

/** Plan §8.3: "≥5 spots noi de la ultima deschidere de feed a userului". */
internal fun hasEnoughNewContent(newSpotCount: Int): Boolean = newSpotCount >= DISCOVERY_CONTENT_THRESHOLD

/** Plan §8.3: "userul are cont ≥3 zile". */
internal fun isAccountOldEnough(accountCreatedAt: Instant, now: Instant): Boolean =
    !Duration.between(accountCreatedAt, now).minusDays(DISCOVERY_MIN_ACCOUNT_AGE_DAYS).isNegative

/** Plan §8.3: "Dacă userul a deschis feed-ul în ultimele 12h → skip complet". */
internal fun feedOpenedRecently(lastFeedOpenAt: Instant?, now: Instant): Boolean =
    lastFeedOpenAt != null && Duration.between(lastFeedOpenAt, now).toHours() < DISCOVERY_FEED_OPEN_SUPPRESS_HOURS

/** Plan §8.3/§15: "Max 3/săptămână, nu se cumulează cu backlog-ul". */
internal fun isUnderWeeklyCap(sentThisWeek: Int): Boolean = sentThisWeek < DISCOVERY_WEEKLY_CAP

/**
 * Plan §8.3: "Se mută la următorul slot permis. Dacă slotul e la >6h distanță → skip complet".
 * [decision] is whatever [INotificationPolicyService.evaluate] already decided for this
 * candidate (quiet hours / missing timezone / caps) — this only adds the discovery-specific
 * "too far out, don't bother" refinement on top of a [NotificationVerdict.DEFER] verdict. A
 * [NotificationVerdict.SUPPRESS] (e.g. no timezone known) is left as-is by the caller; this
 * function only ever turns a DEFER into an effective skip, never the reverse.
 */
internal fun shouldSkipInsteadOfDefer(decision: NotificationPolicyDecision, now: OffsetDateTime): Boolean {
    if (decision.verdict != NotificationVerdict.DEFER) return false
    val notBefore = decision.notBefore ?: return false
    // Compared as a Duration, not .toHours() (which truncates towards zero and would treat a
    // 6h01m gap as "6 hours" — exactly wrong at this boundary).
    return Duration.between(now, notBefore) > Duration.ofHours(DISCOVERY_MAX_DEFER_HOURS)
}
