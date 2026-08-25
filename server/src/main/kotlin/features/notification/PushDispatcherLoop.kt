package com.revio.server.features.notification

import com.revio.server.config.NotificationMetrics
import com.revio.server.features.challenge.IAdvisoryLock
import com.revio.server.features.challenge.PostgresAdvisoryLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.revio.server.features.notification.PushDispatcherLoop")

/**
 * Global advisory lock key for the push outbox dispatcher (D2). Session-scoped Postgres advisory
 * locks share one flat 64-bit keyspace across every feature that takes one out (see
 * ChallengeFinalizationService, which derives its own per-challenge keys from UUIDs) — this fixed
 * constant must never collide with another feature's key. A literal rather than a derived hash so
 * its value is visible directly at the call site.
 */
private const val DISPATCHER_ADVISORY_LOCK_KEY = 592_190_001L

/**
 * The push notification outbox dispatcher loop (plan §18, step 3.5; D2). A single in-process
 * loop, woken every [intervalMillis] (20-30s per the plan), that takes a Postgres advisory lock
 * before doing anything — so if more than one process (or, in a test, more than one instance of
 * this class) wakes up at the same moment, only one of them actually runs [work] for that tick;
 * the other's [IAdvisoryLock.tryAcquire] simply returns false and it skips straight to its next
 * interval. The lock is reused as-is from [ChallengeFinalizationService]'s
 * [IAdvisoryLock]/[PostgresAdvisoryLock], not duplicated.
 *
 * [work] is the actual per-tick unit of work, injected so this class stays pure scheduling
 * machinery: acquire lock -> run [work] -> release lock, with a thrown exception from [work]
 * logged and swallowed rather than killing the loop (an explicit acceptance criterion for this
 * step), and interruptible via [stop] for clean application shutdown. It defaults to draining
 * (reading) [outboxDao]'s due rows; actually sending them via FCM with retry/backoff/TTL/dead-
 * letter handling is step 3.6's job, layered onto this same loop without changing its scheduling
 * contract.
 */
class PushDispatcherLoop(
    outboxDao: INotificationOutboxDAO,
    private val advisoryLock: IAdvisoryLock = PostgresAdvisoryLock(),
    private val intervalMillis: Long = 25_000L,
    batchSize: Int = 50,
    private val work: suspend () -> Unit = {
        val drainable = outboxDao.findDrainable(batchSize)
        if (drainable.isNotEmpty()) {
            logger.info("push dispatcher: {} outbox row(s) due", drainable.size)
        }
    },
) {
    @Volatile
    private var job: Job? = null

    /** True while the loop's coroutine is still running — false once [stop] has taken effect. */
    val isRunning: Boolean
        get() = job?.isActive == true

    /** Starts the loop on [scope]. Safe to call at most once per instance. */
    fun start(scope: CoroutineScope): Job {
        val started = scope.launch {
            while (isActive) {
                try {
                    tick()
                } catch (e: Exception) {
                    logger.warn("push dispatcher tick failed, will retry next interval: {}", e.message, e)
                }
                delay(intervalMillis)
            }
        }
        job = started
        return started
    }

    /** Cancels the loop's coroutine. Idempotent. */
    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * One lock-guarded iteration. Exposed (rather than purely private) so tests can drive
     * individual ticks deterministically instead of waiting on [intervalMillis].
     */
    internal suspend fun tick() {
        if (!advisoryLock.tryAcquire(DISPATCHER_ADVISORY_LOCK_KEY)) {
            NotificationMetrics.dispatchLockContention()
            return
        }
        try {
            work()
        } finally {
            advisoryLock.release(DISPATCHER_ADVISORY_LOCK_KEY)
        }
    }
}
