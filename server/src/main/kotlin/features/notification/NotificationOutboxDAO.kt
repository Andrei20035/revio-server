package com.revio.server.features.notification

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class NotificationOutboxEntry(
    val id: UUID,
    val notificationId: UUID,
    val deviceId: UUID,
    val state: OutboxState,
    val attempts: Int,
    val nextAttemptAt: OffsetDateTime,
    val notBefore: OffsetDateTime?,
    val expiresAt: OffsetDateTime?,
    val fcmMessageId: String?,
    val lastErrorCode: String?,
    /** When this row was enqueued — the start of the `outbox.age_p50/p95` (enqueue -> accepted) measurement (§16, pas 7.3). */
    val createdAt: OffsetDateTime,
)

interface INotificationOutboxDAO {
    /**
     * Enqueues a send of [notificationId] to [deviceId]. Idempotent on
     * (notification_id, device_id) — a duplicate enqueue is silently ignored rather than
     * creating a second row, per the UNIQUE constraint in V37__notification_outbox.sql.
     */
    suspend fun enqueue(
        notificationId: UUID,
        deviceId: UUID,
        notBefore: OffsetDateTime? = null,
        expiresAt: OffsetDateTime? = null,
    )

    /** Fetch the outbox row for a given (notificationId, deviceId) pair, or null if none exists. */
    suspend fun find(notificationId: UUID, deviceId: UUID): NotificationOutboxEntry?

    /** Fetch a single outbox row by its own id, or null if it doesn't exist. */
    suspend fun findById(id: UUID): NotificationOutboxEntry?

    /**
     * Rows ready for the drainer to pick up: PENDING or FAILED, due now or earlier. Ordered by
     * next_attempt_at so the oldest-due rows are served first.
     */
    suspend fun findDrainable(limit: Int): List<NotificationOutboxEntry>

    /** FCM accepted the send — terminal success. Records [fcmMessageId] for traceability. */
    suspend fun markAccepted(id: UUID, fcmMessageId: String)

    /**
     * A retriable failure (5xx/429) that hasn't yet exhausted its retry budget: bumps [attempts],
     * schedules the next attempt at [nextAttemptAt] (the caller's backoff decision — see
     * [nextRetryDecision]), and records [lastErrorCode] for observability.
     */
    suspend fun markRetriableFailure(id: UUID, attempts: Int, nextAttemptAt: OffsetDateTime, lastErrorCode: String?)

    /**
     * Terminal give-up: either an FCM-terminal error (UNREGISTERED/INVALID_ARGUMENT — no retry is
     * ever attempted for these) or a retriable failure that has exhausted its retry budget.
     */
    suspend fun markDead(id: UUID, lastErrorCode: String?)

    /** The event's freshness TTL has passed before it could be sent — dropped without an attempt. */
    suspend fun markDropped(id: UUID)

    /** `outbox.queue_depth` (§16, pas 7.3) — how many rows are still PENDING or FAILED, regardless of whether they're due yet. */
    suspend fun countQueued(): Long

    /**
     * Counts distinct notification events accepted by FCM for [userId] since [since], limited
     * to [categories]. A notification fanned out to multiple devices counts once, because policy
     * caps are per user/event rather than per device delivery.
     */
    suspend fun countAcceptedNotificationsSince(
        userId: UUID,
        categories: Set<NotificationCategory>,
        since: OffsetDateTime,
    ): Int = throw UnsupportedOperationException("Accepted-notification policy counts are not implemented")
}

class NotificationOutboxDAO : INotificationOutboxDAO {

    override suspend fun enqueue(
        notificationId: UUID,
        deviceId: UUID,
        notBefore: OffsetDateTime?,
        expiresAt: OffsetDateTime?,
    ): Unit = transaction {
        NotificationOutboxTable.insertIgnore {
            it[NotificationOutboxTable.notificationId] = notificationId
            it[NotificationOutboxTable.deviceId] = deviceId
            it[NotificationOutboxTable.notBefore] = notBefore
            it[NotificationOutboxTable.expiresAt] = expiresAt
            notBefore?.let { nb -> it[NotificationOutboxTable.nextAttemptAt] = nb }
        }
        Unit
    }

    override suspend fun find(notificationId: UUID, deviceId: UUID): NotificationOutboxEntry? = transaction {
        NotificationOutboxTable
            .selectAll()
            .where {
                (NotificationOutboxTable.notificationId eq notificationId) and
                    (NotificationOutboxTable.deviceId eq deviceId)
            }
            .singleOrNull()
            ?.toEntry()
    }

