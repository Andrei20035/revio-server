package service

import com.revio.server.features.challenge.PostgresAdvisoryLock
import com.revio.server.features.notification.NotificationOutboxDAO
import com.revio.server.features.notification.PushDispatcherLoop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import testutils.TestDatabaseFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * PushDispatcherLoop's own scheduling mechanics (plan §18, step 3.5) — the advisory lock actually
 * needs a live Postgres connection (see PostgresAdvisoryLock), so this uses the real
 * Testcontainers instance via TestDatabaseFactory, same as ChallengeFinalizationServiceConcurrencyTest.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PushDispatcherLoopTest {

    @BeforeAll
    fun setup() = TestDatabaseFactory.start()

    @AfterAll
    fun tearDown() = TestDatabaseFactory.stop()

    @Test
    fun `two instances racing for the same tick never run work concurrently, and only one of them actually runs it`() = runBlocking {
        val concurrentRuns = AtomicInteger(0)
        val maxObservedConcurrency = AtomicInteger(0)
        val totalRuns = AtomicInteger(0)

        val work: suspend () -> Unit = {
            val current = concurrentRuns.incrementAndGet()
            maxObservedConcurrency.updateAndGet { prev -> maxOf(prev, current) }
            delay(200)
            totalRuns.incrementAndGet()
            concurrentRuns.decrementAndGet()
        }

        val loopA = PushDispatcherLoop(outboxDao = NotificationOutboxDAO(), advisoryLock = PostgresAdvisoryLock(), work = work)
        val loopB = PushDispatcherLoop(outboxDao = NotificationOutboxDAO(), advisoryLock = PostgresAdvisoryLock(), work = work)

        awaitAll(
            async(Dispatchers.IO) { loopA.tick() },
            async(Dispatchers.IO) { loopB.tick() },
        )

        assertEquals(1, maxObservedConcurrency.get(), "work must never run concurrently across two instances")
        assertEquals(1, totalRuns.get(), "the losing instance must skip this tick entirely, not queue behind the winner")
    }

    @Test
    fun `an exception thrown from work is swallowed and does not stop subsequent iterations`() = runBlocking {
        val callCount = AtomicInteger(0)
        val loop = PushDispatcherLoop(
            outboxDao = NotificationOutboxDAO(),
            advisoryLock = PostgresAdvisoryLock(),
            intervalMillis = 10L,
            work = {
                val n = callCount.incrementAndGet()
                if (n == 1) throw RuntimeException("boom")
            },
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
        val loop = PushDispatcherLoop(
            outboxDao = NotificationOutboxDAO(),
            advisoryLock = PostgresAdvisoryLock(),
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
