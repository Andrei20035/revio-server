package com.revio.server.features.notification

/** One triggered condition from plan §16's "Alertare" list, evaluated by [evaluateNotificationAlerts]. */
enum class NotificationAlert {
    /** `outbox.queue_depth` > 1000 sustained for 5 min. */
    QUEUE_DEPTH_HIGH,

    /** `outbox.dead` rate > 5% over the last 15 min. */
    DEAD_RATE_HIGH,

    /** `outbox.age_p95` (enqueue -> accepted) > 10 min for the `COMMENTS` category. */
    COMMENTS_AGE_P95_HIGH,

    /** Zero successful sends in 30 min while `queue_depth` > 0 — the dispatcher looks dead. */
    DISPATCHER_STALLED,

    /** `UNREGISTERED` rate > 20% of terminal FCM outcomes — likely a Firebase project misconfiguration (G15). */
    UNREGISTERED_RATE_HIGH,
}

private const val QUEUE_DEPTH_THRESHOLD = 1000
private val QUEUE_DEPTH_SUSTAIN_MS = java.time.Duration.ofMinutes(5).toMillis()
private const val DEAD_RATE_THRESHOLD = 0.05
private val COMMENTS_AGE_P95_THRESHOLD_MS = java.time.Duration.ofMinutes(10).toMillis()
private val DISPATCHER_STALL_THRESHOLD_MS = java.time.Duration.ofMinutes(30).toMillis()
private const val UNREGISTERED_RATE_THRESHOLD = 0.20

/**
 * Everything [evaluateNotificationAlerts] needs to decide which alerts (plan §16) are currently
 * firing. Every value is a caller-supplied fact for "now" — this function is pure, no I/O, same
 * reasoning as [NotificationPolicyService.evaluate] — so it can be tested directly against
 * hand-built scenarios (e.g. "the dispatcher has been stopped") without a live metrics backend.
 */
data class NotificationAlertInput(
    /** Current `outbox.queue_depth` — PENDING+FAILED row count, live from [INotificationOutboxDAO.countQueued]. */
    val queueDepth: Long,
    /** How long [queueDepth] has continuously been above [QUEUE_DEPTH_THRESHOLD], or null if it currently isn't. */
    val queueDepthSustainedMs: Long?,
    /** `outbox.dead` count in the last 15 min. */
    val deadCountLast15Min: Long,
    /** Every terminal outbox outcome (accepted + dead + dropped_expired) in that same 15 min window — the rate's denominator. */
    val terminalOutcomesLast15Min: Long,
    /** `outbox.age_p95` (enqueue -> accepted), `COMMENTS` category only. */
    val commentsAgeP95Ms: Long,
    /** Milliseconds since the last `outbox.accepted`, or null if there has never been one (or none recorded yet this run). */
    val msSinceLastAcceptedSend: Long?,
    /** `UNREGISTERED`-terminal FCM outcomes in the evaluation window. */
    val unregisteredCount: Long,
    /** Every FCM-terminal outcome (UNREGISTERED + INVALID_ARGUMENT) in that same window — the rate's denominator. */
    val terminalFcmOutcomes: Long,
)

/**
 * Evaluates every alert rule in plan §16 against [input], independently — more than one can fire
 * at once (e.g. a stalled dispatcher usually also has a high queue depth). A rule with a
 * zero-denominator rate (no outcomes to compute a rate from) never fires — an empty window is not
 * evidence of a problem.
 */
fun evaluateNotificationAlerts(input: NotificationAlertInput): Set<NotificationAlert> = buildSet {
    if (input.queueDepth > QUEUE_DEPTH_THRESHOLD &&
        (input.queueDepthSustainedMs ?: 0) >= QUEUE_DEPTH_SUSTAIN_MS
    ) {
        add(NotificationAlert.QUEUE_DEPTH_HIGH)
    }

    if (input.terminalOutcomesLast15Min > 0 &&
        input.deadCountLast15Min.toDouble() / input.terminalOutcomesLast15Min > DEAD_RATE_THRESHOLD
    ) {
        add(NotificationAlert.DEAD_RATE_HIGH)
    }

    if (input.commentsAgeP95Ms > COMMENTS_AGE_P95_THRESHOLD_MS) {
        add(NotificationAlert.COMMENTS_AGE_P95_HIGH)
    }

    if (input.queueDepth > 0 &&
        (input.msSinceLastAcceptedSend == null || input.msSinceLastAcceptedSend >= DISPATCHER_STALL_THRESHOLD_MS)
    ) {
        add(NotificationAlert.DISPATCHER_STALLED)
    }

    if (input.terminalFcmOutcomes > 0 &&
        input.unregisteredCount.toDouble() / input.terminalFcmOutcomes > UNREGISTERED_RATE_THRESHOLD
    ) {
        add(NotificationAlert.UNREGISTERED_RATE_HIGH)
    }
}
