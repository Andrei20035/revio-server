package service

import com.revio.server.features.notification.deltaHasDrifted
import com.revio.server.features.notification.renderDay7Copy
import com.revio.server.features.notification.renderDay7CopyAtEnqueue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure unit coverage for the day-7 leaderboard-conditioned copy (plan §9 / §18, step 6.5):
 * rank 1 / missing user / drifted delta all fall back to generic copy — never a number — and
 * delta recomputed at dispatch (not baked in at enqueue) drives the actual rendered text.
 */
class InactivityLeaderboardCopyTest {

    // ---------- deltaHasDrifted ----------

    @Test
    fun `no drift when the delta is unchanged`() {
        assertFalse(deltaHasDrifted(oldDelta = 10, newDelta = 10))
    }

    @Test
    fun `a small change within 30% is not drift`() {
        assertFalse(deltaHasDrifted(oldDelta = 10, newDelta = 13)) // 30% exactly, not over
    }

    @Test
    fun `a change over 30% is drift`() {
        assertTrue(deltaHasDrifted(oldDelta = 10, newDelta = 14))
    }

    @Test
    fun `either side null always counts as drifted`() {
        assertTrue(deltaHasDrifted(oldDelta = null, newDelta = 10))
        assertTrue(deltaHasDrifted(oldDelta = 10, newDelta = null))
        assertTrue(deltaHasDrifted(oldDelta = null, newDelta = null))
    }

    @Test
    fun `old delta of 0 drifts on any nonzero new delta`() {
        assertTrue(deltaHasDrifted(oldDelta = 0, newDelta = 1))
        assertFalse(deltaHasDrifted(oldDelta = 0, newDelta = 0))
    }

    // ---------- renderDay7Copy: the three "always generic, never a number" cases ----------

    @Test
    fun `rank Int_MAX_VALUE (missing user) always produces generic copy`() {
        val (title, body) = renderDay7Copy(rank = Int.MAX_VALUE, currentDelta = 3, oldEnqueuedDelta = 3)
        assertEquals("Your spots have been quiet", title)
        assertEquals("The community's been busy — see what you missed.", body)
    }

    @Test
    fun `rank 1 always produces the rank-1 copy, no number`() {
        val (title, body) = renderDay7Copy(rank = 1, currentDelta = null, oldEnqueuedDelta = null)
        assertEquals("You're still holding #1", title)
        assertEquals("One more spot keeps it that way.", body)
        assertFalse(body.any { it.isDigit() })
    }

    @Test
    fun `a drifted delta falls back to generic copy, no number, even at a real rank`() {
        val (title, body) = renderDay7Copy(rank = 5, currentDelta = 20, oldEnqueuedDelta = 5)
        assertEquals("Your spots have been quiet", title)
        assertFalse(body.any { it.isDigit() })
    }

    // ---------- renderDay7Copy: the numeric branches, when not drifted ----------

    @Test
    fun `rank greater than 1, delta of 10 or less - close-to-moving-up copy naming rank-1`() {
        val (title, body) = renderDay7Copy(rank = 5, currentDelta = 10, oldEnqueuedDelta = 10)
        assertEquals("You're close to moving up", title)
        assertEquals("Your next spot could put you past #4.", body)
    }

    @Test
    fun `rank greater than 1, delta over 10 - board-moved-without-you copy, no number`() {
        val (title, body) = renderDay7Copy(rank = 5, currentDelta = 11, oldEnqueuedDelta = 11)
        assertEquals("The board moved without you", title)
        assertEquals("Your next spot starts closing the gap.", body)
        assertFalse(body.any { it.isDigit() })
    }

    // ---------- renderDay7CopyAtEnqueue: first computation is never "drifted" from itself ----------

    @Test
    fun `renderDay7CopyAtEnqueue renders the numeric copy on its very first computation`() {
        val (title, _) = renderDay7CopyAtEnqueue(rank = 3, delta = 4)
        assertEquals("You're close to moving up", title)
    }

    @Test
    fun `renderDay7CopyAtEnqueue with a null delta (defensive) falls back to generic`() {
        val (title, _) = renderDay7CopyAtEnqueue(rank = 3, delta = null)
        assertEquals("Your spots have been quiet", title)
    }
}
