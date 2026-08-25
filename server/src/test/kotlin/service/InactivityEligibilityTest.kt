package service

import com.revio.server.features.notification.dedupeKeyFor
import com.revio.server.features.notification.InactivityMilestone
import com.revio.server.features.notification.copyFor
import com.revio.server.features.notification.daysSinceLastPost
import com.revio.server.features.notification.lastAppOpenTooRecent
import com.revio.server.features.notification.milestoneFor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Pure unit coverage for the inactivity job's day-boundary/copy/dedupe logic (plan §8.4 / §18
 * step 6.4). No DB — same reasoning as DiscoveryEligibilityTest's coverage.
 */
class InactivityEligibilityTest {

    // ---------- day-boundary milestone ----------

    @Test
    fun `milestoneFor - day 2 matches nothing`() {
        assertNull(milestoneFor(2))
    }

    @Test
    fun `milestoneFor - day 3 is DAY_3`() {
        assertEquals(InactivityMilestone.DAY_3, milestoneFor(3))
    }

    @Test
    fun `milestoneFor - day 4, 5, 6 match nothing`() {
        assertNull(milestoneFor(4))
        assertNull(milestoneFor(5))
        assertNull(milestoneFor(6))
    }

    @Test
    fun `milestoneFor - day 7 is DAY_7`() {
        assertEquals(InactivityMilestone.DAY_7, milestoneFor(7))
    }

    @Test
    fun `milestoneFor - day 8 and well beyond match nothing - silence past day 7 (D8)`() {
        assertNull(milestoneFor(8))
        assertNull(milestoneFor(30))
        assertNull(milestoneFor(365))
    }

    @Test
    fun `daysSinceLastPost computes local calendar days between two dates`() {
        val lastPost = LocalDate.of(2026, 6, 10)
        assertEquals(3L, daysSinceLastPost(lastPost, LocalDate.of(2026, 6, 13)))
        assertEquals(7L, daysSinceLastPost(lastPost, LocalDate.of(2026, 6, 17)))
        assertEquals(0L, daysSinceLastPost(lastPost, lastPost))
    }

    // ---------- last_app_open > 24h gate ----------

    @Test
    fun `lastAppOpenTooRecent - opened 1 hour ago is too recent (suppresses)`() {
        val now = Instant.parse("2026-06-15T12:00:00Z")
        assertTrue(lastAppOpenTooRecent(now.minusSeconds(3600), now))
    }

    @Test
    fun `lastAppOpenTooRecent - opened exactly 24h ago is no longer too recent`() {
        val now = Instant.parse("2026-06-15T12:00:00Z")
        assertFalse(lastAppOpenTooRecent(now.minus(Duration.ofHours(24)), now))
    }

    @Test
    fun `lastAppOpenTooRecent - opened 25h ago is not too recent`() {
        val now = Instant.parse("2026-06-15T12:00:00Z")
        assertFalse(lastAppOpenTooRecent(now.minus(Duration.ofHours(25)), now))
    }

    @Test
    fun `lastAppOpenTooRecent - never opened (null) is never too recent`() {
        assertFalse(lastAppOpenTooRecent(null, Instant.now()))
    }

    // ---------- copy ----------

    @Test
    fun `copyFor DAY_3 matches the kept plan copy`() {
        val (title, body) = copyFor(InactivityMilestone.DAY_3)
        assertEquals("The leaderboard keeps moving", title)
        assertEquals("One good spot could put you back in the race.", body)
    }

    @Test
    fun `copyFor DAY_7 uses the generic variant - leaderboard-conditioned copy is step 6_5`() {
        val (title, body) = copyFor(InactivityMilestone.DAY_7)
        assertEquals("Your spots have been quiet", title)
        assertEquals("The community's been busy — see what you missed.", body)
    }

    // ---------- dedupe key (D8) ----------

    @Test
    fun `dedupeKeyFor DAY_3 is tied to the last post's local date`() {
        assertEquals("inactivity:d3:2026-06-10", dedupeKeyFor(InactivityMilestone.DAY_3, LocalDate.of(2026, 6, 10)))
    }

    @Test
    fun `dedupeKeyFor DAY_7 is tied to the last post's local date`() {
        assertEquals("inactivity:d7:2026-06-10", dedupeKeyFor(InactivityMilestone.DAY_7, LocalDate.of(2026, 6, 10)))
    }

    @Test
    fun `dedupeKeyFor - a new post (different lastPostDate) always yields a different key`() {
        val key1 = dedupeKeyFor(InactivityMilestone.DAY_3, LocalDate.of(2026, 6, 10))
        val key2 = dedupeKeyFor(InactivityMilestone.DAY_3, LocalDate.of(2026, 6, 20))
        assertTrue(key1 != key2)
    }
}
