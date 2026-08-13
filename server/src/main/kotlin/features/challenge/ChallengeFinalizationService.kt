package com.revio.server.features.challenge

import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A session-scoped Postgres advisory lock keyed by an arbitrary [Long]. Deliberately not backed
 * by Exposed's `transaction { }` DSL: that binds a `Transaction` to the calling thread's
 * ThreadLocal for its duration, which would make every later `transaction { }` call made while
 * the lock is held nest into it instead of opening its own independently-committing transaction
 * — silently breaking a caller like [IChallengeProgressDAO.finalizeParticipants]'s
 * one-transaction-per-participant crash-recovery guarantee. A raw, unmanaged JDBC connection
 * held only for the lock itself avoids that entirely.
 */
interface IAdvisoryLock {
    /** Attempts to acquire the lock for [key] without blocking. Returns whether it was acquired. */
    fun tryAcquire(key: Long): Boolean

    /** Releases the lock for [key]. Only valid to call after a successful [tryAcquire] on it. */
    fun release(key: Long)
}

class PostgresAdvisoryLock : IAdvisoryLock {
    override fun tryAcquire(key: Long): Boolean {
        val connection = rawConnection()
        return try {
            val acquired = connection.prepareStatement("SELECT pg_try_advisory_lock(?)").use { statement ->
                statement.setLong(1, key)
                statement.executeQuery().use { it.next() && it.getBoolean(1) }
            }
            connection.commit()
            if (acquired) heldConnections[key] = connection else connection.close()
            acquired
        } catch (e: Exception) {
            connection.close()
            throw e
        }
    }

    override fun release(key: Long) {
        val connection = heldConnections.remove(key) ?: return
        try {
            connection.prepareStatement("SELECT pg_advisory_unlock(?)").use { statement ->
                statement.setLong(1, key)
                statement.executeQuery()
            }
            connection.commit()
        } finally {
            connection.close()
        }
    }

    /** One held-open connection per currently-acquired key, so [release] unlocks the same session that acquired it. */
    private val heldConnections = ConcurrentHashMap<Long, Connection>()

    private fun rawConnection(): Connection = TransactionManager.defaultDatabase!!.connector().connection as Connection
}

/**
 * Reconciles one challenge's rewards after its window has ended — the single finalization code
 * path shared by every trigger (manual admin endpoint, cron sweep, lazy catch-up probe; plan
 * §9-C5/C6/C8). Guards against anything that isn't actually due: the challenge must be
 * SCHEDULED, its window must have ended, and it must not already be finalized — a
 * DRAFT/CANCELLED/still-active/already-finalized challenge is left untouched.
 */
interface IChallengeFinalizationService {
    /**
     * Finalizes [challengeId] if it is due as of [now] (see class doc). Returns null when the
     * challenge doesn't exist or isn't due; otherwise the [FinalizationResult] from reconciling
     * its participants ([IChallengeProgressDAO.finalizeParticipants]).
     *
     * `finalized_at` is written only after participants have been fully reconciled, so a crash
     * mid-way leaves it NULL and the next call — cron, lazy probe, or manual retry — picks the
     * challenge back up. finalizeParticipants is itself idempotent, so re-running it on a
     * partially-processed challenge is safe.
     */
    suspend fun finalize(challengeId: UUID, now: Instant = Instant.now()): FinalizationResult?
}

class ChallengeFinalizationService(
    private val challengeDao: IChallengeDAO,
    private val challengeProgressDao: IChallengeProgressDAO,
    private val advisoryLock: IAdvisoryLock = PostgresAdvisoryLock(),
) : IChallengeFinalizationService {

    /**
     * Holds [advisoryLock] for [challengeId] (derived from half its UUID bits) across the whole
     * call — dedup between two triggers racing on the same challenge. This is purely a
     * work-avoidance optimization, not a correctness requirement: [reconcile] and
     * finalizeParticipants already serialize correctly per-participant via `SELECT ... FOR
     * UPDATE`, so two concurrent finalizes of the same challenge produce the same end result
     * even without this lock.
     */
    override suspend fun finalize(challengeId: UUID, now: Instant): FinalizationResult? {
        val lockKey = challengeId.mostSignificantBits
        if (!advisoryLock.tryAcquire(lockKey)) return null
        try {
            return reconcile(challengeId, now)
        } finally {
            advisoryLock.release(lockKey)
        }
    }

    private suspend fun reconcile(challengeId: UUID, now: Instant): FinalizationResult? {
        val challenge = challengeDao.findById(challengeId) ?: return null
        if (challenge.status != ChallengeStatus.SCHEDULED) return null
        if (challenge.endsAt.isAfter(now)) return null
        if (challenge.finalizedAt != null) return null

        val result = challengeProgressDao.finalizeParticipants(challengeId)
        challengeDao.markFinalized(challengeId, now)
        return result
    }
}
