package service

import com.revio.server.features.notification.RetryDecision
import com.revio.server.features.notification.nextRetryDecision
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pure backoff/dead-letter decision logic (plan §14/§18 step 3.6), unit-tested directly like
 * PushDispatchServiceTest.classifyResponse — no jitter randomness (identity jitter) so exact
 * schedule values are asserted.
 */
class NotificationOutboxRetryPolicyTest {

    private val noJitter: (Long) -> Long = { it }

    @Test
    fun `first failure schedules a 30s retry`() {
        val decision = nextRetryDecision(attemptsAfterFailure = 1, jitter = noJitter)
        assertEquals(RetryDecision.Retry(30), decision)
    }

    @Test
    fun `second failure schedules a 2m retry`() {
        val decision = nextRetryDecision(attemptsAfterFailure = 2, jitter = noJitter)
        assertEquals(RetryDecision.Retry(120), decision)
    }

    @Test
    fun `third failure schedules an 8m retry`() {
        val decision = nextRetryDecision(attemptsAfterFailure = 3, jitter = noJitter)
        assertEquals(RetryDecision.Retry(480), decision)
    }

    @Test
    fun `fourth failure schedules a 30m retry`() {
        val decision = nextRetryDecision(attemptsAfterFailure = 4, jitter = noJitter)
        assertEquals(RetryDecision.Retry(1800), decision)
    }

    @Test
    fun `fifth failure schedules a 2h retry`() {
        val decision = nextRetryDecision(attemptsAfterFailure = 5, jitter = noJitter)
        assertEquals(RetryDecision.Retry(7200), decision)
    }

    @Test
    fun `sixth failure (the retry after the 2h wait also failing) gives up`() {
        val decision = nextRetryDecision(attemptsAfterFailure = 6, jitter = noJitter)
        assertEquals(RetryDecision.Dead, decision)
    }

    @Test
    fun `default jitter never returns less than the base delay`() {
        repeat(50) {
            val decision = nextRetryDecision(attemptsAfterFailure = 1) as RetryDecision.Retry
            assertEquals(true, decision.delaySeconds >= 30)
        }
    }
}
