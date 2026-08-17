package com.revio.server.features.waitlist

import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.TextColumnType
import org.jetbrains.exposed.sql.UUIDColumnType
import org.jetbrains.exposed.sql.javatime.JavaOffsetDateTimeColumnType
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.StatementType
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.OffsetDateTime
import java.util.UUID

/** One row to upsert into the local waitlist copy, sourced from Supabase. */
data class WaitlistUpsertRow(
    val id: UUID,
    val email: String,
    val username: String?,
    val platform: String?,
    val country: String?,
    val sourceCreatedAt: OffsetDateTime,
    val sourceUpdatedAt: OffsetDateTime?,
)

/** Outcome of upserting a single [WaitlistUpsertRow] — see [IWaitlistDAO.upsertBatch]. */
private enum class WaitlistRowOutcome { INSERTED, UPDATED, UNCHANGED, CONFLICTED }

/**
 * Aggregate result of [IWaitlistDAO.upsertBatch]. [unchanged] counts rows whose id already
 * existed but whose incoming source_updated_at was not newer than what's stored (a stale or
 * duplicate retry). [conflicted] counts rows whose email collided with a *different* existing
 * row's email_normalized — a genuine data problem in the source, not something this batch can
 * resolve on its own.
 */
data class WaitlistUpsertResult(
    val inserted: Int,
    val updated: Int,
    val unchanged: Int,
    val conflicted: Int,
)

interface IWaitlistDAO {
    /**
     * Upserts [rows] one at a time, each in its own transaction, so a unique-email conflict on
     * one row can never abort the rest of the batch (a shared transaction would: Postgres aborts
     * the whole transaction on the first unhandled error, and catching the exception in Kotlin
     * doesn't un-abort it). Conflict resolution targets [WaitlistTable.id] — the Supabase row id
     * — so the same row synced N times is still exactly one row. An existing row is only
     * overwritten when the incoming source_updated_at is newer (or the existing one is null), so
     * an out-of-order retry can never regress data. Never deletes: rows missing from [rows] are
     * left untouched.
     */
    suspend fun upsertBatch(rows: List<WaitlistUpsertRow>): WaitlistUpsertResult

    /** Looks up a row by email, normalized the same way as [WaitlistTable.emailNormalized] (trim + lowercase). */
    suspend fun findByNormalizedEmail(email: String): WaitlistEntry?

    suspend fun countAll(): Long

    /** Latest source_updated_at across all rows, or null if the table is empty or all values are null. */
    suspend fun maxSourceUpdatedAt(): OffsetDateTime?

    /** Latest synced_at across all rows, or null if the table is empty. */
    suspend fun lastSyncedAt(): OffsetDateTime?
}

class WaitlistDAO : IWaitlistDAO {

    override suspend fun upsertBatch(rows: List<WaitlistUpsertRow>): WaitlistUpsertResult {
        var inserted = 0
        var updated = 0
        var unchanged = 0
        var conflicted = 0

        for (row in rows) {
            when (upsertOne(row)) {
                WaitlistRowOutcome.INSERTED -> inserted++
                WaitlistRowOutcome.UPDATED -> updated++
                WaitlistRowOutcome.UNCHANGED -> unchanged++
                WaitlistRowOutcome.CONFLICTED -> conflicted++
            }
        }

        return WaitlistUpsertResult(inserted, updated, unchanged, conflicted)
    }

    /**
     * Own transaction per row (see [IWaitlistDAO.upsertBatch] for why). Uses xmax = 0 to tell an
     * insert from an update on the returned row — the standard Postgres idiom for this, safe here
     * since it's read back within the very same command. When the ON CONFLICT ... WHERE condition
     * is false, Postgres performs neither an insert nor an update for that row and RETURNING
     * yields no row at all, which is read here as [WaitlistRowOutcome.UNCHANGED].
     */
    private fun upsertOne(row: WaitlistUpsertRow): WaitlistRowOutcome = try {
        transaction {
            val isInsert = exec(
                """
                INSERT INTO waitlist_signups
                    (id, email, username, platform, country, source_created_at, source_updated_at, synced_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, now(), now())
                ON CONFLICT (id) DO UPDATE SET
                    email = EXCLUDED.email,
                    username = EXCLUDED.username,
                    platform = EXCLUDED.platform,
                    country = EXCLUDED.country,
                    source_created_at = EXCLUDED.source_created_at,
                    source_updated_at = EXCLUDED.source_updated_at,
                    synced_at = now(),
                    updated_at = now()
                WHERE waitlist_signups.source_updated_at IS NULL
                   OR EXCLUDED.source_updated_at > waitlist_signups.source_updated_at
                RETURNING (xmax = 0) AS is_insert
                """.trimIndent(),
                args = listOf(
                    UUIDColumnType() to row.id,
                    TextColumnType() to row.email,
                    TextColumnType() to row.username,
                    TextColumnType() to row.platform,
                    TextColumnType() to row.country,
                    JavaOffsetDateTimeColumnType() to row.sourceCreatedAt,
                    JavaOffsetDateTimeColumnType() to row.sourceUpdatedAt,
                ),
                explicitStatementType = StatementType.SELECT,
            ) { rs -> if (rs.next()) rs.getBoolean("is_insert") else null }

            when (isInsert) {
                true -> WaitlistRowOutcome.INSERTED
                false -> WaitlistRowOutcome.UPDATED
                null -> WaitlistRowOutcome.UNCHANGED
            }
        }
    } catch (e: ExposedSQLException) {
        WaitlistRowOutcome.CONFLICTED
    }

    override suspend fun findByNormalizedEmail(email: String): WaitlistEntry? = transaction {
        WaitlistTable
            .selectAll()
            .where { WaitlistTable.emailNormalized eq email.trim().lowercase() }
            .limit(1)
            .map { it.toEntry() }
            .singleOrNull()
    }

    override suspend fun countAll(): Long = transaction {
        WaitlistTable.selectAll().count()
    }

    override suspend fun maxSourceUpdatedAt(): OffsetDateTime? = transaction {
        WaitlistTable
            .select(WaitlistTable.sourceUpdatedAt)
            .orderBy(WaitlistTable.sourceUpdatedAt, SortOrder.DESC_NULLS_LAST)
            .limit(1)
            .map { it[WaitlistTable.sourceUpdatedAt] }
            .firstOrNull()
    }

    override suspend fun lastSyncedAt(): OffsetDateTime? = transaction {
        WaitlistTable
            .select(WaitlistTable.syncedAt)
            .orderBy(WaitlistTable.syncedAt, SortOrder.DESC)
            .limit(1)
            .map { it[WaitlistTable.syncedAt] }
            .firstOrNull()
    }

    private fun ResultRow.toEntry() = WaitlistEntry(
        id = this[WaitlistTable.id].value,
        email = this[WaitlistTable.email],
        emailNormalized = this[WaitlistTable.emailNormalized],
        username = this[WaitlistTable.username],
        platform = this[WaitlistTable.platform],
        country = this[WaitlistTable.country],
        sourceCreatedAt = this[WaitlistTable.sourceCreatedAt],
        sourceUpdatedAt = this[WaitlistTable.sourceUpdatedAt],
        syncedAt = this[WaitlistTable.syncedAt],
    )
}
