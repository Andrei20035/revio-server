package com.revio.server.features.challenge

import com.revio.server.core.db.retrySerializationConflicts
import com.revio.server.features.car_model.CarModelTable
import com.revio.server.features.post.PostTable
import com.revio.server.features.user.UserTable
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Outcome of evaluating one post against one challenge. [rewardGranted]/[rewardRevoked] are true
 * only on the transition that just happened in this call — once a participant is already
 * GRANTED, repeated calls report false/false, which is what makes evaluatePostContribution
 * idempotent under retry (see the ON CONFLICT DO NOTHING inserts inside it).
 */
data class ContributionEvaluation(
    val eligible: Boolean,
    val contributionCount: Int = 0,
    val rewardGranted: Boolean = false,
    val rewardRevoked: Boolean = false,
)

/** Outcome of reversing one contribution as part of [IChallengeProgressDAO.removeContributionsForPost]. */
data class ContributionReversal(
    val challengeId: UUID,
    val contributionCount: Int,
    val rewardRevoked: Boolean,
)

/** Outcome of reconciling one challenge's participants during finalization (plan §9-C). */
data class FinalizationResult(
    val grantedCount: Int,
    val revokedCount: Int,
)

/** One user's read-only progress snapshot on one challenge. */
data class ParticipantProgress(
    val contributionCount: Int,
    val rewardState: RewardState,
)

/**
 * One contributing post, for display (e.g. GET /challenges/{id}/progress). [imageKey] is the raw
 * storage object key, not a resolved URL — turning it into one is the route/service layer's job
 * (see PostService.resolveUrl's callers), keeping this DAO storage-agnostic. [carBrand]/[carModel]
 * come from [ChallengeContributionTable.carModelId] — the model captured at contribution time
 * (see that table's KDoc), not the post's current one, so a later edit to the post's car model
 * never rewrites history here.
 */
data class ContributionSummary(
    val postId: UUID,
    val createdAt: Instant,
    val imageKey: String?,
    val carBrand: String?,
    val carModel: String?,
)

interface IChallengeProgressDAO {
    /**
     * Evaluates whether [postId] (owned by [userId], created at [postCreatedAt] with
     * [carModelId]) contributes to [challengeId]: membership + window check, participant
     * upsert-and-lock, contribution insert, contribution_count recompute. Does **not** grant or
     * revoke the completion reward — that happens only at finalization
     * ([finalizeParticipants], plan §9-C7), once the challenge has ended.
     *
     * This is the one method in the codebase where a single transaction spans multiple logical
     * operations — every other DAO method here is one independent statement per transaction. The
     * reason: challenge_participants is a row shared by every post a user creates during a
     * challenge, and "contribution recorded -> count updated" must be atomic and serialized per
     * (challenge, user), or two concurrent posts from the same user could both observe count-1
     * and cache a stale contribution_count. Callers should wrap this call with
     * [com.revio.server.core.db.retrySerializationConflicts] awareness in mind — it already is,
     * internally.
     */
    suspend fun evaluatePostContribution(
        challengeId: UUID,
        userId: UUID,
        postId: UUID,
        carModelId: UUID,
        postCreatedAt: Instant,
    ): ContributionEvaluation

    /**
     * Revokes the completion reward, tagged with [reason], for every participant of
     * [challengeId] currently holding it (reward_state = GRANTED). One participant per
     * transaction, so a mass revoke never holds a lock across hundreds of rows. Idempotent:
     * each participant is re-checked under lock immediately before revoking, so a retry after a
     * partial failure (or a call on an already-fully-revoked challenge) only processes what's
     * left and returns 0 once nothing remains.
     *
     * Backs both ChallengeService.cancel() (reason = CHALLENGE_CANCELLED) and
     * ChallengeService.revokeAll() (reason = ADMIN_REVOKE_ALL) — the single mass-revoke engine
     * described in the plan's §4.8, so those two callers never duplicate this logic.
     *
     * @return the number of participants actually revoked by this call.
     */
    suspend fun revokeAllGrantedRewards(challengeId: UUID, reason: LedgerReason): Int

