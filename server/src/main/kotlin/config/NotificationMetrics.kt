package com.revio.server.config

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/** How many recent latency samples are kept for a p50/p95 estimate — same capacity/approach as [RouteMetric]. */
private const val LATENCY_SAMPLE_CAPACITY = 512

/** A bounded ring buffer of millisecond samples, for a p50/p95 estimate. Mirrors [RouteMetric]'s latency buffer. */
private class LatencySampleBuffer {
    private val samplesMs = AtomicLongArray(LATENCY_SAMPLE_CAPACITY)
    private val nextIndex = AtomicLong(0)

    fun record(ms: Long) {
        val slot = (nextIndex.getAndIncrement() % LATENCY_SAMPLE_CAPACITY).toInt()
        samplesMs.set(slot, ms)
    }

    fun snapshotSorted(): List<Long> {
        val n = minOf(nextIndex.get(), LATENCY_SAMPLE_CAPACITY.toLong()).toInt()
        return (0 until n).map { samplesMs.get(it) }.sorted()
    }
}

private fun percentile(sortedAscending: List<Long>, p: Double): Long {
    if (sortedAscending.isEmpty()) return 0
    val index = (p * (sortedAscending.size - 1)).toInt().coerceIn(0, sortedAscending.size - 1)
    return sortedAscending[index]
}

private fun countersByKey() = ConcurrentHashMap<String, AtomicLong>()

private fun ConcurrentHashMap<String, AtomicLong>.increment(key: String) {
    getOrPut(key) { AtomicLong(0) }.incrementAndGet()
}

private fun ConcurrentHashMap<String, AtomicLong>.snapshot(): Map<String, Long> =
    mapValues { (_, v) -> v.get() }

/** Point-in-time snapshot for tests/rendering — see plan §16's metric list. */
data class NotificationMetricsSnapshot(
    val eventsCreatedByCategory: Map<String, Long>,
    val suppressedByReason: Map<String, Long>,
    val deferredByCategory: Map<String, Long>,
    val outboxSent: Long,
    val outboxAccepted: Long,
    val outboxFailedByCode: Map<String, Long>,
    val outboxDead: Long,
    val outboxDroppedExpired: Long,
    val outboxUnconfiguredByProject: Map<String, Long>,
    val devicesDeactivatedByReason: Map<String, Long>,
    val dispatchLockContention: Long,
    val fcmLatencyMsP50: Long,
    val fcmLatencyMsP95: Long,
    val outboxAgeMsP50: Long,
    val outboxAgeMsP95: Long,
)

/**
 * Notification-pipeline metrics (plan §18, step 7.3 / §16) — in-process counters and latency
 * samples, same lightweight approach as [configureMetrics]'s [RouteMetric] (no external metrics
 * backend wired up yet). [queueDepth] itself isn't tracked here since it isn't a counter this
 * process increments — it's read live from [com.revio.server.features.notification.INotificationOutboxDAO.countQueued]
 * by whoever renders/evaluates a snapshot.
 */
object NotificationMetrics {
    private val eventsCreated = countersByKey()
    private val suppressed = countersByKey()
    private val deferred = countersByKey()
    private val outboxSentCounter = AtomicLong(0)
    private val outboxAcceptedCounter = AtomicLong(0)
    private val outboxFailed = countersByKey()
    private val outboxDeadCounter = AtomicLong(0)
    private val outboxDroppedExpiredCounter = AtomicLong(0)
    private val outboxUnconfigured = countersByKey()
    private val devicesDeactivated = countersByKey()
    private val dispatchLockContentionCounter = AtomicLong(0)
    private val fcmLatencyMs = LatencySampleBuffer()
    private val outboxAgeMs = LatencySampleBuffer()

    /** `notifications.events.created{category}` — a *new* `user_notifications` row was created (not an aggregation update). */
    fun eventCreated(category: String) = eventsCreated.increment(category)

    /** `notifications.suppressed{reason}` — an event was evaluated and will never be pushed (still lands in the inbox). */
    fun suppressed(reason: String) = suppressed.increment(reason)

    /** `notifications.deferred{category}` — an event was pushed to quiet-hours' `not_before` instead of dispatched now. */
    fun deferred(category: String) = deferred.increment(category)

    /** `outbox.sent` — an FCM HTTP call was actually issued. */
    fun outboxSent() = outboxSentCounter.incrementAndGet()

    /** `outbox.accepted` — FCM returned 200 with a message name. [ageMs] feeds `outbox.age_p50/p95` (enqueue -> accepted). */
    fun outboxAccepted(ageMs: Long) {
        outboxAcceptedCounter.incrementAndGet()
        outboxAgeMs.record(ageMs)
    }

    /** `outbox.failed{code}` — a retriable (or unclassified) send failure, before any dead-lettering decision. */
    fun outboxFailed(code: String) = outboxFailed.increment(code)

