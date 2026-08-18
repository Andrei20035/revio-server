package com.revio.server.features.waitlist

import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap

interface IWaitlistLookupService {
    /**
     * Looks up [normalizedEmail] (already trim+lowercased by the caller) in the waitlist.
     * Never throws: any failure — local DB error, Supabase unreachable, timeout — is caught,
     * logged, and treated as "not in waitlist", so this can never fail registration or login.
     */
    suspend fun lookup(normalizedEmail: String): WaitlistEntry?
}

/**
 * Recognizes waitlist membership at auth time. Resolution order:
 * 1. local copy (fast, primary source);
 * 2. if not found locally AND this exact email hasn't already had a negative live lookup within
 *    [NEGATIVE_LOOKUP_TTL] — a live lookup against Supabase with a short timeout. The result is
 *    upserted locally before being returned, so a repeat lookup for the same email hits the local
 *    copy. Gating per-email (instead of on a table-wide last-sync watermark) means a signup that
 *    hasn't synced yet is never starved by an unrelated row syncing elsewhere;
 * 3. if Supabase is unreachable or times out, falls back to "not in waitlist". A circuit breaker
 *    disables live lookups for [CIRCUIT_BREAKER_COOLDOWN] after
 *    [CIRCUIT_BREAKER_FAILURE_THRESHOLD] consecutive failures, so an outage doesn't add latency
 *    to every registration attempt.
 */
class WaitlistLookupService(
    private val waitlistDao: IWaitlistDAO,
    private val supabaseClient: ISupabaseWaitlistClient,
) : IWaitlistLookupService {

    companion object {
        private val logger = LoggerFactory.getLogger(WaitlistLookupService::class.java)
        private val NEGATIVE_LOOKUP_TTL: Duration = Duration.ofSeconds(60)
        private const val LIVE_LOOKUP_TIMEOUT_MILLIS = 1500L
        private const val CIRCUIT_BREAKER_FAILURE_THRESHOLD = 3
        private val CIRCUIT_BREAKER_COOLDOWN: Duration = Duration.ofMinutes(5)
    }

    @Volatile
    private var consecutiveFailures = 0

    @Volatile
    private var circuitOpenUntil: OffsetDateTime? = null

    /** Per-email cooldown after a live lookup found nothing — see class doc point 2. */
    private val recentLiveMisses = ConcurrentHashMap<String, OffsetDateTime>()

    /** Where a [lookup] result came from — logged so a miss caused by a Supabase timeout is never confused with a legitimate one. */
    private enum class LookupOutcome { LOCAL_HIT, LIVE_HIT, LIVE_MISS, SKIPPED_FRESH, CIRCUIT_OPEN, ERROR }

    override suspend fun lookup(normalizedEmail: String): WaitlistEntry? {
        val startedAtNanos = System.nanoTime()

        val local = safeLocalLookup(normalizedEmail)
        if (local != null) {
            logOutcome(normalizedEmail, LookupOutcome.LOCAL_HIT, startedAtNanos)
            return local
        }

        if (!shouldAttemptLiveLookup(normalizedEmail)) {
            logOutcome(normalizedEmail, skipReason(), startedAtNanos)
            return null
        }

        val (result, outcome) = liveLookup(normalizedEmail)
        logOutcome(normalizedEmail, outcome, startedAtNanos)
        return result
    }

    private fun skipReason(): LookupOutcome {
        val openUntil = circuitOpenUntil
        return if (openUntil != null && OffsetDateTime.now().isBefore(openUntil)) {
            LookupOutcome.CIRCUIT_OPEN
        } else {
            LookupOutcome.SKIPPED_FRESH
        }
    }

    private fun logOutcome(normalizedEmail: String, outcome: LookupOutcome, startedAtNanos: Long) {
        val durationMillis = (System.nanoTime() - startedAtNanos) / 1_000_000
        logger.info(
            "Waitlist lookup for {} -> {} ({} ms)",
            hashEmailForLogging(normalizedEmail),
            outcome,
            durationMillis,
        )
    }

    private suspend fun safeLocalLookup(normalizedEmail: String): WaitlistEntry? = try {
        waitlistDao.findByNormalizedEmail(normalizedEmail)
    } catch (e: Exception) {
        logger.error("Local waitlist lookup failed for {}", hashEmailForLogging(normalizedEmail), e)
        null
    }

    private fun shouldAttemptLiveLookup(normalizedEmail: String): Boolean {
        val openUntil = circuitOpenUntil
        if (openUntil != null && OffsetDateTime.now().isBefore(openUntil)) return false

        val lastMiss = recentLiveMisses[normalizedEmail] ?: return true
        return Duration.between(lastMiss, OffsetDateTime.now()) > NEGATIVE_LOOKUP_TTL
    }

    private suspend fun liveLookup(normalizedEmail: String): Pair<WaitlistEntry?, LookupOutcome> {
        val row = try {
            val fetched = withTimeout(LIVE_LOOKUP_TIMEOUT_MILLIS) {
                supabaseClient.fetchByEmail(normalizedEmail)
            }
            onLiveLookupSuccess()
            fetched
        } catch (e: Exception) {
            onLiveLookupFailure()
            logger.warn("Live waitlist lookup failed for {}", hashEmailForLogging(normalizedEmail), e)
            return null to LookupOutcome.ERROR
        }

        if (row == null) {
            recordLiveMiss(normalizedEmail)
            return null to LookupOutcome.LIVE_MISS
        }
        recentLiveMisses.remove(normalizedEmail)
        return persistAndReturn(row) to LookupOutcome.LIVE_HIT
    }

    /** Records the miss and opportunistically evicts stale entries so the map doesn't grow unbounded. */
    private fun recordLiveMiss(normalizedEmail: String) {
        val now = OffsetDateTime.now()
        recentLiveMisses[normalizedEmail] = now
        recentLiveMisses.entries.removeIf { (_, missedAt) -> Duration.between(missedAt, now) > NEGATIVE_LOOKUP_TTL }
    }

    private suspend fun persistAndReturn(row: WaitlistUpsertRow): WaitlistEntry? = try {
        waitlistDao.upsertBatch(listOf(row))
        waitlistDao.findByNormalizedEmail(row.email.trim().lowercase())
    } catch (e: Exception) {
        logger.error("Failed to persist live waitlist lookup result for {}", hashEmailForLogging(row.email), e)
        null
    }

    @Synchronized
    private fun onLiveLookupSuccess() {
        consecutiveFailures = 0
        circuitOpenUntil = null
    }

    @Synchronized
    private fun onLiveLookupFailure() {
        consecutiveFailures++
        if (consecutiveFailures >= CIRCUIT_BREAKER_FAILURE_THRESHOLD) {
            circuitOpenUntil = OffsetDateTime.now().plus(CIRCUIT_BREAKER_COOLDOWN)
        }
    }

    /** Truncated SHA-256 of the email — never log emails in clear text. */
    private fun hashEmailForLogging(email: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(email.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(12)
    }
}
