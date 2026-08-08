package com.revio.server.features.post

import com.revio.server.core.db.retrySerializationConflicts
import com.revio.server.features.challenge.ContributionReversal
import com.revio.server.features.challenge.IChallengeProgressDAO
import com.revio.server.features.moderation.AdminAuditLogDAO
import com.revio.server.features.moderation.IAdminAuditLogDAO
import com.revio.server.features.moderation.IModerationViolationDAO
import com.revio.server.features.moderation.ModerationReason
import com.revio.server.features.moderation.ModerationViolationDAO
import com.revio.server.features.notification.INotificationService
import com.revio.server.features.notification.NotificationDAO
import com.revio.server.features.notification.NotificationService
import com.revio.server.features.notification.NotificationType
import com.revio.server.features.scoring.IScoringDao
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.UUID

/**
 * What [IPostRemovalDAO.removePostAtomically] committed. [deletedRows] is 0 when the post was
 * already gone, in which case nothing else happened either. [violationId] is non-null only when
 * the removal was moderated (a [ModerationRemoval] was passed in) and the post actually existed.
 */
data class PostRemovalOutcome(
    val deletedRows: Int,
    val reversals: List<ContributionReversal>,
    val violationId: UUID? = null,
)

/**
 * Admin-initiated removal context. When passed to [IPostRemovalDAO.removePostAtomically], a
 * [com.revio.server.features.moderation.ModerationViolationTable] row and an
 * [com.revio.server.features.moderation.AdminAuditLogTable] row are written in the same
 * transaction as the removal — see that method's KDoc for why atomicity matters here too: a
 * violation for a post that turned out not to be removed (rolled-back transaction) would be a
 * phantom accusation. [notifyUser] is false for bulk removal, which sends one aggregated
 * notification per affected owner afterwards instead of one per post (plan's challenge-fraud
 * scenario: 5 fraudulent posts removed together should read as one message, not five).
 */
data class ModerationRemoval(
    val adminId: UUID,
    val reason: ModerationReason,
    val reasonDetails: String?,
    val notifyUser: Boolean = true,
)

interface IPostRemovalDAO {
    /**
     * Removes a post and everything that must fall with it, in ONE transaction: read the post's
     * owner and points, reconcile every challenge contribution it made (revoking the completion
     * reward where [allowRevoke] permits), reverse its normal points from spot_score, then delete
     * the row. When [moderation] is non-null, also records the violation, an audit log entry, and
     * (unless [ModerationRemoval.notifyUser] is false) a "post removed" notification — all in the
     * same transaction as the delete.
     *
     * Atomicity is the whole point, not an optimization. challenge_contributions.post_id is ON
     * DELETE CASCADE: if the delete could commit while reconciliation had failed, the cascade
     * would erase the contribution rows with no chance to revoke the reward they earned, leaving
     * challenge_participants.contribution_count and reward_state permanently disagreeing with
     * reality and spot_score inflated by a reward the user no longer qualifies for. Nothing
     * recomputes that afterwards, and the ledger invariant still holds, so no reconciliation pass
     * would even detect it. Either both happen or neither does; on failure the caller sees the
     * exception and the post is still there to retry.
     *
     * Reading points inside this transaction (rather than from a row the caller fetched earlier)
     * also closes the pre-existing race where a concurrent like made the caller's post.points
     * stale between its read and the reversal.
     *
     * Ordering within the transaction is deliberate: the challenge reward is revoked BEFORE the
     * post's normal points are subtracted, so the floor-at-zero clamp on spot_score produces a
     * deterministic result rather than one that depends on which reversal ran first.
     */
    suspend fun removePostAtomically(
        postId: UUID,
        allowRevoke: (endsAt: Instant) -> Boolean,
        moderation: ModerationRemoval? = null,
    ): PostRemovalOutcome
}

class PostRemovalDAO(
    private val challengeProgressDao: IChallengeProgressDAO,
    private val scoringDao: IScoringDao,
    // Defaulted so pre-existing callers that only pass the first two args (author-delete path,
    // and tests predating moderation) keep compiling unchanged. These three are only exercised
    // when [ModerationRemoval] is passed to removePostAtomically; production wiring (PostModule)
    // still injects the real Koin-managed singletons explicitly.
    private val moderationViolationDao: IModerationViolationDAO = ModerationViolationDAO(),
    private val adminAuditLogDao: IAdminAuditLogDAO = AdminAuditLogDAO(),
    private val notificationService: INotificationService = NotificationService(NotificationDAO()),
) : IPostRemovalDAO {

    // The retry wraps the entire unit, so a serialization conflict (REPEATABLE READ) re-runs
    // reconciliation and deletion together from a fresh snapshot. Nothing was committed by the
    // aborted attempt, so replaying it is safe.
    override suspend fun removePostAtomically(
        postId: UUID,
        allowRevoke: (endsAt: Instant) -> Boolean,
        moderation: ModerationRemoval?,
    ): PostRemovalOutcome = retrySerializationConflicts {
        transaction {
            val post = PostTable
                .select(PostTable.userId, PostTable.points, PostTable.imageKey, PostTable.caption)
                .where { PostTable.id eq postId }
                .singleOrNull()
                ?: return@transaction PostRemovalOutcome(deletedRows = 0, reversals = emptyList())

            val ownerId = post[PostTable.userId]

            val reversals = challengeProgressDao.removeContributionsForPostInCurrentTransaction(postId, allowRevoke)

            val deletedRows = scoringDao.reverseAndDeletePostInCurrentTransaction(
                ownerId = ownerId,
                postId = postId,
                points = post[PostTable.points],
            )

            val violationId = moderation?.let {
                val id = moderationViolationDao.insertInCurrentTransaction(
                    userId = ownerId,
                    postId = postId,
                    postImageKey = post[PostTable.imageKey],
                    postCaption = post[PostTable.caption],
                    reason = it.reason,
                    reasonDetails = it.reasonDetails,
                    adminId = it.adminId,
                )

                adminAuditLogDao.insertInCurrentTransaction(
                    adminId = it.adminId,
                    action = "POST_REMOVED",
                    targetType = "POST",
                    targetId = postId,
                )

                if (it.notifyUser) {
                    notificationService.createInCurrentTransaction(
                        userId = ownerId,
                        type = NotificationType.POST_REMOVED,
                        title = "Post removed",
                        body = notificationService.buildPostRemovedNotificationBody(it.reason, it.reasonDetails),
                        blocking = true,
                    )
                }

                id
            }

            PostRemovalOutcome(deletedRows = deletedRows, reversals = reversals, violationId = violationId)
        }
    }
}
