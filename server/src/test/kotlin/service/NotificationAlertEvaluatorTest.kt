package service

import com.revio.server.features.challenge.IAdvisoryLock
import com.revio.server.features.notification.INotificationOutboxDAO
import com.revio.server.features.notification.NotificationAlert
import com.revio.server.features.notification.NotificationAlertInput
import com.revio.server.features.notification.NotificationOutboxEntry
import com.revio.server.features.notification.PushDispatcherLoop
import com.revio.server.features.notification.evaluateNotificationAlerts
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

private val TEN_MIN_MS = Duration.ofMinutes(10).toMillis()
private val THIRTY_ONE_MIN_MS = Duration.ofMinutes(31).toMillis()

private fun baselineInput() = NotificationAlertInput(
    queueDepth = 0,
    queueDepthSustainedMs = null,
    deadCountLast15Min = 0,
    terminalOutcomesLast15Min = 0,
    commentsAgeP95Ms = 0,
    msSinceLastAcceptedSend = 0,
    unregisteredCount = 0,
    terminalFcmOutcomes = 0,
)

/** In-memory [IAdvisoryLock] so [PushDispatcherLoop] can be driven here without a live Postgres connection. */
private class InMemoryAdvisoryLock : IAdvisoryLock {
    private val held = mutableSetOf<Long>()
    override fun tryAcquire(key: Long): Boolean = held.add(key)
    override fun release(key: Long) {
        held.remove(key)
    }
}

private class NeverDrainsOutboxDAO : INotificationOutboxDAO {
    override suspend fun enqueue(notificationId: UUID, deviceId: UUID, notBefore: OffsetDateTime?, expiresAt: OffsetDateTime?) = Unit
    override suspend fun find(notificationId: UUID, deviceId: UUID): NotificationOutboxEntry? = null
    override suspend fun findById(id: UUID): NotificationOutboxEntry? = null
    override suspend fun findDrainable(limit: Int): List<NotificationOutboxEntry> = emptyList()
    override suspend fun markAccepted(id: UUID, fcmMessageId: String) = Unit
    override suspend fun markRetriableFailure(id: UUID, attempts: Int, nextAttemptAt: OffsetDateTime, lastErrorCode: String?) = Unit
    override suspend fun markDead(id: UUID, lastErrorCode: String?) = Unit
    override suspend fun markDropped(id: UUID) = Unit
    override suspend fun countQueued(): Long = 7 // a non-empty backlog the stopped dispatcher will never drain
}

/**
 * [evaluateNotificationAlerts] is a pure function (no I/O — same reasoning as
 * [com.revio.server.features.notification.NotificationPolicyService]), so every rule is testable
 * directly from hand-built [NotificationAlertInput] scenarios. The plan's motivating case for
 * [NotificationAlert.DISPATCHER_STALLED] (§16: "zero trimiteri reușite în 30 min când
 * queue_depth > 0 — dispatcher mort") is exercised against an actual, deliberately-stopped
 * [PushDispatcherLoop] rather than just a hand-picked number, so the scenario is real: the loop
 * is started, ticks are driven manually, then it is stopped mid-backlog and the alert fires.
 */
class NotificationAlertEvaluatorTest {

    @Test
    fun `no alerts fire on a healthy baseline`() {
        assertTrue(evaluateNotificationAlerts(baselineInput()).isEmpty())
    }

    @Test
    fun `queue depth alert only fires once sustained past 5 minutes`() {
        val input = baselineInput().copy(queueDepth = 1001, queueDepthSustainedMs = Duration.ofMinutes(5).toMillis())
        assertTrue(NotificationAlert.QUEUE_DEPTH_HIGH in evaluateNotificationAlerts(input))

        val notYetSustained = baselineInput().copy(queueDepth = 1001, queueDepthSustainedMs = Duration.ofMinutes(4).toMillis())
        assertFalse(NotificationAlert.QUEUE_DEPTH_HIGH in evaluateNotificationAlerts(notYetSustained))

        val belowThreshold = baselineInput().copy(queueDepth = 1000, queueDepthSustainedMs = Duration.ofMinutes(10).toMillis())
        assertFalse(NotificationAlert.QUEUE_DEPTH_HIGH in evaluateNotificationAlerts(belowThreshold))
    }

