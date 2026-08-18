package com.revio.server.features.announcement

import com.revio.server.features.announcement.dto.AnnouncementDTO
import java.util.UUID

interface IAnnouncementService {
    /** PENDING announcements for [userId] — the recovery path after restart/relogin/another device. */
    suspend fun getPending(userId: UUID): List<AnnouncementDTO>

    /**
     * Marks [key] SEEN for [userId]. Idempotent: acknowledging an already-SEEN announcement, or a
     * key that was never created for this user, is a no-op — a retried or out-of-order client
     * acknowledgement must never fail. Throws [IllegalArgumentException] only when [key] isn't a
     * recognized announcement kind at all (a genuine client bug, not a timing race).
     */
    suspend fun acknowledge(userId: UUID, key: String)
}

class AnnouncementService(
    private val dao: IUserAnnouncementDAO,
) : IAnnouncementService {

    override suspend fun getPending(userId: UUID): List<AnnouncementDTO> =
        dao.getPending(userId).map {
            AnnouncementDTO(
                key = it.announcementKey.name,
                status = it.status.name,
                payload = it.payload,
            )
        }

    override suspend fun acknowledge(userId: UUID, key: String) {
        val parsedKey = try {
            AnnouncementKey.valueOf(key)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Unknown announcement key: $key")
        }
        dao.acknowledge(userId, parsedKey)
    }
}