    /**
     * Reconciles every participant of [challengeId] against their final contribution count —
     * the per-participant engine behind challenge finalization (plan §9-C). One transaction per
     * participant, same locking pattern as [revokeAllGrantedRewards]: each row is re-read under
     * FOR UPDATE and its count is recomputed fresh from challenge_contributions (not the cached
     * contribution_count column) immediately before deciding, so a retried or concurrent call is
     * idempotent. A participant who reached required_posts but is still NONE is granted; one
     * who is GRANTED but has since dropped below required_posts (e.g. a late moderation
     * removal) is revoked, reason [LedgerReason.CONTRIBUTION_REMOVED]. Anyone already in the
     * matching state (completed-and-GRANTED, or not-completed-and-NONE) is left untouched.
     */
    suspend fun finalizeParticipants(challengeId: UUID): FinalizationResult

    /**
     * Reverses every contribution [postId] made, one challenge at a time.
     *
     * Runs in the CALLER'S already-open transaction — it opens none of its own, deliberately.
     * Reconciling a removed contribution and deleting the post row must commit or roll back as
     * one unit (see IPostRemovalDAO.removePostAtomically): challenge_contributions.post_id is ON
     * DELETE CASCADE, so a delete that committed while this had failed would drop the
     * contribution rows with no chance to revoke the reward they earned, leaving a permanently
     * inflated spot_score that nothing recomputes. Callers must therefore invoke this BEFORE
     * deleting the post row, inside the same transaction.
     *
     * For each challenge the post contributed to: deletes that one contribution row, recomputes
     * contribution_count under the same participant lock [evaluatePostContribution] uses, and —
     * only if the participant is currently GRANTED and progress just dropped below
     * required_posts — revokes the reward, but only when [allowRevoke] (given that challenge's
     * endsAt) returns true. This is how policies like ChallengeProgressService's
     * REVOKE_AFTER_END flag are applied: the DAO stays policy-free, the caller decides.
     */
    fun removeContributionsForPostInCurrentTransaction(
        postId: UUID,
        allowRevoke: (endsAt: Instant) -> Boolean,
    ): List<ContributionReversal>

    /** [userId]'s progress on [challengeId], or null if they have no participant row yet (zero progress). */
    suspend fun getProgress(challengeId: UUID, userId: UUID): ParticipantProgress?

    /** [userId]'s contributing posts for [challengeId], oldest first. */
    suspend fun listContributionsForUser(challengeId: UUID, userId: UUID): List<ContributionSummary>

    /**
     * Whether [postId] has ever contributed to any challenge — i.e. has at least one row in
     * [ChallengeContributionTable], regardless of that challenge's status (active, ended,
     * finalized, or cancelled). Used to lock a post's car model against edits once it has
     * contributed, so a later edit can't retroactively falsify an already-recorded contribution.
     */
    suspend fun hasContributions(postId: UUID): Boolean
}

/** Per-participant result of one [ChallengeProgressDAO.finalizeParticipants] pass, tallied into [FinalizationResult]. */
private enum class FinalizationOutcome { GRANTED, REVOKED, UNCHANGED }

class ChallengeProgressDAO : IChallengeProgressDAO {