    @Test
    fun `dead rate alert fires above 5 percent and never on an empty window`() {
        val over = baselineInput().copy(deadCountLast15Min = 6, terminalOutcomesLast15Min = 100)
        assertTrue(NotificationAlert.DEAD_RATE_HIGH in evaluateNotificationAlerts(over))

        val atThreshold = baselineInput().copy(deadCountLast15Min = 5, terminalOutcomesLast15Min = 100)
        assertFalse(NotificationAlert.DEAD_RATE_HIGH in evaluateNotificationAlerts(atThreshold))

        val emptyWindow = baselineInput().copy(deadCountLast15Min = 0, terminalOutcomesLast15Min = 0)
        assertFalse(NotificationAlert.DEAD_RATE_HIGH in evaluateNotificationAlerts(emptyWindow))
    }

    @Test
    fun `comments age p95 alert fires only above 10 minutes`() {
        assertTrue(NotificationAlert.COMMENTS_AGE_P95_HIGH in evaluateNotificationAlerts(baselineInput().copy(commentsAgeP95Ms = TEN_MIN_MS + 1)))
        assertFalse(NotificationAlert.COMMENTS_AGE_P95_HIGH in evaluateNotificationAlerts(baselineInput().copy(commentsAgeP95Ms = TEN_MIN_MS)))
    }

    @Test
    fun `unregistered rate alert fires above 20 percent and never on an empty window`() {
        val over = baselineInput().copy(unregisteredCount = 21, terminalFcmOutcomes = 100)
        assertTrue(NotificationAlert.UNREGISTERED_RATE_HIGH in evaluateNotificationAlerts(over))

        val atThreshold = baselineInput().copy(unregisteredCount = 20, terminalFcmOutcomes = 100)
        assertFalse(NotificationAlert.UNREGISTERED_RATE_HIGH in evaluateNotificationAlerts(atThreshold))

        val emptyWindow = baselineInput().copy(unregisteredCount = 0, terminalFcmOutcomes = 0)
        assertFalse(NotificationAlert.UNREGISTERED_RATE_HIGH in evaluateNotificationAlerts(emptyWindow))
    }

    @Test
    fun `dispatcher-stalled alert fires once a stopped dispatcher leaves a backlog unsent past 30 minutes`() = runBlocking {
        val loop = PushDispatcherLoop(outboxDao = NeverDrainsOutboxDAO(), advisoryLock = InMemoryAdvisoryLock())

        // The dispatcher runs normally for a tick or two...
        loop.tick()
        loop.tick()
        assertFalse(evaluateNotificationAlerts(baselineInput()).contains(NotificationAlert.DISPATCHER_STALLED))

        // ...then is deliberately stopped, exactly the scenario plan §16 describes: the backlog
        // it never got to keeps growing (queueDepth from the still-full outbox) while no further
        // sends succeed.
        loop.stop()
        assertFalse(loop.isRunning)

        val afterStopping = NotificationAlertInput(
            queueDepth = 7, // NeverDrainsOutboxDAO.countQueued() — the backlog the stopped loop leaves behind
            queueDepthSustainedMs = null,
            deadCountLast15Min = 0,
            terminalOutcomesLast15Min = 0,
            commentsAgeP95Ms = 0,
            msSinceLastAcceptedSend = THIRTY_ONE_MIN_MS,
            unregisteredCount = 0,
            terminalFcmOutcomes = 0,
        )

        val firedAlerts = evaluateNotificationAlerts(afterStopping)
        assertTrue(NotificationAlert.DISPATCHER_STALLED in firedAlerts, "a stopped dispatcher sitting on a backlog must trip the alert")
    }

    @Test
    fun `dispatcher-stalled alert never fires while the queue is empty, however long since the last send`() {
        val input = baselineInput().copy(queueDepth = 0, msSinceLastAcceptedSend = THIRTY_ONE_MIN_MS * 10)
        assertFalse(NotificationAlert.DISPATCHER_STALLED in evaluateNotificationAlerts(input))
    }

    @Test
    fun `multiple alerts can fire together`() {
        val input = NotificationAlertInput(
            queueDepth = 5000,
            queueDepthSustainedMs = Duration.ofMinutes(6).toMillis(),
            deadCountLast15Min = 50,
            terminalOutcomesLast15Min = 100,
            commentsAgeP95Ms = TEN_MIN_MS * 2,
            msSinceLastAcceptedSend = THIRTY_ONE_MIN_MS,
            unregisteredCount = 30,
            terminalFcmOutcomes = 100,
        )

        val fired = evaluateNotificationAlerts(input)
        assertEquals(setOf(
            NotificationAlert.QUEUE_DEPTH_HIGH,
            NotificationAlert.DEAD_RATE_HIGH,
            NotificationAlert.COMMENTS_AGE_P95_HIGH,
            NotificationAlert.DISPATCHER_STALLED,
            NotificationAlert.UNREGISTERED_RATE_HIGH,
        ), fired)
    }
}
