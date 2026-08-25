package com.revio.server.features.notification

import com.revio.server.features.post.PostTable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.UUID

/**
 * One user with at least one spot ever (plan §8.4's "≥1 spot vreodată" eligibility row) — the
 * cheapest possible candidate list, before prefs/ban/last-app-open/day-count are applied.
 */
data class InactivityCandidate(
    val userId: UUID,
    val lastPostAt: Instant,
)

interface IInactivityDAO {
    /** Every user who has ever posted, with their most recent post's timestamp. */
    suspend fun findCandidates(): List<InactivityCandidate>

    /**
     * The timezone of [userId]'s most recently active device, or null if they have none/it's
     * unset. Mirrors [DiscoveryDAO.findMostRecentDeviceTimezone] exactly — kept as its own copy
     * here rather than shared, so step 6.4 doesn't need to touch DiscoveryDAO.kt.
     */
    suspend fun findMostRecentDeviceTimezone(userId: UUID): String?

    /** `MAX(user_devices.last_seen_at)` for [userId] — plan §8.4's `last_app_open`, null if the user has no devices. */
    suspend fun findLastAppOpen(userId: UUID): Instant?
}

class InactivityDAO : IInactivityDAO {

    override suspend fun findCandidates(): List<InactivityCandidate> = transaction {
        val maxCreatedAt = PostTable.createdAt.max()
        PostTable
            .select(PostTable.userId, maxCreatedAt)
            .groupBy(PostTable.userId)
            .mapNotNull { row ->
                val lastPostAt = row[maxCreatedAt] ?: return@mapNotNull null
                InactivityCandidate(userId = row[PostTable.userId], lastPostAt = lastPostAt)
            }
    }

    override suspend fun findMostRecentDeviceTimezone(userId: UUID): String? = transaction {
        UserDeviceTable
            .select(UserDeviceTable.timezone)
            .where { (UserDeviceTable.userId eq userId) and (UserDeviceTable.isActive eq true) }
            .orderBy(UserDeviceTable.lastSeenAt, SortOrder.DESC)
            .limit(1)
            .map { it[UserDeviceTable.timezone] }
            .singleOrNull()
    }

    override suspend fun findLastAppOpen(userId: UUID): Instant? = transaction {
        val maxLastSeen = UserDeviceTable.lastSeenAt.max()
        UserDeviceTable
            .select(maxLastSeen)
            .where { UserDeviceTable.userId eq userId }
            .map { it[maxLastSeen] }
            .singleOrNull()
            ?.toInstant()
    }
}
