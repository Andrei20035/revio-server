package com.revio.server.features.waitlist

import kotlinx.coroutines.delay
import java.time.OffsetDateTime

/**
 * Aggregate result of one [IWaitlistSyncService.reconcile] run. [success] is false only when
 * Supabase could not be reached (all retries exhausted) — the fetched/applied counters still
 * reflect whatever pages committed before the failure, since each page's upsert is its own
 * commit and is never rolled back by a later page's failure.
 */
data class WaitlistSyncReport(
    val success: Boolean,
    val rowsFetched: Int,
    val inserted: Int,
    val updated: Int,
    val unchanged: Int,
    val conflicted: Int,
    val watermarkBefore: OffsetDateTime?,
    val watermarkAfter: OffsetDateTime?,
)

interface IWaitlistSyncService {
    /**
     * Reconciles the local waitlist copy with Supabase.
     *
     * When [since] is null (the default), the watermark is computed from the local copy's own
     * [IWaitlistDAO.maxSourceUpdatedAt], minus a 5-minute overlap — deliberate and free, since
     * [IWaitlistDAO.upsertBatch] is idempotent and only advances a row when the incoming
     * source_updated_at is strictly newer. When the local copy is empty and [since] is null, no
     * lower bound is sent to Supabase at all: this is exactly how the initial import works, it is
     * not a separate code path.
     *
     * Never deletes: a row present locally but missing from a fetched page (because it no longer
     * matches the `updated_at` filter, or was removed from Supabase) is left untouched.
     *
     * On a Supabase failure — after [MAX_ATTEMPTS] retries with exponential backoff — the
     * exception is caught here and never escapes this method; the returned report has
     * `success = false`, and nothing about the local watermark is advanced beyond whatever pages
     * already committed.
     */
    suspend fun reconcile(since: OffsetDateTime? = null): WaitlistSyncReport

    /** Applies a single webhook-delivered row through the exact same upsert path as [reconcile]. */
    suspend fun applyEvent(row: WaitlistUpsertRow): WaitlistUpsertResult
}

class WaitlistSyncService(
    private val client: ISupabaseWaitlistClient,
    private val waitlistDao: IWaitlistDAO,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
) : IWaitlistSyncService {

    companion object {
        private const val DEFAULT_PAGE_SIZE = 200
        private const val OVERLAP_MINUTES = 5L
        private const val MAX_ATTEMPTS = 3
        private const val INITIAL_BACKOFF_MILLIS = 500L
    }

    override suspend fun reconcile(since: OffsetDateTime?): WaitlistSyncReport {
        val watermarkBefore = since ?: waitlistDao.maxSourceUpdatedAt()?.minusMinutes(OVERLAP_MINUTES)

        var rowsFetched = 0
        var inserted = 0
        var updated = 0
        var unchanged = 0
        var conflicted = 0
        var offset = 0

        try {
            while (true) {
                val page = fetchPageWithRetry(watermarkBefore, offset, pageSize)
                if (page.isEmpty()) break

                rowsFetched += page.size
                val result = waitlistDao.upsertBatch(page)
                inserted += result.inserted
                updated += result.updated
                unchanged += result.unchanged
                conflicted += result.conflicted

                if (page.size < pageSize) break
                offset += pageSize
            }
        } catch (e: Exception) {
            return WaitlistSyncReport(
                success = false,
                rowsFetched = rowsFetched,
                inserted = inserted,
                updated = updated,
                unchanged = unchanged,
                conflicted = conflicted,
                watermarkBefore = watermarkBefore,
                watermarkAfter = waitlistDao.maxSourceUpdatedAt(),
            )
        }

        return WaitlistSyncReport(
            success = true,
            rowsFetched = rowsFetched,
            inserted = inserted,
            updated = updated,
            unchanged = unchanged,
            conflicted = conflicted,
            watermarkBefore = watermarkBefore,
            watermarkAfter = waitlistDao.maxSourceUpdatedAt(),
        )
    }

    override suspend fun applyEvent(row: WaitlistUpsertRow): WaitlistUpsertResult =
        waitlistDao.upsertBatch(listOf(row))

    private suspend fun fetchPageWithRetry(since: OffsetDateTime?, offset: Int, limit: Int): List<WaitlistUpsertRow> {
        var attempt = 0
        var lastError: Exception? = null
        while (attempt < MAX_ATTEMPTS) {
            try {
                return client.fetchPage(since, offset, limit)
            } catch (e: Exception) {
                lastError = e
                attempt++
                if (attempt < MAX_ATTEMPTS) {
                    delay(INITIAL_BACKOFF_MILLIS * (1L shl (attempt - 1)))
                }
            }
        }
        throw lastError ?: IllegalStateException("fetchPage failed with no captured exception")
    }
}
