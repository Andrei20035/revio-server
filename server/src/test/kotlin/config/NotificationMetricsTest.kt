package com.revio.server.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [NotificationMetrics] is a process-wide singleton (same approach as [configureMetrics]'s
 * `RouteMetric` registry), so every assertion here reads a *delta* around a distinct, randomly
 * generated key rather than an absolute count — other tests in the same JVM run may also touch
 * these counters, but never under this test's own unique key.
 */
class NotificationMetricsTest {

    private fun uniqueKey() = "test_${UUID.randomUUID()}"

    @Test
    fun `eventCreated increments only its own category`() {
        val category = uniqueKey()
        val before = NotificationMetrics.snapshot().eventsCreatedByCategory[category] ?: 0

        NotificationMetrics.eventCreated(category)
        NotificationMetrics.eventCreated(category)

        val after = NotificationMetrics.snapshot().eventsCreatedByCategory[category] ?: 0
        assertEquals(before + 2, after)
    }

    @Test
    fun `suppressed and deferred are tracked independently by reason and category`() {
        val reason = uniqueKey()
        val category = uniqueKey()

        NotificationMetrics.suppressed(reason)
        NotificationMetrics.deferred(category)

        val snapshot = NotificationMetrics.snapshot()
        assertEquals(1, snapshot.suppressedByReason[reason])
        assertEquals(1, snapshot.deferredByCategory[category])
        // Recording one never bleeds into the other's map.
        assertEquals(null, snapshot.suppressedByReason[category])
        assertEquals(null, snapshot.deferredByCategory[reason])
    }

    @Test
    fun `outboxAccepted feeds the age percentile samples`() {
        NotificationMetrics.outboxAccepted(ageMs = 1000)
        NotificationMetrics.outboxAccepted(ageMs = 2000)
        NotificationMetrics.outboxAccepted(ageMs = 3000)

        val snapshot = NotificationMetrics.snapshot()
        assertTrue(snapshot.outboxAgeMsP50 in 1000..3000)
        assertTrue(snapshot.outboxAgeMsP95 in 1000..3000)
        assertTrue(snapshot.outboxAccepted >= 3)
    }

    @Test
    fun `outboxFailed and devicesDeactivated are tracked independently by their own key`() {
        val code = uniqueKey()
        val reason = uniqueKey()

        NotificationMetrics.outboxFailed(code)
        NotificationMetrics.deviceDeactivated(reason)

        val snapshot = NotificationMetrics.snapshot()
        assertEquals(1, snapshot.outboxFailedByCode[code])
        assertEquals(1, snapshot.devicesDeactivatedByReason[reason])
    }

    @Test
    fun `renderNotificationMetrics includes queue depth and every counter family`() {
        val category = uniqueKey()
        NotificationMetrics.eventCreated(category)

        val rendered = renderNotificationMetrics(NotificationMetrics.snapshot(), queueDepth = 42)

        assertTrue(rendered.contains("outbox_queue_depth 42"))
        assertTrue(rendered.contains("notifications_events_created{category=\"$category\"}"))
        assertTrue(rendered.contains("outbox_age_p95_ms"))
        assertTrue(rendered.contains("fcm_latency_p95_ms"))
    }
}