    override suspend fun evaluatePostContribution(
        challengeId: UUID,
        userId: UUID,
        postId: UUID,
        carModelId: UUID,
        postCreatedAt: Instant,
    ): ContributionEvaluation = retrySerializationConflicts {
        transaction {
            val challengeRow = ChallengeTable
                .select(
                    ChallengeTable.targetFamilyId,
                    ChallengeTable.startsAt,
                    ChallengeTable.endsAt,
                )
                .where { ChallengeTable.id eq challengeId }
                .singleOrNull()
                ?: return@transaction ContributionEvaluation(eligible = false)

            val startsAt = challengeRow[ChallengeTable.startsAt].toInstant()
            val endsAt = challengeRow[ChallengeTable.endsAt].toInstant()
            val withinWindow = !postCreatedAt.isBefore(startsAt) && postCreatedAt.isBefore(endsAt)

            val modelFamilyId = CarModelTable
                .select(CarModelTable.familyId)
                .where { CarModelTable.id eq carModelId }
                .singleOrNull()
                ?.get(CarModelTable.familyId)

            val modelMatches = modelFamilyId != null && modelFamilyId == challengeRow[ChallengeTable.targetFamilyId]

            if (!withinWindow || !modelMatches) {
                return@transaction ContributionEvaluation(eligible = false)
            }

            // Upsert-then-lock: the serialization point for this (challenge, user), so two
            // concurrent posts from the same user can't both recount challenge_contributions and
            // overwrite contribution_count with a stale value. Reward decisions no longer happen
            // on this path (see finalizeParticipants, plan §9-C7) — this lock now exists purely
            // to keep the cached counter consistent.
            //
            // Uses upsert (ON CONFLICT DO UPDATE) rather than insertIgnore (ON CONFLICT DO
            // NOTHING) deliberately: under REPEATABLE READ, when two transactions race to create
            // the first participant row for this (challenge, user), DO NOTHING does not raise
            // 40001 on the loser, so it would silently proceed on a stale snapshot that doesn't
            // yet contain the winner's committed row, and the subsequent SELECT ... FOR UPDATE
            // would find nothing. DO UPDATE touches the row on conflict, which does raise 40001
            // in that race, routing the loser through retrySerializationConflicts instead.
            ChallengeParticipantTable.upsert(
                ChallengeParticipantTable.challengeId,
                ChallengeParticipantTable.userId,
            ) {
                it[ChallengeParticipantTable.challengeId] = challengeId
                it[ChallengeParticipantTable.userId] = userId
            }
            ChallengeParticipantTable
                .selectAll()
                .where { (ChallengeParticipantTable.challengeId eq challengeId) and (ChallengeParticipantTable.userId eq userId) }
                .forUpdate()
                .single()

            ChallengeContributionTable.insertIgnore {
                it[ChallengeContributionTable.challengeId] = challengeId
                it[ChallengeContributionTable.userId] = userId
                it[ChallengeContributionTable.postId] = postId
                it[ChallengeContributionTable.carModelId] = carModelId
                it[ChallengeContributionTable.postCreatedAt] = postCreatedAt.atOffset(ZoneOffset.UTC)
            }

            val contributionCount = ChallengeContributionTable
                .select(ChallengeContributionTable.id)
                .where { (ChallengeContributionTable.challengeId eq challengeId) and (ChallengeContributionTable.userId eq userId) }
                .count()
                .toInt()

            val now = Instant.now().atOffset(ZoneOffset.UTC)
            ChallengeParticipantTable.update({
                (ChallengeParticipantTable.challengeId eq challengeId) and (ChallengeParticipantTable.userId eq userId)
            }) {
                it[ChallengeParticipantTable.contributionCount] = contributionCount
                it[ChallengeParticipantTable.updatedAt] = now
            }

            ContributionEvaluation(
                eligible = true,
                contributionCount = contributionCount,
            )
        }
    }

    override suspend fun revokeAllGrantedRewards(challengeId: UUID, reason: LedgerReason): Int {
        val grantedUserIds = transaction {
            ChallengeParticipantTable
                .select(ChallengeParticipantTable.userId)
                .where { (ChallengeParticipantTable.challengeId eq challengeId) and (ChallengeParticipantTable.rewardState eq RewardState.GRANTED) }
                .map { it[ChallengeParticipantTable.userId] }
        }

        var revokedCount = 0
        for (userId in grantedUserIds) {
            val didRevoke = retrySerializationConflicts {
                transaction {
                    val participant = ChallengeParticipantTable
                        .selectAll()
                        .where { (ChallengeParticipantTable.challengeId eq challengeId) and (ChallengeParticipantTable.userId eq userId) }
                        .forUpdate()
                        .single()

                    if (participant[ChallengeParticipantTable.rewardState] != RewardState.GRANTED) {
                        false
                    } else {
                        val rewardPoints = ChallengeTable
                            .select(ChallengeTable.rewardPoints)
                            .where { ChallengeTable.id eq challengeId }
                            .single()[ChallengeTable.rewardPoints]

                        revokeReward(
                            challengeId = challengeId,
                            userId = userId,
                            epoch = participant[ChallengeParticipantTable.awardEpoch],
                            rewardPoints = rewardPoints,
                            reason = reason,
                        )
                        true
                    }
                }
            }
            if (didRevoke) revokedCount++
        }
        return revokedCount
    }

