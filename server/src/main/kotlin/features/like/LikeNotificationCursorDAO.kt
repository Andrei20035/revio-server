package features.like

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

/**
 * One liker's current standing against a post's like-notification aggregation, per plan §18
 * step 5.2. See LikeNotificationParticipationTable / V38 for what [committed] means.
 */
data class LikeNotificationParticipation(
    val windowStartedAt: Instant,
    val committed: Boolean,
)

interface ILikeNotificationCursorDAO {
    /** The (post, liker) pair's current participation row, or null if this liker has never contributed to this post's like notification. */
    suspend fun find(postId: UUID, likerId: UUID): LikeNotificationParticipation?

    /** Records a fresh, not-yet-committed contribution from [likerId] to [postId]'s current window. */
    suspend fun insert(postId: UUID, likerId: UUID, windowStartedAt: Instant)

    /** Permanently marks [likerId]'s contribution to [postId] as committed — see V38 for what this locks in. */
    suspend fun markCommitted(postId: UUID, likerId: UUID)

    /** Removes a not-yet-committed participation row (an unlike that fully withdraws it before it closes out). */
    suspend fun delete(postId: UUID, likerId: UUID)
}

class LikeNotificationCursorDAO : ILikeNotificationCursorDAO {

    override suspend fun find(postId: UUID, likerId: UUID): LikeNotificationParticipation? = transaction {
        LikeNotificationParticipationTable
            .select(LikeNotificationParticipationTable.windowStartedAt, LikeNotificationParticipationTable.committed)
            .where {
                (LikeNotificationParticipationTable.postId eq postId) and
                    (LikeNotificationParticipationTable.likerId eq likerId)
            }
            .singleOrNull()
            ?.let {
                LikeNotificationParticipation(
                    windowStartedAt = it[LikeNotificationParticipationTable.windowStartedAt],
                    committed = it[LikeNotificationParticipationTable.committed],
                )
            }
    }

    override suspend fun insert(postId: UUID, likerId: UUID, windowStartedAt: Instant): Unit = transaction {
        LikeNotificationParticipationTable.insert {
            it[LikeNotificationParticipationTable.postId] = postId
            it[LikeNotificationParticipationTable.likerId] = likerId
            it[LikeNotificationParticipationTable.windowStartedAt] = windowStartedAt
            it[LikeNotificationParticipationTable.committed] = false
        }
        Unit
    }

    override suspend fun markCommitted(postId: UUID, likerId: UUID): Unit = transaction {
        LikeNotificationParticipationTable.update({
            (LikeNotificationParticipationTable.postId eq postId) and
                (LikeNotificationParticipationTable.likerId eq likerId)
        }) {
            it[LikeNotificationParticipationTable.committed] = true
        }
        Unit
    }

    override suspend fun delete(postId: UUID, likerId: UUID): Unit = transaction {
        LikeNotificationParticipationTable.deleteWhere {
            (LikeNotificationParticipationTable.postId eq postId) and
                (LikeNotificationParticipationTable.likerId eq likerId)
        }
        Unit
    }
}
