package service

import com.revio.server.config.NotificationMetrics
import com.revio.server.features.notification.INotificationOutboxDAO
import com.revio.server.features.notification.NotificationAlert
import com.revio.server.features.notification.NotificationAlertEvaluatorLoop
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * NotificationAlertEvaluatorLoop (step 4.4) — the composition/delta logic that finally calls
 * evaluateNotificationAlerts. [outboxDao] is a lightweight mock (only `countQueued` matters here,
 * no advisory lock/SQL of its own to exercise); [NotificationMetrics] is the real process-wide
 * singleton, so every assertion here reads a *delta* around two consecutive ticks, same
 * reasoning as NotificationMetricsTest — not an absolute count, which other tests in the same
 * JVM run may also have touched.
 */
class NotificationAlertEvaluatorLoopTest {

    private fun daoReturning(queueDepth: Long): INotificationOutboxDAO =
        mockk<INotificationOutboxDAO>().apply {
            coEvery { countQueued() } returns queueDepth
        }

    private class MutableClock(private var current: Instant) {
        fun now(): Instant = current
        fun advance(duration: Duration) {
            current = current.plus(duration)
        }
    }

    @Test
    fun `queue depth over the threshold does not alert on the first tick, only once sustained`() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-01-15T12:00:00Z"))
        val captured = mutableListOf<Set<NotificationAlert>>()
        val loop = NotificationAlertEvaluatorLoop(
            outboxDao = daoReturning(queueDepth = 2000),
            clock = clock::now,
            onAlerts = { captured.add(it) },
        )

        loop.tick()
        assertTrue(captured.isEmpty(), "must not alert before the queue has been sustained above threshold")

        clock.advance(Duration.ofMinutes(6))
        loop.tick()

        assertEquals(1, captured.size)
        assertTrue(NotificationAlert.QUEUE_DEPTH_HIGH in captured.single())
    }

    @Test
    fun `queue depth dropping back below the threshold resets the sustained clock`() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-01-15T12:00:00Z"))
        val captured = mutableListOf<Set<NotificationAlert>>()
        var queueDepth = 2000L
        val loop = NotificationAlertEvaluatorLoop(
            outboxDao = mockk<INotificationOutboxDAO>().apply {
                coEvery { countQueued() } answers { queueDepth }
            },
            clock = clock::now,
            onAlerts = { captured.add(it) },
        )

        loop.tick()
        clock.advance(Duration.ofMinutes(3))
        queueDepth = 0
        loop.tick() // back under threshold — resets "sustained since"
        queueDepth = 2000
        clock.advance(Duration.ofMinutes(3))
        loop.tick() // only 3 min into the new sustained window, not yet 5

        assertTrue(captured.none { NotificationAlert.QUEUE_DEPTH_HIGH in it }, "the sustained window must restart after dropping below threshold")
    }

    @Test
    fun `no alerts fire, and onAlerts is never called, when nothing is wrong`() = runBlocking {
        val captured = mutableListOf<Set<NotificationAlert>>()
        val loop = NotificationAlertEvaluatorLoop(
            outboxDao = daoReturning(queueDepth = 0),
            onAlerts = { captured.add(it) },
        )

        loop.tick()
        loop.tick()

        assertTrue(captured.isEmpty())
    }

    @Test
    fun `a dead-rate spike between two ticks fires DEAD_RATE_HIGH`() = runBlocking {
        val captured = mutableListOf<Set<NotificationAlert>>()
        val loop = NotificationAlertEvaluatorLoop(
            outboxDao = daoReturning(queueDepth = 0),
            onAlerts = { captured.add(it) },
        )

        loop.tick() // establishes the baseline snapshot for the delta below
        repeat(10) { NotificationMetrics.outboxAccepted(ageMs = 100) }
        repeat(2) { NotificationMetrics.outboxDead() } // 2/12 ≈ 16.7% > 5% threshold
        loop.tick()

        assertTrue(captured.isNotEmpty())
        assertTrue(NotificationAlert.DEAD_RATE_HIGH in captured.last())
    }

    @Test
    fun `a dispatcher that stops accepting for 30+ min fires DISPATCHER_STALLED while the queue is non-empty`() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-01-15T12:00:00Z"))
        val captured = mutableListOf<Set<NotificationAlert>>()
        val loop = NotificationAlertEvaluatorLoop(
            outboxDao = daoReturning(queueDepth = 5),
            clock = clock::now,
            onAlerts = { captured.add(it) },
        )

        loop.tick() // no accepted send observed yet
        clock.advance(Duration.ofMinutes(31))
        loop.tick()

        assertTrue(NotificationAlert.DISPATCHER_STALLED in captured.last())
    }

    @Test
    fun `an exception thrown mid-tick is swallowed and does not stop subsequent iterations`() = runBlocking {
        val callCount = AtomicInteger(0)
        val loop = NotificationAlertEvaluatorLoop(
            outboxDao = mockk<INotificationOutboxDAO>().apply {
                coEvery { countQueued() } answers {
                    val n = callCount.incrementAndGet()
                    if (n == 1) throw RuntimeException("boom")
                    0
                }
            },
            intervalMillis = 10L,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        loop.start(scope)
        withTimeout(5_000) {
            while (callCount.get() < 3) delay(10)
        }
        loop.stop()
        scope.cancel()

        assertTrue(callCount.get() >= 3, "loop should keep iterating past the failing first tick")
    }

    @Test
    fun `stop cancels the loop's job cleanly`() = runBlocking {
        val loop = NotificationAlertEvaluatorLoop(
            outboxDao = daoReturning(queueDepth = 0),
            intervalMillis = 10L,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        loop.start(scope)
        delay(50)
        assertTrue(loop.isRunning)

        loop.stop()
        delay(50)
        assertFalse(loop.isRunning)

        scope.cancel()
    }
}