    override suspend fun findById(id: UUID): NotificationOutboxEntry? = transaction {
        NotificationOutboxTable
            .selectAll()
            .where { NotificationOutboxTable.id eq id }
            .singleOrNull()
            ?.toEntry()
    }

    override suspend fun findDrainable(limit: Int): List<NotificationOutboxEntry> = transaction {
        val now = Instant.now().atOffset(ZoneOffset.UTC)
        NotificationOutboxTable
            .selectAll()
            .where {
                ((NotificationOutboxTable.state eq OutboxState.PENDING) or
                    (NotificationOutboxTable.state eq OutboxState.FAILED)) and
                    (NotificationOutboxTable.nextAttemptAt lessEq now)
            }
            .orderBy(NotificationOutboxTable.nextAttemptAt, SortOrder.ASC)
            .limit(limit)
            .map { it.toEntry() }
    }

    override suspend fun markAccepted(id: UUID, fcmMessageId: String): Unit = transaction {
        NotificationOutboxTable.update({ NotificationOutboxTable.id eq id }) {
            it[NotificationOutboxTable.state] = OutboxState.ACCEPTED
            it[NotificationOutboxTable.fcmMessageId] = fcmMessageId
            it[NotificationOutboxTable.updatedAt] = Instant.now().atOffset(ZoneOffset.UTC)
        }
        Unit
    }

    override suspend fun markRetriableFailure(
        id: UUID,
        attempts: Int,
        nextAttemptAt: OffsetDateTime,
        lastErrorCode: String?,
    ): Unit = transaction {
        NotificationOutboxTable.update({ NotificationOutboxTable.id eq id }) {
            it[NotificationOutboxTable.state] = OutboxState.FAILED
            it[NotificationOutboxTable.attempts] = attempts
            it[NotificationOutboxTable.nextAttemptAt] = nextAttemptAt
            it[NotificationOutboxTable.lastErrorCode] = lastErrorCode
            it[NotificationOutboxTable.updatedAt] = Instant.now().atOffset(ZoneOffset.UTC)
        }
        Unit
    }

    override suspend fun markDead(id: UUID, lastErrorCode: String?): Unit = transaction {
        NotificationOutboxTable.update({ NotificationOutboxTable.id eq id }) {
            it[NotificationOutboxTable.state] = OutboxState.DEAD
            it[NotificationOutboxTable.lastErrorCode] = lastErrorCode
            it[NotificationOutboxTable.updatedAt] = Instant.now().atOffset(ZoneOffset.UTC)
        }
        Unit
    }

    override suspend fun markDropped(id: UUID): Unit = transaction {
        NotificationOutboxTable.update({ NotificationOutboxTable.id eq id }) {
            it[NotificationOutboxTable.state] = OutboxState.DROPPED
            it[NotificationOutboxTable.updatedAt] = Instant.now().atOffset(ZoneOffset.UTC)
        }
        Unit
    }

    override suspend fun countQueued(): Long = transaction {
        NotificationOutboxTable
            .selectAll()
            .where {
                (NotificationOutboxTable.state eq OutboxState.PENDING) or
                    (NotificationOutboxTable.state eq OutboxState.FAILED)
            }
            .count()
    }

    override suspend fun countAcceptedNotificationsSince(
        userId: UUID,
        categories: Set<NotificationCategory>,
        since: OffsetDateTime,
    ): Int = transaction {
        if (categories.isEmpty()) return@transaction 0

        NotificationOutboxTable
            .innerJoin(NotificationTable)
            .select(NotificationOutboxTable.notificationId)
            .where {
                (NotificationOutboxTable.state eq OutboxState.ACCEPTED) and
                    (NotificationOutboxTable.updatedAt greaterEq since) and
                    (NotificationTable.userId eq userId) and
                    (NotificationTable.category inList categories)
            }
            .withDistinct()
            .count()
            .toInt()
    }

    private fun ResultRow.toEntry() = NotificationOutboxEntry(
        id = this[NotificationOutboxTable.id].value,
        notificationId = this[NotificationOutboxTable.notificationId].value,
        deviceId = this[NotificationOutboxTable.deviceId].value,
        state = this[NotificationOutboxTable.state],
        attempts = this[NotificationOutboxTable.attempts],
        nextAttemptAt = this[NotificationOutboxTable.nextAttemptAt],
        notBefore = this[NotificationOutboxTable.notBefore],
        expiresAt = this[NotificationOutboxTable.expiresAt],
        fcmMessageId = this[NotificationOutboxTable.fcmMessageId],
        lastErrorCode = this[NotificationOutboxTable.lastErrorCode],
        createdAt = this[NotificationOutboxTable.createdAt],
    )
}