    override suspend fun finalizeParticipants(challengeId: UUID): FinalizationResult {
        val userIds = transaction {
            ChallengeParticipantTable
                .select(ChallengeParticipantTable.userId)
                .where { ChallengeParticipantTable.challengeId eq challengeId }
                .map { it[ChallengeParticipantTable.userId] }
        }

        var grantedCount = 0
        var revokedCount = 0
        for (userId in userIds) {
            val outcome = retrySerializationConflicts {
                transaction {
                    val participant = ChallengeParticipantTable
                        .selectAll()
                        .where { (ChallengeParticipantTable.challengeId eq challengeId) and (ChallengeParticipantTable.userId eq userId) }
                        .forUpdate()
                        .single()

                    val contributionCount = ChallengeContributionTable
                        .select(ChallengeContributionTable.id)
                        .where { (ChallengeContributionTable.challengeId eq challengeId) and (ChallengeContributionTable.userId eq userId) }
                        .count()
                        .toInt()

                    val challengeRow = ChallengeTable
                        .select(ChallengeTable.requiredPosts, ChallengeTable.rewardPoints)
                        .where { ChallengeTable.id eq challengeId }
                        .single()

                    val requiredPosts = challengeRow[ChallengeTable.requiredPosts]
                    val rewardPoints = challengeRow[ChallengeTable.rewardPoints]
                    val currentRewardState = participant[ChallengeParticipantTable.rewardState]
                    val currentAwardEpoch = participant[ChallengeParticipantTable.awardEpoch]
                    val notCompletedBefore = participant[ChallengeParticipantTable.firstCompletedAt] == null
                    val completed = contributionCount >= requiredPosts
                    val now = Instant.now().atOffset(ZoneOffset.UTC)

                    when {
                        completed && currentRewardState == RewardState.NONE -> {
                            grantReward(challengeId, userId, currentAwardEpoch, rewardPoints, now, setFirstCompletedAt = notCompletedBefore)
                            FinalizationOutcome.GRANTED
                        }
                        !completed && currentRewardState == RewardState.GRANTED -> {
                            revokeReward(challengeId, userId, currentAwardEpoch, rewardPoints, LedgerReason.CONTRIBUTION_REMOVED)
                            FinalizationOutcome.REVOKED
                        }
                        else -> FinalizationOutcome.UNCHANGED
                    }
                }
            }
            when (outcome) {
                FinalizationOutcome.GRANTED -> grantedCount++
                FinalizationOutcome.REVOKED -> revokedCount++
                FinalizationOutcome.UNCHANGED -> {}
            }
        }
        return FinalizationResult(grantedCount = grantedCount, revokedCount = revokedCount)
    }

