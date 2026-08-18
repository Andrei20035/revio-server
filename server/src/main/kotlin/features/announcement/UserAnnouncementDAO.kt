package com.revio.server.features.announcement

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.util.UUID

data class UserAnnouncement(
    val announcementKey: AnnouncementKey,
    val status: AnnouncementStatus,
    val payload: String?,
    val createdAt: OffsetDateTime,
    val seenAt: OffsetDateTime?,
)

interface IUserAnnouncementDAO {
    /** PENDING announcements for [userId], oldest first. */
    suspend fun getPending(userId: UUID): List<UserAnnouncement>

    /**
     * Marks [key] SEEN for [userId]. Idempotent: acknowledging an already-SEEN row, or a key that
     * was never created for this user, is a no-op rather than an error — a retried or
     * out-of-order client acknowledgement must never fail.
     */
    suspend fun acknowledge(userId: UUID, key: AnnouncementKey)
}

class UserAnnouncementDAO : IUserAnnouncementDAO {
    override suspend fun getPending(userId: UUID): List<UserAnnouncement> = transaction {
        UserAnnouncementTable
            .selectAll()
            .where {
                (UserAnnouncementTable.userId eq userId) and
                    (UserAnnouncementTable.status eq AnnouncementStatus.PENDING.name)
            }
            .orderBy(UserAnnouncementTable.createdAt)
            .map { it.toUserAnnouncement() }
    }

    override suspend fun acknowledge(userId: UUID, key: AnnouncementKey): Unit = transaction {
        UserAnnouncementTable.update({
            (UserAnnouncementTable.userId eq userId) and
                (UserAnnouncementTable.announcementKey eq key.name) and
                (UserAnnouncementTable.status eq AnnouncementStatus.PENDING.name)
        }) {
            it[status] = AnnouncementStatus.SEEN.name
            it[seenAt] = OffsetDateTime.now()
        }
        Unit
    }

    private fun ResultRow.toUserAnnouncement() = UserAnnouncement(
        announcementKey = AnnouncementKey.valueOf(this[UserAnnouncementTable.announcementKey]),
        status = AnnouncementStatus.valueOf(this[UserAnnouncementTable.status]),
        payload = this[UserAnnouncementTable.payload],
        createdAt = this[UserAnnouncementTable.createdAt],
        seenAt = this[UserAnnouncementTable.seenAt],
    )
}
