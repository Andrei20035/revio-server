package service

import features.comment.CommentService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Pure unit coverage for CommentService.windowStartFor — the 15-minute floor used to build a
 * comment notification's dedupe_key (plan §18, step 4.2). No DB: this is the exact piece of
 * math the "16 minutes opens a new window" acceptance criterion hinges on, and it can't be
 * exercised through a real wall-clock wait in CommentNotificationAggregationTest.kt's
 * Testcontainers-backed tests.
 */
class CommentServiceWindowTest {

    @Test
    fun `two instants 16 minutes apart fall in different windows`() {
        val first = Instant.parse("2026-06-15T10:00:00Z")
        val second = first.plusSeconds(16 * 60)

        assertNotEquals(CommentService.windowStartFor(first), CommentService.windowStartFor(second))
    }

    @Test
    fun `two instants exactly 15 minutes apart fall in different windows`() {
        val first = Instant.parse("2026-06-15T10:00:00Z")
        val second = first.plusSeconds(CommentService.AGGREGATION_WINDOW_SECONDS)

        assertNotEquals(CommentService.windowStartFor(first), CommentService.windowStartFor(second))
    }

    @Test
    fun `two instants within the same 15-minute bucket share a window`() {
        val first = Instant.parse("2026-06-15T10:00:01Z")
        val second = Instant.parse("2026-06-15T10:14:59Z")

        assertEquals(CommentService.windowStartFor(first), CommentService.windowStartFor(second))
    }

    @Test
    fun `an instant floors down to its bucket start, never rounds up`() {
        val instant = Instant.parse("2026-06-15T10:07:33Z")
        val expected = Instant.parse("2026-06-15T10:00:00Z")

        assertEquals(expected, CommentService.windowStartFor(instant))
    }

    @Test
    fun `an instant already on a bucket boundary floors to itself`() {
        val instant = Instant.parse("2026-06-15T10:15:00Z")

        assertEquals(instant, CommentService.windowStartFor(instant))
    }
}