    /**
     * Ledger entry keyed by the current award_epoch, so a retried call for the same grant is
     * absorbed by challenge_reward_ledger's UNIQUE idempotency_key instead of double-applying.
     */
    private fun grantReward(
        challengeId: UUID,
        userId: UUID,
        epoch: Int,
        rewardPoints: Int,
        now: OffsetDateTime,
        setFirstCompletedAt: Boolean,
    ) {
        val idempotencyKey = "chg:$challengeId:usr:$userId:grant:$epoch"

        val currentScore = UserTable
            .select(UserTable.spotScore)
            .where { UserTable.id eq userId }
            .single()[UserTable.spotScore]
        val newScore = currentScore + rewardPoints

        ChallengeRewardLedgerTable.insertIgnore {
            it[ChallengeRewardLedgerTable.userId] = userId
            it[ChallengeRewardLedgerTable.challengeId] = challengeId
            it[ChallengeRewardLedgerTable.kind] = LedgerEntryKind.GRANT
            it[ChallengeRewardLedgerTable.reason] = LedgerReason.THRESHOLD_REACHED
            it[ChallengeRewardLedgerTable.nominalDelta] = rewardPoints
            it[ChallengeRewardLedgerTable.appliedDelta] = rewardPoints
            it[ChallengeRewardLedgerTable.awardEpoch] = epoch
            it[ChallengeRewardLedgerTable.idempotencyKey] = idempotencyKey
        }

        UserTable.update({ UserTable.id eq userId }) {
            it[UserTable.spotScore] = newScore
        }

        ChallengeParticipantTable.update({
            (ChallengeParticipantTable.challengeId eq challengeId) and (ChallengeParticipantTable.userId eq userId)
        }) {
            it[ChallengeParticipantTable.rewardState] = RewardState.GRANTED
            it[ChallengeParticipantTable.lastGrantedAt] = now
            if (setFirstCompletedAt) it[ChallengeParticipantTable.firstCompletedAt] = now
        }
    }

    /**
     * applied_delta is the actual (floored-at-0) change to spot_score, which can be smaller in
     * magnitude than nominal_delta — see the reconciliation invariant in
     * ChallengeRewardLedgerTable's KDoc. Bumps award_epoch so a later re-grant gets a fresh
     * idempotency key instead of colliding with this revoke's key.
     */
    private fun revokeReward(challengeId: UUID, userId: UUID, epoch: Int, rewardPoints: Int, reason: LedgerReason) {
        val idempotencyKey = "chg:$challengeId:usr:$userId:revoke:$epoch"

        val currentScore = UserTable
            .select(UserTable.spotScore)
            .where { UserTable.id eq userId }
            .single()[UserTable.spotScore]
        val newScore = maxOf(0, currentScore - rewardPoints)
        val appliedDelta = newScore - currentScore

        ChallengeRewardLedgerTable.insertIgnore {
            it[ChallengeRewardLedgerTable.userId] = userId
            it[ChallengeRewardLedgerTable.challengeId] = challengeId
            it[ChallengeRewardLedgerTable.kind] = LedgerEntryKind.REVOKE
            it[ChallengeRewardLedgerTable.reason] = reason
            it[ChallengeRewardLedgerTable.nominalDelta] = -rewardPoints
            it[ChallengeRewardLedgerTable.appliedDelta] = appliedDelta
            it[ChallengeRewardLedgerTable.awardEpoch] = epoch
            it[ChallengeRewardLedgerTable.idempotencyKey] = idempotencyKey
        }

        UserTable.update({ UserTable.id eq userId }) {
            it[UserTable.spotScore] = newScore
        }

        ChallengeParticipantTable.update({
            (ChallengeParticipantTable.challengeId eq challengeId) and (ChallengeParticipantTable.userId eq userId)
        }) {
            it[ChallengeParticipantTable.rewardState] = RewardState.NONE
            it[ChallengeParticipantTable.awardEpoch] = epoch + 1
        }
    }

