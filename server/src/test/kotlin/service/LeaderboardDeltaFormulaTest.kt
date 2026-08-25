package service

import com.revio.server.features.leaderboard.pointsToGuaranteeMoveUp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Pure unit coverage for pointsToGuaranteeMoveUp — the corrected leaderboard-delta formula from
 * plan §9, rendered per plan §18 step 6.1. No DB: same reasoning as
 * NotificationCommentCopyTest's renderCommentCopy coverage. Covers all 7 cases from §9's table.
 */
class LeaderboardDeltaFormulaTest {

    // Case 1: rank 12, score 40; rank 11 has 41 (S_A > S, not tied) -> naive S_A-S=1 is wrong,
    // correct delta is 2 (reaching 41 only ties; 42 is needed to strictly pass).
    @Test
    fun `case 1 - next score is 1 higher, not tied - delta is 2, not the naive 1`() {
        assertEquals(2, pointsToGuaranteeMoveUp(rank = 12, myScore = 40, scoreImmediatelyAhead = 41))
    }

    // Case 2: rank 12, score 40; ranks 8-11 are also 40 (S_A == S, a tie group ahead) -> delta is
    // exactly 1, since any gain breaks the tie in the user's favor.
    @Test
    fun `case 2 - tied with the score immediately ahead - delta is exactly 1`() {
        assertEquals(1, pointsToGuaranteeMoveUp(rank = 12, myScore = 40, scoreImmediatelyAhead = 40))
    }

    // Case 3: rank 12, score 40; next distinct score is 45 -> naive S_A-S=5 is wrong, correct
    // delta is 6.
    @Test
    fun `case 3 - next distinct score is 5 higher - delta is 6, not the naive 5`() {
        assertEquals(6, pointsToGuaranteeMoveUp(rank = 12, myScore = 40, scoreImmediatelyAhead = 45))
    }

    // Case 4: user is rank 1 -> nothing ahead to guarantee passing; delta is undefined (null).
    @Test
    fun `case 4 - already rank 1 - delta is null`() {
        assertNull(pointsToGuaranteeMoveUp(rank = 1, myScore = 999, scoreImmediatelyAhead = null))
    }

    // Case 5: user doesn't appear on the leaderboard at all -> getUserRank's Int.MAX_VALUE
    // sentinel; a literal #2147483647 would be a visible bug, so this must also be null.
    @Test
    fun `case 5 - user not found (Int_MAX_VALUE sentinel) - delta is null`() {
        assertNull(pointsToGuaranteeMoveUp(rank = Int.MAX_VALUE, myScore = 0, scoreImmediatelyAhead = null))
    }

    // Case 6: a single camera spot (+10 points, CAMERA_POINTS) exceeds the computed delta ->
    // the formula itself doesn't change; this just confirms delta stays a small, correct number
    // regardless of how many points a single spot happens to be worth.
    @Test
    fun `case 6 - a delta smaller than a single spot's points is still computed correctly`() {
        assertEquals(2, pointsToGuaranteeMoveUp(rank = 5, myScore = 100, scoreImmediatelyAhead = 101))
    }

    // Case 7: delta must always be computed from live/current scores, never a stale snapshot —
    // this pure function has no notion of "when" at all, so it can only ever see the score it
    // was given; recency is the caller's (LeaderboardDeltaService's) responsibility by always
    // querying live via ILeaderboardDeltaDAO, never caching.
    @Test
    fun `case 7 - the formula is a pure function of its inputs, with no hidden staleness`() {
        val fromOldScore = pointsToGuaranteeMoveUp(rank = 3, myScore = 50, scoreImmediatelyAhead = 55)
        val fromNewScore = pointsToGuaranteeMoveUp(rank = 3, myScore = 55, scoreImmediatelyAhead = 55)
        assertEquals(6, fromOldScore)
        assertEquals(1, fromNewScore)
    }

    @Test
    fun `rank 2 with a distant leader behaves like any other non-tied case`() {
        assertEquals(11, pointsToGuaranteeMoveUp(rank = 2, myScore = 89, scoreImmediatelyAhead = 99))
    }

    @Test
    fun `defensive - a null score-ahead at a rank greater than 1 returns null rather than crashing`() {
        assertNull(pointsToGuaranteeMoveUp(rank = 5, myScore = 10, scoreImmediatelyAhead = null))
    }
}
