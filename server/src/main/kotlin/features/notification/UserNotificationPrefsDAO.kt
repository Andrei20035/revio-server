package com.revio.server.features.notification

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID

data class UserNotificationPrefs(
    val userId: UUID,
    val likesEnabled: Boolean,
    val commentsEnabled: Boolean,
    val discoveryEnabled: Boolean,
    val remindersEnabled: Boolean,
    val quietStart: LocalTime,
    val quietEnd: LocalTime,
)

interface IUserNotificationPrefsDAO {
    /**
     * Reads [userId]'s preferences. A user with no row is read as every category enabled and the
     * default quiet hours — [UserNotificationPrefsDefaults] — without creating a row.
     */
    suspend fun get(userId: UUID): UserNotificationPrefs

    /**
     * Applies a partial update, upserting the row. Only non-null parameters are changed; omitted
     * ([null]) fields keep their current value (or the default, if the row doesn't exist yet).
     */
    suspend fun update(
        userId: UUID,
        likesEnabled: Boolean? = null,
        commentsEnabled: Boolean? = null,
        discoveryEnabled: Boolean? = null,
        remindersEnabled: Boolean? = null,
        quietStart: LocalTime? = null,
        quietEnd: LocalTime? = null,
    ): UserNotificationPrefs
}

class UserNotificationPrefsDAO : IUserNotificationPrefsDAO {

    override suspend fun get(userId: UUID): UserNotificationPrefs = transaction {
        UserNotificationPrefsTable
            .selectAll()
            .where { UserNotificationPrefsTable.userId eq userId }
            .singleOrNull()
            ?.toPrefs()
            ?: defaultPrefs(userId)
    }

    override suspend fun update(
        userId: UUID,
        likesEnabled: Boolean?,
        commentsEnabled: Boolean?,
        discoveryEnabled: Boolean?,
        remindersEnabled: Boolean?,
        quietStart: LocalTime?,
        quietEnd: LocalTime?,
    ): UserNotificationPrefs = transaction {
        val existing = UserNotificationPrefsTable
            .selectAll()
            .where { UserNotificationPrefsTable.userId eq userId }
            .singleOrNull()
            ?.toPrefs()

        if (existing != null) {
            UserNotificationPrefsTable.update({ UserNotificationPrefsTable.userId eq userId }) {
                likesEnabled?.let { v -> it[UserNotificationPrefsTable.likesEnabled] = v }
                commentsEnabled?.let { v -> it[UserNotificationPrefsTable.commentsEnabled] = v }
                discoveryEnabled?.let { v -> it[UserNotificationPrefsTable.discoveryEnabled] = v }
                remindersEnabled?.let { v -> it[UserNotificationPrefsTable.remindersEnabled] = v }
                quietStart?.let { v -> it[UserNotificationPrefsTable.quietStart] = v }
                quietEnd?.let { v -> it[UserNotificationPrefsTable.quietEnd] = v }
                it[UserNotificationPrefsTable.updatedAt] = Instant.now().atOffset(ZoneOffset.UTC)
            }
            existing.copy(
                likesEnabled = likesEnabled ?: existing.likesEnabled,
                commentsEnabled = commentsEnabled ?: existing.commentsEnabled,
                discoveryEnabled = discoveryEnabled ?: existing.discoveryEnabled,
                remindersEnabled = remindersEnabled ?: existing.remindersEnabled,
                quietStart = quietStart ?: existing.quietStart,
                quietEnd = quietEnd ?: existing.quietEnd,
            )
        } else {
            val defaults = defaultPrefs(userId)
            val toInsert = defaults.copy(
                likesEnabled = likesEnabled ?: defaults.likesEnabled,
                commentsEnabled = commentsEnabled ?: defaults.commentsEnabled,
                discoveryEnabled = discoveryEnabled ?: defaults.discoveryEnabled,
                remindersEnabled = remindersEnabled ?: defaults.remindersEnabled,
                quietStart = quietStart ?: defaults.quietStart,
                quietEnd = quietEnd ?: defaults.quietEnd,
            )
            UserNotificationPrefsTable.insert {
                it[UserNotificationPrefsTable.userId] = toInsert.userId
                it[UserNotificationPrefsTable.likesEnabled] = toInsert.likesEnabled
                it[UserNotificationPrefsTable.commentsEnabled] = toInsert.commentsEnabled
                it[UserNotificationPrefsTable.discoveryEnabled] = toInsert.discoveryEnabled
                it[UserNotificationPrefsTable.remindersEnabled] = toInsert.remindersEnabled
                it[UserNotificationPrefsTable.quietStart] = toInsert.quietStart
                it[UserNotificationPrefsTable.quietEnd] = toInsert.quietEnd
            }
            toInsert
        }
    }

    private fun defaultPrefs(userId: UUID) = UserNotificationPrefs(
        userId = userId,
        likesEnabled = UserNotificationPrefsDefaults.LIKES_ENABLED,
        commentsEnabled = UserNotificationPrefsDefaults.COMMENTS_ENABLED,
        discoveryEnabled = UserNotificationPrefsDefaults.DISCOVERY_ENABLED,
        remindersEnabled = UserNotificationPrefsDefaults.REMINDERS_ENABLED,
        quietStart = UserNotificationPrefsDefaults.QUIET_START,
        quietEnd = UserNotificationPrefsDefaults.QUIET_END,
    )

    private fun ResultRow.toPrefs() = UserNotificationPrefs(
        userId = this[UserNotificationPrefsTable.userId],
        likesEnabled = this[UserNotificationPrefsTable.likesEnabled],
        commentsEnabled = this[UserNotificationPrefsTable.commentsEnabled],
        discoveryEnabled = this[UserNotificationPrefsTable.discoveryEnabled],
        remindersEnabled = this[UserNotificationPrefsTable.remindersEnabled],
        quietStart = this[UserNotificationPrefsTable.quietStart],
        quietEnd = this[UserNotificationPrefsTable.quietEnd],
    )
}
