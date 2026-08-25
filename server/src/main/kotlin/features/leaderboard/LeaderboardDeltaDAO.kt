package com.revio.server.features.leaderboard

import com.revio.server.features.user.UserTable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/**
 * One user's row from [ILeaderboardDeltaDAO.batchRankAndDeltaInputs] — everything
 * [LeaderboardDeltaService] needs to compute that user's [LeaderboardDelta] without a further
 * per-user query (plan §18, step 6.1's batch acceptance criterion).
 */
data class BatchDeltaRow(
    val userId: UUID,
    val rank: Int,
    val spotScore: Int,
    /** S_A — the score of the user immediately ahead in LEADERBOARD_ORDER, or null at rank 1. */
    val scoreImmediatelyAhead: Int?,
    /** |{ v : v.score == spotScore && v.id < userId }| — see [LeaderboardDelta.placesGainedWithOnePoint]. */
    val tiedAndRankedAheadCount: Int,
)

interface ILeaderboardDeltaDAO {
    /**
     * S_A from plan §9: the score of the user immediately ahead of (myScore, myId) in
     * [LEADERBOARD_ORDER] — either the next higher score, or (on a tie) the next user whose id
     * sorts before [myId]. Null if nobody is ahead (already rank 1).
     */
    suspend fun findScoreImmediatelyAhead(myScore: Int, myId: UUID): Int?

    /**
     * `|{ v : v.score == myScore && v.id < myId }|` (plan §9) — how many users currently tied
     * with [myScore] rank ahead of [myId] purely by the UUID tie-break, i.e. exactly how many
     * places a single +1 point would let [myId] jump.
     */
    suspend fun countTiedAndRankedAhead(myScore: Int, myId: UUID): Int

    /**
     * Every user's rank, score, score-immediately-ahead, and tied-ahead-count in one single-pass
     * query over `users` (plan §18, step 6.1) — the batch counterpart to calling
     * [findScoreImmediatelyAhead]/[countTiedAndRankedAhead] once per user. Reuses the same
     * `(spot_score DESC, id ASC)` ordering as [LEADERBOARD_ORDER] and the index it's built on
     * (V33__users_spot_score_index.sql).
     */
    suspend fun batchRankAndDeltaInputs(): List<BatchDeltaRow>
}

class LeaderboardDeltaDAO : ILeaderboardDeltaDAO {

    override suspend fun findScoreImmediatelyAhead(myScore: Int, myId: UUID): Int? = transaction {
        UserTable
            .select(UserTable.spotScore)
            .where {
                (UserTable.spotScore greater myScore) or
                    ((UserTable.spotScore eq myScore) and (UserTable.id less myId))
            }
            .orderBy(UserTable.spotScore to SortOrder.ASC, UserTable.id to SortOrder.DESC)
            .limit(1)
            .map { it[UserTable.spotScore] }
            .singleOrNull()
    }

    override suspend fun countTiedAndRankedAhead(myScore: Int, myId: UUID): Int = transaction {
        UserTable
            .select(UserTable.id)
            .where { (UserTable.spotScore eq myScore) and (UserTable.id less myId) }
            .count()
            .toInt()
    }

    /**
     * Raw SQL rather than the Exposed DSL: `ROW_NUMBER`/`LAG`/a windowed `COUNT` aren't
     * expressible through it in this Exposed version. Still exactly one query — the acceptance
     * criterion ("o singură interogare pentru toți userii") is about round-trips to Postgres, not
     * about which API issues them.
     */
    override suspend fun batchRankAndDeltaInputs(): List<BatchDeltaRow> = transaction {
        exec(
            """
            SELECT id, spot_score,
                   ROW_NUMBER() OVER (ORDER BY spot_score DESC, id ASC) AS rnk,
                   LAG(spot_score) OVER (ORDER BY spot_score DESC, id ASC) AS score_ahead,
                   COUNT(*) OVER (
                       PARTITION BY spot_score
                       ORDER BY id ASC
                       ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING
                   ) AS tied_ahead
            FROM users
            """.trimIndent(),
        ) { rs ->
            val rows = mutableListOf<BatchDeltaRow>()
            while (rs.next()) {
                // Column order matters here: wasNull() reflects only the *last* getXxx call, so
                // score_ahead's nullability must be captured immediately, before any other
                // column on this row is read.
                val userId = rs.getObject("id") as UUID
                val rank = rs.getInt("rnk")
                val spotScore = rs.getInt("spot_score")
                val scoreAheadRaw = rs.getInt("score_ahead")
                val scoreAhead = if (rs.wasNull()) null else scoreAheadRaw
                val tiedAhead = rs.getInt("tied_ahead")
                rows += BatchDeltaRow(userId, rank, spotScore, scoreAhead, tiedAhead)
            }
            rows
        } ?: emptyList()
    }
}
