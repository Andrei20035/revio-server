package service

import com.revio.server.features.notification.NotificationPolicyDecision
import com.revio.server.features.notification.NotificationVerdict
import com.revio.server.features.notification.shouldSkipChallengeStartInsteadOfDefer
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Pure unit coverage for shouldSkipChallengeStartInsteadOfDefer — the challenge-relative
 * (not absolute-hours) max-defer threshold from plan §9: skip rather than defer once the
 * quiet-hours target would consume more than 25% of the challenge's own [startsAt, endsAt)
 * window. Same reasoning as DiscoveryEligibilityTest's coverage of shouldSkipInsteadOfDefer.
 */
class ChallengeStartEligibilityTest {

    private val now = OffsetDateTime.parse("2026-06-15T18:30:00Z")

    @Test
    fun `a DISPATCH verdict is never skipped by this check`() {
        val decision = NotificationPolicyDecision(NotificationVerdict.DISPATCH)
        assertFalse(
            shouldSkipChallengeStartInsteadOfDefer(
                decision, now,
                challengeStartsAt = Instant.parse("2026-06-15T00:00:00Z"),
                challengeEndsAt = Instant.parse("2026-06-17T00:00:00Z"),
            ),
        )
    }

    @Test
    fun `a SUPPRESS verdict is left alone (not this function's job)`() {
        val decision = NotificationPolicyDecision(NotificationVerdict.SUPPRESS)
        assertFalse(
            shouldSkipChallengeStartInsteadOfDefer(
                decision, now,
                challengeStartsAt = Instant.parse("2026-06-15T00:00:00Z"),
                challengeEndsAt = Instant.parse("2026-06-17T00:00:00Z"),
            ),
        )
    }

    // ---------- long window extreme: 48h weekend challenge, 25% = 12h ----------

    @Test
    fun `a 48h challenge tolerates an 11h59m defer`() {
        val startsAt = Instant.parse("2026-06-15T00:00:00Z")
        val endsAt = startsAt.plus(Duration.ofHours(48))
        val decision = NotificationPolicyDecision(NotificationVerdict.DEFER, notBefore = now.plus(Duration.ofHours(11).plusMinutes(59)))
        assertFalse(shouldSkipChallengeStartInsteadOfDefer(decision, now, startsAt, endsAt))
    }

    @Test
    fun `a 48h challenge skips a defer just past the 12h threshold`() {
        val startsAt = Instant.parse("2026-06-15T00:00:00Z")
        val endsAt = startsAt.plus(Duration.ofHours(48))
        val decision = NotificationPolicyDecision(NotificationVerdict.DEFER, notBefore = now.plus(Duration.ofHours(12).plusMinutes(1)))
        assertTrue(shouldSkipChallengeStartInsteadOfDefer(decision, now, startsAt, endsAt))
    }

    // ---------- short window extreme: 4h challenge, 25% = 1h ----------

    @Test
    fun `a 4h challenge tolerates a 59m defer`() {
        val startsAt = Instant.parse("2026-06-15T00:00:00Z")
        val endsAt = startsAt.plus(Duration.ofHours(4))
        val decision = NotificationPolicyDecision(NotificationVerdict.DEFER, notBefore = now.plus(Duration.ofMinutes(59)))
        assertFalse(shouldSkipChallengeStartInsteadOfDefer(decision, now, startsAt, endsAt))
    }

    @Test
    fun `a 4h challenge skips any defer past the 1h threshold`() {
        val startsAt = Instant.parse("2026-06-15T00:00:00Z")
        val endsAt = startsAt.plus(Duration.ofHours(4))
        val decision = NotificationPolicyDecision(NotificationVerdict.DEFER, notBefore = now.plus(Duration.ofHours(1).plusMinutes(1)))
        assertTrue(shouldSkipChallengeStartInsteadOfDefer(decision, now, startsAt, endsAt))
    }
}