    override fun removeContributionsForPostInCurrentTransaction(
        postId: UUID,
        allowRevoke: (endsAt: Instant) -> Boolean,
    ): List<ContributionReversal> {
        val contributions = ChallengeContributionTable
            .select(ChallengeContributionTable.id, ChallengeContributionTable.challengeId, ChallengeContributionTable.userId)
            .where { ChallengeContributionTable.postId eq postId }
            .map {
                Triple(
                    it[ChallengeContributionTable.id].value,
                    it[ChallengeContributionTable.challengeId],
                    it[ChallengeContributionTable.userId],
                )
            }

        return contributions.map { (contributionId, challengeId, userId) ->
            val participant = ChallengeParticipantTable
                .selectAll()
                .where { (ChallengeParticipantTable.challengeId eq challengeId) and (ChallengeParticipantTable.userId eq userId) }
                .forUpdate()
                .single()

            ChallengeContributionTable.deleteWhere { id eq contributionId }

            val contributionCount = ChallengeContributionTable
                .select(ChallengeContributionTable.id)
                .where { (ChallengeContributionTable.challengeId eq challengeId) and (ChallengeContributionTable.userId eq userId) }
                .count()
                .toInt()

            ChallengeParticipantTable.update({
                (ChallengeParticipantTable.challengeId eq challengeId) and (ChallengeParticipantTable.userId eq userId)
            }) {
                it[ChallengeParticipantTable.contributionCount] = contributionCount
                it[ChallengeParticipantTable.updatedAt] = Instant.now().atOffset(ZoneOffset.UTC)
            }

            val challengeRow = ChallengeTable
                .select(ChallengeTable.requiredPosts, ChallengeTable.rewardPoints, ChallengeTable.endsAt)
                .where { ChallengeTable.id eq challengeId }
                .single()

            val completed = contributionCount >= challengeRow[ChallengeTable.requiredPosts]
            val currentRewardState = participant[ChallengeParticipantTable.rewardState]

            var rewardRevoked = false
            if (!completed && currentRewardState == RewardState.GRANTED && allowRevoke(challengeRow[ChallengeTable.endsAt].toInstant())) {
                revokeReward(
                    challengeId = challengeId,
                    userId = userId,
                    epoch = participant[ChallengeParticipantTable.awardEpoch],
                    rewardPoints = challengeRow[ChallengeTable.rewardPoints],
                    reason = LedgerReason.CONTRIBUTION_REMOVED,
                )
                rewardRevoked = true
            }

            ContributionReversal(
                challengeId = challengeId,
                contributionCount = contributionCount,
                rewardRevoked = rewardRevoked,
            )
        }
    }

    override suspend fun getProgress(challengeId: UUID, userId: UUID): ParticipantProgress? = transaction {
        ChallengeParticipantTable
            .select(ChallengeParticipantTable.contributionCount, ChallengeParticipantTable.rewardState)
            .where { (ChallengeParticipantTable.challengeId eq challengeId) and (ChallengeParticipantTable.userId eq userId) }
            .singleOrNull()
            ?.let {
                ParticipantProgress(
                    contributionCount = it[ChallengeParticipantTable.contributionCount],
                    rewardState = it[ChallengeParticipantTable.rewardState],
                )
            }
    }

    override suspend fun hasContributions(postId: UUID): Boolean = transaction {
        ChallengeContributionTable
            .select(ChallengeContributionTable.id)
            .where { ChallengeContributionTable.postId eq postId }
            .limit(1)
            .any()
    }

    override suspend fun listContributionsForUser(challengeId: UUID, userId: UUID): List<ContributionSummary> = transaction {
        (ChallengeContributionTable innerJoin PostTable)
            .join(CarModelTable, JoinType.LEFT, additionalConstraint = { ChallengeContributionTable.carModelId eq CarModelTable.id })
            .select(
                ChallengeContributionTable.postId,
                ChallengeContributionTable.postCreatedAt,
                PostTable.imageKey,
                CarModelTable.brand,
                CarModelTable.model,
            )
            .where { (ChallengeContributionTable.challengeId eq challengeId) and (ChallengeContributionTable.userId eq userId) }
            .orderBy(ChallengeContributionTable.postCreatedAt to SortOrder.ASC)
            .map {
                ContributionSummary(
                    postId = it[ChallengeContributionTable.postId],
                    createdAt = it[ChallengeContributionTable.postCreatedAt].toInstant(),
                    imageKey = it[PostTable.imageKey],
                    carBrand = it.getOrNull(CarModelTable.brand),
                    carModel = it.getOrNull(CarModelTable.model),
                )
            }
    }
}
