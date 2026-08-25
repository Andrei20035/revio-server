package com.revio.server.features.notification

import kotlin.math.abs

/** A delta that drifted by more than this fraction between enqueue and dispatch falls back to generic copy (plan §9 / §18, step 6.5). */
private const val DELTA_DRIFT_FALLBACK_THRESHOLD = 0.3

/**
 * Plan §9: "Dacă între timp userul a urcat deja sau delta s-a schimbat cu >30%, se cade pe
 * copy-ul generic." [oldDelta] is the value stored at enqueue time, [newDelta] the value
 * recomputed fresh at dispatch. A null on either side (rank became 1, or the user dropped off the
 * board entirely) always counts as drifted — there's nothing meaningful left to compare.
 */
internal fun deltaHasDrifted(oldDelta: Int?, newDelta: Int?): Boolean {
    if (oldDelta == null || newDelta == null) return true
    if (oldDelta == 0) return newDelta != 0
    return abs(newDelta - oldDelta).toDouble() / abs(oldDelta) > DELTA_DRIFT_FALLBACK_THRESHOLD
}

/**
 * Plan §8.4's day-7 copy table, rendered from the *current* rank/delta at dispatch time (plan §9
 * / §18, step 6.5) — never a stale value baked in at enqueue. [oldEnqueuedDelta] is only used to
 * decide whether to fall back to generic copy via [deltaHasDrifted]; it never appears in the
 * rendered text itself.
 *
 * Plan's explicit requirement: rank 1, a missing user (`Int.MAX_VALUE`, [ILeaderboardDAO.getUserRank]'s
 * sentinel), and a drifted/absent delta all produce the same generic copy — never a number.
 */
internal fun renderDay7Copy(rank: Int, currentDelta: Int?, oldEnqueuedDelta: Int?): Pair<String, String> {
    if (rank == Int.MAX_VALUE) {
        return "Your spots have been quiet" to "The community's been busy — see what you missed."
    }
    if (rank <= 1) {
        return "You're still holding #1" to "One more spot keeps it that way."
    }
    if (deltaHasDrifted(oldEnqueuedDelta, currentDelta)) {
        return "Your spots have been quiet" to "The community's been busy — see what you missed."
    }
    return if (currentDelta!! <= 10) {
        "You're close to moving up" to "Your next spot could put you past #${rank - 1}."
    } else {
        "The board moved without you" to "Your next spot starts closing the gap."
    }
}

/**
 * [renderDay7Copy] at enqueue time (plan §18, step 6.4/6.5): no prior value exists yet to drift
 * from, so [delta] is compared against itself — never treated as drifted on its own first
 * computation.
 */
internal fun renderDay7CopyAtEnqueue(rank: Int, delta: Int?): Pair<String, String> =
    renderDay7Copy(rank, currentDelta = delta, oldEnqueuedDelta = delta)
