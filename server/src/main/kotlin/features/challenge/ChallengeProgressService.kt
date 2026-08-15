package com.revio.server.features.challenge

import java.time.Instant
import java.util.UUID

/** One page row of [IChallengeProgressService.listUserHistory]: a challenge plus the caller's own progress on it. */
data class ChallengeHistoryItem(
    val challenge: Challenge,
    val effectiveStatus: EffectiveChallengeStatus,
    val progress: ParticipantProgress,
)

/** One page of [IChallengeProgressService.listUserHistory] — same "+1 row" hasMore technique as [ChallengePage]. */
data class ChallengeHistoryPage(
    val items: List<ChallengeHistoryItem>,
    val hasMore: Boolean,
    val nextCursorEndsAt: Instant?,
    val nextCursorId: UUID?,
)

interface IChallengeProgressService {
    /**
     * Evaluates [postId] against whatever challenge was active at [postCreatedAt] (found via
     * [IChallengeDAO.findActive] using that same instant, so "is there an active challenge" and
     * "is this post inside its window" can never disagree due to clock skew between the two
     * checks). Returns null when no challenge was active — nothing to evaluate.
     *
     * Callers (PostService, Etapa 4) are expected to have already confirmed the post is
     * PostSource.CAMERA and has a non-null carModelId before calling this — those are cheap,
     * challenge-independent preconditions the plan's §4.3 assigns to the caller.
     */
    suspend fun evaluatePostForActiveChallenge(
        userId: UUID,
        postId: UUID,
        carModelId: UUID,
        postCreatedAt: Instant,
    ): ContributionEvaluation?

    /**
     * The [ChallengeProgressService.REVOKE_AFTER_END] policy, as a predicate over a challenge's
     * endsAt, for the post-removal transaction to apply while reconciling contributions.
     *
     * Handed to the DAO rather than executed here because reconciliation must run inside the same
     * transaction that deletes the post (see [IPostRemovalDAO.removePostAtomically]) — but the
     * policy decision itself stays in this service, so it applies uniformly no matter which
     * caller removes the post (its author, or a moderator takedown).
     */
    fun contributionRevokePolicy(): (endsAt: Instant) -> Boolean

    /** [userId]'s progress on [challengeId] — zero/NONE if they have no participant row yet. */
    suspend fun getUserProgress(challengeId: UUID, userId: UUID): ParticipantProgress

    /** [userId]'s contributing posts for [challengeId], oldest first. */
    suspend fun listUserContributions(challengeId: UUID, userId: UUID): List<ContributionSummary>

    /**
     * Keyset-paginated history of challenges [userId] has participated in, newest-finished first
     * (see [IChallengeHistoryDAO.listUserHistory]), each entry carrying its [EffectiveChallengeStatus]
     * computed at read time (see [effectiveStatus]) — a challenge that finished since the caller's
     * last request must show ENDED, not whatever it looked like when the row was written. Fetches
     * one extra row to determine [ChallengeHistoryPage.hasMore], same technique as
     * [IChallengeService.listChallenges].
     */
    suspend fun listUserHistory(
        userId: UUID,
        limit: Int,
        cursorEndsAt: Instant?,
        cursorId: UUID?,
        filter: ChallengeHistoryFilter?,
    ): ChallengeHistoryPage

    /** [userId]'s lifetime challenge participation — see [IChallengeHistoryDAO.getUserAggregates]. */
    suspend fun getUserAggregates(userId: UUID): ChallengeUserAggregates

    /** Whether [postId] has ever contributed to any challenge — see [IChallengeProgressDAO.hasContributions]. */
    suspend fun hasContributions(postId: UUID): Boolean
}

class ChallengeProgressService(
    private val challengeDao: IChallengeDAO,
    private val challengeProgressDao: IChallengeProgressDAO,
    private val challengeHistoryDao: IChallengeHistoryDAO = ChallengeHistoryDAO(),
) : IChallengeProgressService {

    companion object {
        private const val DEFAULT_HISTORY_LIMIT = 20
        private const val MAX_HISTORY_LIMIT = 50
        /**
         * Whether deleting/invalidating a contributing post after its challenge's endsAt still
         * revokes the completion reward. true = revoke (the decided MVP behavior — see the
         * plan's §2.6a: a challenge completed correctly during its window, but abandoned by
         * deleting a contributing post afterward, loses the reward; the post can no longer be
         * replaced once endsAt has passed). false would instead freeze already-granted rewards
         * once a challenge ends. Kept as a single flag, not spread across deletion call sites, so
         * this policy can change later without a migration or touching any deletion path.
         */
        const val REVOKE_AFTER_END = true
    }

    override suspend fun evaluatePostForActiveChallenge(
        userId: UUID,
        postId: UUID,
        carModelId: UUID,
        postCreatedAt: Instant,
    ): ContributionEvaluation? {
        val activeChallenge = challengeDao.findActive(postCreatedAt) ?: return null
        return challengeProgressDao.evaluatePostContribution(
            challengeId = activeChallenge.id,
            userId = userId,
            postId = postId,
            carModelId = carModelId,
            postCreatedAt = postCreatedAt,
        )
    }

    override fun contributionRevokePolicy(): (endsAt: Instant) -> Boolean {
        val now = Instant.now()
        return { endsAt -> REVOKE_AFTER_END || now.isBefore(endsAt) }
    }

    override suspend fun getUserProgress(challengeId: UUID, userId: UUID): ParticipantProgress =
        challengeProgressDao.getProgress(challengeId, userId)
            ?: ParticipantProgress(contributionCount = 0, rewardState = RewardState.NONE)

    override suspend fun listUserContributions(challengeId: UUID, userId: UUID): List<ContributionSummary> =
        challengeProgressDao.listContributionsForUser(challengeId, userId)

    override suspend fun listUserHistory(
        userId: UUID,
        limit: Int,
        cursorEndsAt: Instant?,
        cursorId: UUID?,
        filter: ChallengeHistoryFilter?,
    ): ChallengeHistoryPage {
        val effectiveLimit = validateHistoryLimit(limit)
        require((cursorEndsAt == null) == (cursorId == null)) {
            "cursorEndsAt and cursorId must be provided together"
        }

        val now = Instant.now()
        // Fetch one extra row to determine whether another page exists, same as ChallengeService.listChallenges.
        val rows = challengeHistoryDao.listUserHistory(userId, effectiveLimit + 1, cursorEndsAt, cursorId, filter)
        val hasMore = rows.size > effectiveLimit
        val page = if (hasMore) rows.take(effectiveLimit) else rows

        return ChallengeHistoryPage(
            items = page.map { entry ->
                ChallengeHistoryItem(
                    challenge = entry.challenge,
                    effectiveStatus = effectiveStatus(entry.challenge, now),
                    progress = entry.progress,
                )
            },
            hasMore = hasMore,
            nextCursorEndsAt = if (hasMore) page.last().challenge.endsAt else null,
            nextCursorId = if (hasMore) page.last().challenge.id else null,
        )
    }

    override suspend fun getUserAggregates(userId: UUID): ChallengeUserAggregates =
        challengeHistoryDao.getUserAggregates(userId)

    override suspend fun hasContributions(postId: UUID): Boolean =
        challengeProgressDao.hasContributions(postId)

    private fun validateHistoryLimit(limit: Int): Int {
        if (limit == 0) return DEFAULT_HISTORY_LIMIT
        require(limit in 1..MAX_HISTORY_LIMIT) { "limit must be between 1 and $MAX_HISTORY_LIMIT" }
        return limit
    }
}
