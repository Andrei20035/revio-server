package com.revio.server.features.notification

import com.revio.server.config.NotificationMetrics
import com.revio.server.config.NotificationMetricsSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

private val logger = LoggerFactory.getLogger("com.revio.server.features.notification.NotificationAlertEvaluatorLoop")

/** Reasons [NotificationMetrics.deviceDeactivated] records for the two [FcmTerminalReason] values — see [NotificationOutboxProcessor]'s `"fcm_${result.reason.name.lowercase()}"`. */
private const val UNREGISTERED_REASON_KEY = "fcm_unregistered"
private const val INVALID_ARGUMENT_REASON_KEY = "fcm_invalid_argument"

/**
 * The alert-evaluation loop (step 4.4). A single in-process loop, woken every [intervalMillis],
 * that composes a [NotificationAlertInput] and runs it through [evaluateNotificationAlerts] —
 * the only place that actually happens; before this, the function had 8 unit tests and no
 * caller, so no alert ever fired in production, however bad the pipeline got.
 *
 * [NotificationMetrics] holds counters cumulative since process start, not time-windowed, so
 * [NotificationAlertInput.deadCountLast15Min]/`terminalOutcomesLast15Min`/`unregisteredCount`/
 * `terminalFcmOutcomes` are each approximated as the delta between this tick's snapshot and the
 * previous one — [intervalMillis] stands in for that "last 15 min" window rather than a true
 * rolling one. [NotificationAlertInput.commentsAgeP95Ms] is similarly approximated by
 * [NotificationMetricsSnapshot.outboxAgeMsP95], which isn't broken down per category. Genuinely
 * elapsed-time facts — [queueDepthSustainedMs] and `msSinceLastAcceptedSend` — are tracked
 * directly across ticks instead of via a delta, since a single interval's worth of "still above
 * threshold" doesn't by itself mean "sustained".
 */
class NotificationAlertEvaluatorLoop(
    private val outboxDao: INotificationOutboxDAO,
    private val intervalMillis: Long = 5 * 60_000L,
    private val clock: () -> Instant = Instant::now,
    private val onAlerts: (Set<NotificationAlert>) -> Unit = { alerts ->
        alerts.forEach { alert -> logger.warn("notification alert firing: {}", alert) }
    },
) {
    @Volatile
    private var job: Job? = null

    private var previousSnapshot: NotificationMetricsSnapshot? = null
    private var queueDepthAboveThresholdSince: Instant? = null
    private var lastAcceptedCountSeen: Long = 0
    private var lastAcceptedAt: Instant? = null

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
                    logger.warn("notification alert tick failed, will retry next interval: {}", e.message, e)
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
     * One evaluation. Exposed (rather than purely private) so tests can drive individual ticks
     * deterministically instead of waiting on [intervalMillis].
     */
    internal suspend fun tick() {
        val now = clock()
        val queueDepth = outboxDao.countQueued()
        val snapshot = NotificationMetrics.snapshot()
        val previous = previousSnapshot

        queueDepthAboveThresholdSince = when {
            queueDepth <= QUEUE_DEPTH_THRESHOLD -> null
            queueDepthAboveThresholdSince == null -> now
            else -> queueDepthAboveThresholdSince
        }
        val queueDepthSustainedMs = queueDepthAboveThresholdSince?.let { Duration.between(it, now).toMillis() }

        if (snapshot.outboxAccepted > lastAcceptedCountSeen) {
            lastAcceptedAt = now
        }
        lastAcceptedCountSeen = snapshot.outboxAccepted
        val msSinceLastAcceptedSend = lastAcceptedAt?.let { Duration.between(it, now).toMillis() }

        val deadDelta = counterDelta(snapshot.outboxDead, previous?.outboxDead)
        val acceptedDelta = counterDelta(snapshot.outboxAccepted, previous?.outboxAccepted)
        val droppedExpiredDelta = counterDelta(snapshot.outboxDroppedExpired, previous?.outboxDroppedExpired)
        val terminalOutcomesDelta = deadDelta + acceptedDelta + droppedExpiredDelta

        val unregisteredDelta = mapCounterDelta(snapshot.devicesDeactivatedByReason, previous?.devicesDeactivatedByReason, UNREGISTERED_REASON_KEY)
        val invalidArgumentDelta = mapCounterDelta(snapshot.devicesDeactivatedByReason, previous?.devicesDeactivatedByReason, INVALID_ARGUMENT_REASON_KEY)
        val terminalFcmOutcomesDelta = unregisteredDelta + invalidArgumentDelta

        previousSnapshot = snapshot

        val alerts = evaluateNotificationAlerts(
            NotificationAlertInput(
                queueDepth = queueDepth,
                queueDepthSustainedMs = queueDepthSustainedMs,
                deadCountLast15Min = deadDelta,
                terminalOutcomesLast15Min = terminalOutcomesDelta,
                commentsAgeP95Ms = snapshot.outboxAgeMsP95,
                msSinceLastAcceptedSend = msSinceLastAcceptedSend,
                unregisteredCount = unregisteredDelta,
                terminalFcmOutcomes = terminalFcmOutcomesDelta,
            ),
        )
        if (alerts.isNotEmpty()) {
            onAlerts(alerts)
        }
    }

    /** A cumulative counter only ever grows — a negative delta (a process restart resetting it) is floored at 0 rather than read as a drop. */
    private fun counterDelta(current: Long, previous: Long?): Long = (current - (previous ?: current)).coerceAtLeast(0)

    private fun mapCounterDelta(current: Map<String, Long>, previous: Map<String, Long>?, key: String): Long =
        counterDelta(current[key] ?: 0, previous?.get(key) ?: 0)
}
