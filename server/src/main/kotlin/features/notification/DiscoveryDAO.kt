package com.revio.server.features.notification

import com.revio.server.features.post.PostTable
import com.revio.server.features.user.UserTable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * One user old enough to be considered for a discovery send (plan §8.3 / §18, step 6.3) — before
 * the content threshold, feed-open gate, weekly cap, or quiet hours are applied. Those all need
 * per-candidate facts ([IUserNotificationPrefsDAO] prefs, a device timezone, a weekly send count)
 * fetched separately, so this only carries what a single `users` query can cheaply give.
 */
data class DiscoveryCandidate(
    val userId: UUID,
    val country: String,
    val createdAt: Instant,
    val lastFeedOpenAt: Instant?,
)

interface IDiscoveryDAO {
    /** Users whose account is at least [DISCOVERY_MIN_ACCOUNT_AGE_DAYS] old, as of [now]. */
    suspend fun findCandidates(now: Instant): List<DiscoveryCandidate>

    /** Plan §8.3's "Personalizare v1": posts scoped to [country], created after [since]. */
    suspend fun countNewPostsInCountrySince(country: String, since: Instant): Int

    /**
     * The timezone of [userId]'s most recently active device, or null if they have none/it's
     * unset — the same "quiet hours can't be computed, must not guess" case
     * [INotificationPolicyService.evaluate] already handles explicitly for null zones.
     */
    suspend fun findMostRecentDeviceTimezone(userId: UUID): String?

    /**
     * How many DISCOVERY notifications [userId] has already been sent in the 7 days before
     * [now] — plan §8.3/§15's weekly cap. Counts `user_notifications` rows rather than outbox
     * sends: one row is created per discovery decision to notify, regardless of downstream FCM
     * delivery, which is the same "we decided to notify" semantics the cap is meant to bound.
     */
    suspend fun countDiscoverySentSince(userId: UUID, since: Instant): Int
}

class DiscoveryDAO : IDiscoveryDAO {

    override suspend fun findCandidates(now: Instant): List<DiscoveryCandidate> = transaction {
        val cutoff = now.minus(Duration.ofDays(DISCOVERY_MIN_ACCOUNT_AGE_DAYS))
        UserTable
            .select(UserTable.id, UserTable.country, UserTable.createdAt, UserTable.lastFeedOpenAt)
            .where { UserTable.createdAt lessEq cutoff }
            .map {
                DiscoveryCandidate(
                    userId = it[UserTable.id].value,
                    country = it[UserTable.country],
                    createdAt = it[UserTable.createdAt],
                    lastFeedOpenAt = it[UserTable.lastFeedOpenAt],
                )
            }
    }

    override suspend fun countNewPostsInCountrySince(country: String, since: Instant): Int = transaction {
        PostTable
            .select(PostTable.id)
            .where { (PostTable.country eq country) and (PostTable.createdAt greater since) }
            .count()
            .toInt()
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

    override suspend fun countDiscoverySentSince(userId: UUID, since: Instant): Int = transaction {
        NotificationTable
            .selectAll()
            .where {
                (NotificationTable.userId eq userId) and
                    (NotificationTable.category eq NotificationCategory.DISCOVERY) and
                    (NotificationTable.createdAt greater since)
            }
            .count()
            .toInt()
    }
}
