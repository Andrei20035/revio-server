package com.revio.server.features.challenge

import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.sum
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Which side of "did the user complete it" a page of [IChallengeHistoryDAO.listUserHistory] is narrowed to. */
enum class ChallengeHistoryFilter {
    COMPLETED,
    NOT_COMPLETED,
}

/** One page row: a challenge the user has a [ChallengeParticipantTable] row for, plus their progress on it. */
data class ChallengeHistoryEntry(
    val challenge: Challenge,
    val progress: ParticipantProgress,
)

/** A user's lifetime challenge participation, for the summary strip in "My Challenges". */
data class ChallengeUserAggregates(
    val joinedCount: Int,
    val completedCount: Int,
    /** Net of every grant/revoke, i.e. `SUM(challenge_reward_ledger.applied_delta)` — see that
     * table's KDoc for why this can be smaller in magnitude than `rewardPoints * completedCount`. */
    val pointsEarned: Int,
    val totalContributions: Int,
)

interface IChallengeHistoryDAO {
    /**
     * Keyset-paginated history of challenges [userId] has participated in — i.e. challenges with
     * a [ChallengeParticipantTable] row for that user, ordered `ends_at DESC, id DESC` (most
     * recently finished first, `id` as tiebreaker for challenges sharing an `ends_at`). A
     * challenge the user has never contributed to is absent, by design: this lists what the user
     * did, not everything that was ever scheduled.
     */
    suspend fun listUserHistory(
        userId: UUID,
        limit: Int,
        cursorEndsAt: Instant?,
        cursorId: UUID?,
        filter: ChallengeHistoryFilter?,
    ): List<ChallengeHistoryEntry>

    /** Lifetime aggregates for [userId] across every challenge they've participated in. */
    suspend fun getUserAggregates(userId: UUID): ChallengeUserAggregates
}

class ChallengeHistoryDAO : IChallengeHistoryDAO {

    override suspend fun listUserHistory(
        userId: UUID,
        limit: Int,
        cursorEndsAt: Instant?,
        cursorId: UUID?,
        filter: ChallengeHistoryFilter?,
    ): List<ChallengeHistoryEntry> = transaction {
        val query = (ChallengeTable innerJoin ChallengeParticipantTable)
            .select(
                ChallengeTable.id,
                ChallengeTable.title,
                ChallengeTable.description,
                ChallengeTable.targetFamilyId,
                ChallengeTable.requiredPosts,
                ChallengeTable.rewardPoints,
                ChallengeTable.startsAt,
                ChallengeTable.endsAt,
                ChallengeTable.adminTimezone,
                ChallengeTable.status,
                ChallengeTable.createdBy,
                ChallengeTable.createdAt,
                ChallengeTable.updatedAt,
                ChallengeTable.publishedAt,
                ChallengeTable.cancelledAt,
                ChallengeTable.finalizedAt,
                ChallengeParticipantTable.contributionCount,
                ChallengeParticipantTable.rewardState,
            )
            .where { ChallengeParticipantTable.userId eq userId }

        if (cursorEndsAt != null && cursorId != null) {
            val cursorOffset = cursorEndsAt.atOffset(ZoneOffset.UTC)
            query.andWhere {
                (ChallengeTable.endsAt less cursorOffset) or
                    ((ChallengeTable.endsAt eq cursorOffset) and (ChallengeTable.id less cursorId))
            }
        }

        if (filter != null) {
            query.andWhere {
                when (filter) {
                    ChallengeHistoryFilter.COMPLETED -> ChallengeParticipantTable.rewardState eq RewardState.GRANTED
                    ChallengeHistoryFilter.NOT_COMPLETED -> ChallengeParticipantTable.rewardState neq RewardState.GRANTED
                }
            }
        }

        query
            .orderBy(ChallengeTable.endsAt to SortOrder.DESC, ChallengeTable.id to SortOrder.DESC)
            .limit(limit)
            .map { row ->
                ChallengeHistoryEntry(
                    challenge = row.toChallenge(),
                    progress = ParticipantProgress(
                        contributionCount = row[ChallengeParticipantTable.contributionCount],
                        rewardState = row[ChallengeParticipantTable.rewardState],
                    ),
                )
            }
    }

    override suspend fun getUserAggregates(userId: UUID): ChallengeUserAggregates = transaction {
        val joinedCount = ChallengeParticipantTable
            .selectAll()
            .where { ChallengeParticipantTable.userId eq userId }
            .count()
            .toInt()

        val completedCount = ChallengeParticipantTable
            .selectAll()
            .where { (ChallengeParticipantTable.userId eq userId) and (ChallengeParticipantTable.rewardState eq RewardState.GRANTED) }
            .count()
            .toInt()

        val pointsEarnedSum = ChallengeRewardLedgerTable.appliedDelta.sum()
        val pointsEarned = ChallengeRewardLedgerTable
            .select(pointsEarnedSum)
            .where { ChallengeRewardLedgerTable.userId eq userId }
            .single()[pointsEarnedSum] ?: 0

        val totalContributions = ChallengeContributionTable
            .selectAll()
            .where { ChallengeContributionTable.userId eq userId }
            .count()
            .toInt()

        ChallengeUserAggregates(
            joinedCount = joinedCount,
            completedCount = completedCount,
            pointsEarned = pointsEarned,
            totalContributions = totalContributions,
        )
    }
}
