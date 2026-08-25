package service

import com.revio.server.features.notification.NotificationPolicyDecision
import com.revio.server.features.notification.NotificationVerdict
import com.revio.server.features.notification.feedOpenedRecently
import com.revio.server.features.notification.hasEnoughNewContent
import com.revio.server.features.notification.isAccountOldEnough
import com.revio.server.features.notification.isUnderWeeklyCap
import com.revio.server.features.notification.shouldSkipInsteadOfDefer
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Pure unit coverage for the discovery job's eligibility checks (plan §8.3 / §18 step 6.3). No
 * DB — same reasoning as NotificationPolicyServiceTest's coverage of the quiet-hours decision
 * this reuses.
 */
class DiscoveryEligibilityTest {

    // ---------- content threshold ----------

    @Test
    fun `hasEnoughNewContent - below the 5-spot threshold is not eligible`() {
        assertFalse(hasEnoughNewContent(4))
    }

    @Test
    fun `hasEnoughNewContent - exactly 5 spots is eligible`() {
        assertTrue(hasEnoughNewContent(5))
    }

    @Test
    fun `hasEnoughNewContent - well above the threshold is eligible`() {
        assertTrue(hasEnoughNewContent(24))
    }

    // ---------- account age ----------

    @Test
    fun `isAccountOldEnough - a brand new account is not old enough`() {
        val now = Instant.parse("2026-06-15T12:00:00Z")
        assertFalse(isAccountOldEnough(accountCreatedAt = now.minusSeconds(3600), now = now))
    }

    @Test
    fun `isAccountOldEnough - exactly 3 days old is old enough`() {
        val now = Instant.parse("2026-06-15T12:00:00Z")
        assertTrue(isAccountOldEnough(accountCreatedAt = now.minus(java.time.Duration.ofDays(3)), now = now))
    }

    // ---------- feed-open 12h gate ----------

    @Test
    fun `feedOpenedRecently - never opened the feed is not recent`() {
        assertFalse(feedOpenedRecently(lastFeedOpenAt = null, now = Instant.now()))
    }

    @Test
    fun `feedOpenedRecently - opened 1 hour ago is recent (within the 12h gate)`() {
        val now = Instant.parse("2026-06-15T12:00:00Z")
        assertTrue(feedOpenedRecently(lastFeedOpenAt = now.minusSeconds(3600), now = now))
    }

    @Test
    fun `feedOpenedRecently - opened 13 hours ago is not recent`() {
        val now = Instant.parse("2026-06-15T12:00:00Z")
        assertFalse(feedOpenedRecently(lastFeedOpenAt = now.minus(java.time.Duration.ofHours(13)), now = now))
    }

    // ---------- weekly cap ----------

    @Test
    fun `isUnderWeeklyCap - 0 or 1 or 2 sent this week is under the cap of 3`() {
        assertTrue(isUnderWeeklyCap(0))
        assertTrue(isUnderWeeklyCap(2))
    }

    @Test
    fun `isUnderWeeklyCap - 3 already sent this week is at the cap, not under it`() {
        assertFalse(isUnderWeeklyCap(3))
    }

    // ---------- quiet hours defer-vs-skip (plan §8.3's "slot mutat cu >6h -> skip complet") ----------

    @Test
    fun `shouldSkipInsteadOfDefer - a DISPATCH verdict is never skipped by this check`() {
        val now = OffsetDateTime.parse("2026-06-15T18:30:00Z")
        assertFalse(shouldSkipInsteadOfDefer(NotificationPolicyDecision(NotificationVerdict.DISPATCH), now))
    }

    @Test
    fun `shouldSkipInsteadOfDefer - a SUPPRESS verdict is left alone (not this function's job)`() {
        val now = OffsetDateTime.parse("2026-06-15T18:30:00Z")
        assertFalse(shouldSkipInsteadOfDefer(NotificationPolicyDecision(NotificationVerdict.SUPPRESS), now))
    }

    @Test
    fun `shouldSkipInsteadOfDefer - deferred exactly 6 hours out still delivers`() {
        val now = OffsetDateTime.parse("2026-06-15T18:30:00Z")
        val decision = NotificationPolicyDecision(NotificationVerdict.DEFER, notBefore = now.plusHours(6))
        assertFalse(shouldSkipInsteadOfDefer(decision, now))
    }

    @Test
    fun `shouldSkipInsteadOfDefer - deferred more than 6 hours out is skipped, not delivered late`() {
        val now = OffsetDateTime.parse("2026-06-15T18:30:00Z")
        val decision = NotificationPolicyDecision(NotificationVerdict.DEFER, notBefore = now.plusHours(6).plusMinutes(1))
        assertTrue(shouldSkipInsteadOfDefer(decision, now))
    }

    @Test
    fun `shouldSkipInsteadOfDefer - deferred to the next morning (12h+ out) is skipped`() {
        val now = OffsetDateTime.parse("2026-06-15T22:00:00Z")
        val decision = NotificationPolicyDecision(NotificationVerdict.DEFER, notBefore = now.plusHours(10))
        assertTrue(shouldSkipInsteadOfDefer(decision, now))
    }
}
