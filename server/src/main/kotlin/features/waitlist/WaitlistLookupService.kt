package com.revio.server.features.waitlist

import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Duration
import java.time.OffsetDateTime

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
 * 2. if not found locally AND the last successful sync is older than [FRESHNESS_THRESHOLD] (or
 *    unknown) — a live lookup against Supabase with a short timeout. The result is upserted
 *    locally before being returned, so a repeat lookup for the same email hits the local copy;
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
        private val FRESHNESS_THRESHOLD: Duration = Duration.ofSeconds(60)
        private const val LIVE_LOOKUP_TIMEOUT_MILLIS = 1500L
        private const val CIRCUIT_BREAKER_FAILURE_THRESHOLD = 3
        private val CIRCUIT_BREAKER_COOLDOWN: Duration = Duration.ofMinutes(5)
    }

    @Volatile
    private var consecutiveFailures = 0

    @Volatile
    private var circuitOpenUntil: OffsetDateTime? = null

    override suspend fun lookup(normalizedEmail: String): WaitlistEntry? {
        val local = safeLocalLookup(normalizedEmail)
        if (local != null) return local

        if (!shouldAttemptLiveLookup()) return null

        return liveLookup(normalizedEmail)
    }

    private suspend fun safeLocalLookup(normalizedEmail: String): WaitlistEntry? = try {
        waitlistDao.findByNormalizedEmail(normalizedEmail)
    } catch (e: Exception) {
        logger.error("Local waitlist lookup failed for {}", hashEmailForLogging(normalizedEmail), e)
        null
    }

    private suspend fun shouldAttemptLiveLookup(): Boolean {
        val openUntil = circuitOpenUntil
        if (openUntil != null && OffsetDateTime.now().isBefore(openUntil)) return false

        val lastSync = try {
            waitlistDao.lastSyncedAt()
        } catch (e: Exception) {
            logger.error("Failed to read waitlist last-sync watermark", e)
            null
        }
        return lastSync == null || Duration.between(lastSync, OffsetDateTime.now()) > FRESHNESS_THRESHOLD
    }

    private suspend fun liveLookup(normalizedEmail: String): WaitlistEntry? {
        val row = try {
            val fetched = withTimeout(LIVE_LOOKUP_TIMEOUT_MILLIS) {
                supabaseClient.fetchByEmail(normalizedEmail)
            }
            onLiveLookupSuccess()
            fetched
        } catch (e: Exception) {
            onLiveLookupFailure()
            logger.warn("Live waitlist lookup failed for {}", hashEmailForLogging(normalizedEmail), e)
            return null
        }

        row ?: return null
        return persistAndReturn(row)
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
