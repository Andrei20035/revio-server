package com.revio.server.features.leaderboard

import java.util.UUID

/**
 * What a single user would gain from their next spot (plan §9). [pointsToGuaranteeMoveUp] is
 * null when there is nothing to guarantee moving past — already rank 1, or the user isn't ranked
 * at all.
 */
data class LeaderboardDelta(
    val pointsToGuaranteeMoveUp: Int?,
    val placesGainedWithOnePoint: Int,
)

/**
 * The corrected formula from plan §9: naively subtracting scores (`S_A - S`) only produces a
 * *tie* when `S_A == S` — the tie-break then falls on the immutable UUID column, entirely outside
 * the user's control, so it cannot be presented as a guaranteed pass. Guaranteeing one requires
 * strictly exceeding `S_A`, i.e. `S_A - S + 1`; on a tie that's exactly `1`.
 *
 * [rank] follows [ILeaderboardDAO.getUserRank]'s contract: `Int.MAX_VALUE` means the user isn't
 * ranked at all (case 5 in the plan's table — a `#2147483647` would be a visible bug), `1` means
 * already first (case 4) — both have nothing to guarantee moving past, so both return null.
 * [scoreImmediatelyAhead] is `S_A`; null only makes sense at rank 1, handled defensively rather
 * than trusted, since a caller could pass inconsistent inputs.
 *
 * Extracted as a top-level pure function for direct unit testing — same reasoning as
 * `NotificationPolicyService.evaluate`'s pure-decision design and
 * `NotificationOutboxProcessor.nextRetryDecision`.
 */
internal fun pointsToGuaranteeMoveUp(rank: Int, myScore: Int, scoreImmediatelyAhead: Int?): Int? {
    if (rank == Int.MAX_VALUE) return null // case 5: user not found/unranked
    if (rank <= 1) return null // case 4: already first, nothing ahead to pass
    if (scoreImmediatelyAhead == null) return null // defensive: no one ahead despite rank > 1
    return if (scoreImmediatelyAhead == myScore) 1 else scoreImmediatelyAhead - myScore + 1
}

interface ILeaderboardDeltaService {
    /**
     * Single-user variant (plan §18, step 6.1): [ILeaderboardDAO.getUserRank] +
     * [ILeaderboardDAO.getUserScoreAndStreak] for the user's own rank/score, plus
     * [ILeaderboardDeltaDAO.findScoreImmediatelyAhead]/[ILeaderboardDeltaDAO.countTiedAndRankedAhead]
     * for the two extra facts the formula needs. Returns null only if [userId] doesn't exist at
     * all (as opposed to existing-but-unranked, which can't happen — every user has a score).
     */
    suspend fun computeDelta(userId: UUID): LeaderboardDelta?

    /**
     * Batch variant (plan §18, step 6.1): every user's [LeaderboardDelta] computed from
     * [ILeaderboardDeltaDAO.batchRankAndDeltaInputs]'s single window-function pass over `users`,
     * instead of one round-trip per user — the shape a batch job (e.g. the inactivity job, step
     * 6.4) needs without becoming O(N) queries.
     */
    suspend fun computeAllDeltas(): Map<UUID, LeaderboardDelta>
}

class LeaderboardDeltaService(
    private val leaderboardDao: ILeaderboardDAO,
    private val deltaDao: ILeaderboardDeltaDAO,
) : ILeaderboardDeltaService {

    override suspend fun computeDelta(userId: UUID): LeaderboardDelta? {
        val self = leaderboardDao.getUserScoreAndStreak(userId) ?: return null
        val rank = leaderboardDao.getUserRank(userId)

        if (rank <= 1) {
            return LeaderboardDelta(pointsToGuaranteeMoveUp = null, placesGainedWithOnePoint = 0)
        }

        val scoreAhead = deltaDao.findScoreImmediatelyAhead(self.spotScore, userId)
        val tiedAhead = deltaDao.countTiedAndRankedAhead(self.spotScore, userId)

        return LeaderboardDelta(
            pointsToGuaranteeMoveUp = pointsToGuaranteeMoveUp(rank, self.spotScore, scoreAhead),
            placesGainedWithOnePoint = tiedAhead,
        )
    }

    override suspend fun computeAllDeltas(): Map<UUID, LeaderboardDelta> =
        deltaDao.batchRankAndDeltaInputs().associate { row ->
            row.userId to LeaderboardDelta(
                pointsToGuaranteeMoveUp = pointsToGuaranteeMoveUp(row.rank, row.spotScore, row.scoreImmediatelyAhead),
                placesGainedWithOnePoint = row.tiedAndRankedAheadCount,
            )
        }
}