    /** `outbox.dead` — a row was dead-lettered (terminal FCM error, or retry budget exhausted). */
    fun outboxDead() = outboxDeadCounter.incrementAndGet()

    /** `outbox.dropped_expired` — a row's freshness TTL passed before it could be sent. */
    fun outboxDroppedExpired() = outboxDroppedExpiredCounter.incrementAndGet()

    /** `outbox.unconfigured{project}` — a send was skipped because that Firebase project has no usable FCM credential (see [com.revio.server.features.notification.FcmCredentialsProvider]). Row stays PENDING and is retried next tick. */
    fun outboxUnconfigured(project: String) = outboxUnconfigured.increment(project)

    /** `devices.deactivated{reason}` — a device row was deactivated (session revoke, or FCM rejected its token outright). */
    fun deviceDeactivated(reason: String) = devicesDeactivated.increment(reason)

    /** `fcm.latency_ms` — one FCM HTTP v1 call's wall-clock duration. */
    fun fcmLatency(ms: Long) = fcmLatencyMs.record(ms)

    /** `dispatch.lock_contention` — a dispatcher tick found the advisory lock already held and skipped its work. */
    fun dispatchLockContention() = dispatchLockContentionCounter.incrementAndGet()

    /**
     * A consistent read of every counter/sample — [queueDepth] is supplied by the caller (a live
     * DB read, not something this object tracks) so alert evaluation and rendering share one
     * coherent view of "now".
     */
    fun snapshot(): NotificationMetricsSnapshot {
        val fcmSorted = fcmLatencyMs.snapshotSorted()
        val ageSorted = outboxAgeMs.snapshotSorted()
        return NotificationMetricsSnapshot(
            eventsCreatedByCategory = eventsCreated.snapshot(),
            suppressedByReason = suppressed.snapshot(),
            deferredByCategory = deferred.snapshot(),
            outboxSent = outboxSentCounter.get(),
            outboxAccepted = outboxAcceptedCounter.get(),
            outboxFailedByCode = outboxFailed.snapshot(),
            outboxDead = outboxDeadCounter.get(),
            outboxDroppedExpired = outboxDroppedExpiredCounter.get(),
            outboxUnconfiguredByProject = outboxUnconfigured.snapshot(),
            devicesDeactivatedByReason = devicesDeactivated.snapshot(),
            dispatchLockContention = dispatchLockContentionCounter.get(),
            fcmLatencyMsP50 = percentile(fcmSorted, 0.50),
            fcmLatencyMsP95 = percentile(fcmSorted, 0.95),
            outboxAgeMsP50 = percentile(ageSorted, 0.50),
            outboxAgeMsP95 = percentile(ageSorted, 0.95),
        )
    }
}

/** Plain-text rendering for the `/internal/notifications/metrics` endpoint — same shape as [renderMetrics]. */
fun renderNotificationMetrics(snapshot: NotificationMetricsSnapshot, queueDepth: Long): String {
    val sb = StringBuilder()
    sb.appendLine("outbox_queue_depth $queueDepth")
    sb.appendLine("outbox_sent ${snapshot.outboxSent}")
    sb.appendLine("outbox_accepted ${snapshot.outboxAccepted}")
    sb.appendLine("outbox_dead ${snapshot.outboxDead}")
    sb.appendLine("outbox_dropped_expired ${snapshot.outboxDroppedExpired}")
    sb.appendLine("outbox_age_p50_ms ${snapshot.outboxAgeMsP50}")
    sb.appendLine("outbox_age_p95_ms ${snapshot.outboxAgeMsP95}")
    sb.appendLine("fcm_latency_p50_ms ${snapshot.fcmLatencyMsP50}")
    sb.appendLine("fcm_latency_p95_ms ${snapshot.fcmLatencyMsP95}")
    sb.appendLine("dispatch_lock_contention ${snapshot.dispatchLockContention}")
    snapshot.eventsCreatedByCategory.toSortedMap().forEach { (k, v) -> sb.appendLine("notifications_events_created{category=\"$k\"} $v") }
    snapshot.suppressedByReason.toSortedMap().forEach { (k, v) -> sb.appendLine("notifications_suppressed{reason=\"$k\"} $v") }
    snapshot.deferredByCategory.toSortedMap().forEach { (k, v) -> sb.appendLine("notifications_deferred{category=\"$k\"} $v") }
    snapshot.outboxFailedByCode.toSortedMap().forEach { (k, v) -> sb.appendLine("outbox_failed{code=\"$k\"} $v") }
    snapshot.outboxUnconfiguredByProject.toSortedMap().forEach { (k, v) -> sb.appendLine("outbox_unconfigured{project=\"$k\"} $v") }
    snapshot.devicesDeactivatedByReason.toSortedMap().forEach { (k, v) -> sb.appendLine("devices_deactivated{reason=\"$k\"} $v") }
    return sb.toString()
}
