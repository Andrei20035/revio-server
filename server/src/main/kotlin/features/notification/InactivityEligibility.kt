package com.revio.server.features.notification

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Which of the two inactivity milestones (plan §8.4) a given day-count lands on, if either. */
internal enum class InactivityMilestone { DAY_3, DAY_7 }

private const val INACTIVITY_DAY_3 = 3L
private const val INACTIVITY_DAY_7 = 7L

/** "last_app_open > 24h" (plan §8.4's eligibility row). */
internal const val INACTIVITY_LAST_APP_OPEN_SUPPRESS_HOURS = 24L

/** Local calendar days between the user's last post and [today] — both already in the user's own zone. */
internal fun daysSinceLastPost(lastPostLocalDate: LocalDate, today: LocalDate): Long =
    ChronoUnit.DAYS.between(lastPostLocalDate, today)

/**
 * Plan §8.4: exactly day 3 and day 7 fire, nothing else — including everything past day 7
 * ("După: Tăcere completă (D8)... fără push la ziua 30"). No state is needed to enforce that
 * silence: [daysSince] keeps growing past 7 with nothing here ever matching it again, until a new
 * post moves `MAX(posts.created_at)` forward and resets [daysSince] back near zero.
 */
internal fun milestoneFor(daysSince: Long): InactivityMilestone? = when (daysSince) {
    INACTIVITY_DAY_3 -> InactivityMilestone.DAY_3
    INACTIVITY_DAY_7 -> InactivityMilestone.DAY_7
    else -> null
}

/**
 * Plan §8.4: "Anulare la revenire: Nu anulăm — amânăm. Dacă `last_app_open < 24h`, suprimăm și
 * reevaluăm mâine." [lastAppOpen] null (no device ever seen) is never "too recent" — there is
 * nothing to suppress against.
 */
internal fun lastAppOpenTooRecent(lastAppOpen: Instant?, now: Instant): Boolean =
    lastAppOpen != null && Duration.between(lastAppOpen, now) < Duration.ofHours(INACTIVITY_LAST_APP_OPEN_SUPPRESS_HOURS)

/** Plan §8.4's copy table — day 3's copy only; day 7's leaderboard-conditioned variants are step 6.5's job. */
internal fun copyFor(milestone: InactivityMilestone): Pair<String, String> = when (milestone) {
    InactivityMilestone.DAY_3 -> "The leaderboard keeps moving" to "One good spot could put you back in the race."
    // Generic ("date lipsă") variant from plan §8.4 — the rank>1/rank==1-conditioned copy is
    // layered on top of this same mechanism by step 6.5, not built here.
    InactivityMilestone.DAY_7 -> "Your spots have been quiet" to "The community's been busy — see what you missed."
}

/** Plan §18 step 6.4: `dedupe_key = "inactivity:d3:{lastPostDate}"` / `"...d7:{lastPostDate}"` (D8). */
internal fun dedupeKeyFor(milestone: InactivityMilestone, lastPostLocalDate: LocalDate): String {
    val tag = if (milestone == InactivityMilestone.DAY_3) "d3" else "d7"
    return "inactivity:$tag:$lastPostLocalDate"
}
