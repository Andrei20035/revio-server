package com.revio.server.features.moderation

import com.revio.server.core.storage.IStorageService
import com.revio.server.features.auth.session.ISessionService
import com.revio.server.features.auth.session.RevokeReason
import com.revio.server.features.notification.INotificationService
import com.revio.server.features.notification.Notification
import com.revio.server.features.notification.NotificationType
import com.revio.server.features.post.IPostService
import com.revio.server.features.user.UserRole
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class AdminUserNotFoundException(val userId: UUID) : RuntimeException("User $userId not found")

class ViolationNotFoundException(val violationId: UUID) : RuntimeException("Violation $violationId not found")

/** [removedPostIds] preserves request order; [notifiedUserIds] is the set of owners who got the aggregated notification. */
data class BulkRemovalResult(
    val removedPostIds: List<UUID>,
    val notifiedUserIds: Set<UUID>,
)

data class OrphanRetryResult(val attempted: Int, val succeeded: Int)

data class BanState(
    val isBanned: Boolean,
    val permanent: Boolean,
    val bannedUntil: Instant?,
    val reason: String?,
    val bannedAt: Instant?,
    val bannedBy: UUID?,
)

data class AdminUserSummary(
    val id: UUID,
    val username: String,
    val email: String,
    val fullName: String,
    val role: UserRole,
    val banState: BanState,
)

data class UserModerationDetail(
    val user: AdminUserSummary,
    val activeViolationCount: Long,
    val needsReview: Boolean,
    val violations: List<ModerationViolation>,
    val recentNotifications: List<Notification>,
)

interface IModerationService {
    /** Removes one post with an admin-selected reason. @return the created violation id. */
    suspend fun removePost(postId: UUID, adminId: UUID, reason: ModerationReason, reasonDetails: String?): UUID

    /**
     * Removes every post in [postIds] (one violation row each, per [IPostService]'s moderated
     * removal), but sends a single aggregated "post removed" notification per affected owner
     * instead of one per post — the card's challenge-fraud scenario (5 fraudulent posts removed
     * together) should read to the user as one message, not five. Stops at the first post that
     * fails to remove (e.g. already gone); posts removed earlier in the same call stay removed.
     */
    suspend fun bulkRemovePosts(
        postIds: List<UUID>,
        adminId: UUID,
        reason: ModerationReason,
        reasonDetails: String?,
    ): BulkRemovalResult

    /** Retries [IStorageService.deleteImage] for every queued orphaned object; removes the row on success. */
    suspend fun retryOrphanedStorage(): OrphanRetryResult

    /** Case-insensitive username/email substring search for the admin panel's "find user" screen. */
    suspend fun searchUsers(query: String, limit: Int): List<AdminUserSummary>

    /** Full moderation picture for one user: ban state, violation history, recent notifications. */
    suspend fun getUserModeration(userId: UUID): UserModerationDetail

    /**
     * Applies a manual ban. [durationDays] is ignored when [permanent] is true; otherwise it must
     * be one of the card's fixed options (3/7/30) — validated at the route layer, not here.
     * Revokes every active session immediately after the ban commits, so the card's requirement
     * that a banned user's existing tokens/sessions stop working right away holds even though
     * session revocation isn't part of [BanDAO.banUserAtomically]'s own transaction.
     */
    suspend fun banUser(userId: UUID, durationDays: Int?, permanent: Boolean, reason: String?, adminId: UUID)

    suspend fun unbanUser(userId: UUID, adminId: UUID)

    /** Idempotent: revoking an already-revoked violation is a no-op, not an error. */
    suspend fun revokeViolation(violationId: UUID, adminId: UUID)

    /** Every administrative action, newest first. */
    suspend fun listAuditLog(limit: Int): List<AdminAuditLogEntry>
}

class ModerationService(
    private val postService: IPostService,
    private val notificationService: INotificationService,
    private val storageService: IStorageService,
    private val orphanedStorageDao: IOrphanedStorageObjectDAO,
    private val adminUserQueryDao: IAdminUserQueryDAO,
    private val moderationViolationDao: IModerationViolationDAO,
    private val banDao: IBanDAO,
    private val adminAuditLogDao: IAdminAuditLogDAO,
    private val sessionService: ISessionService,
) : IModerationService {

    companion object {
        private const val POST_REMOVED_TITLE = "Post removed"
        private const val VIOLATIONS_LIST_LIMIT = 100
        private const val RECENT_NOTIFICATIONS_LIMIT = 20

        /** The card's own threshold: at 3 active violations, the admin panel flags the user for review. */
        private const val NEEDS_REVIEW_THRESHOLD = 3
    }

    override suspend fun removePost(postId: UUID, adminId: UUID, reason: ModerationReason, reasonDetails: String?): UUID =
        postService.removePostAsModerator(postId, adminId, reason, reasonDetails).violationId

    override suspend fun bulkRemovePosts(
        postIds: List<UUID>,
        adminId: UUID,
        reason: ModerationReason,
        reasonDetails: String?,
    ): BulkRemovalResult {
        val removedPostIds = mutableListOf<UUID>()
        val ownerIds = linkedSetOf<UUID>()

        for (postId in postIds) {
            val result = postService.removePostAsModerator(postId, adminId, reason, reasonDetails, notifyUser = false)
            removedPostIds += postId
            ownerIds += result.ownerId
        }

        val body = notificationService.buildPostRemovedNotificationBody(reason, reasonDetails)
        ownerIds.forEach { ownerId ->
            notificationService.create(ownerId, NotificationType.POST_REMOVED, POST_REMOVED_TITLE, body, blocking = true)
        }

        return BulkRemovalResult(removedPostIds = removedPostIds, notifiedUserIds = ownerIds)
    }

    override suspend fun retryOrphanedStorage(): OrphanRetryResult {
        val keys = orphanedStorageDao.listAll()
        var succeeded = 0
        keys.forEach { key ->
            runCatching { storageService.deleteImage(key) }
                .onSuccess {
                    orphanedStorageDao.remove(key)
                    succeeded++
                }
                .onFailure { e -> orphanedStorageDao.recordFailure(key, e.message) }
        }
        return OrphanRetryResult(attempted = keys.size, succeeded = succeeded)
    }

    override suspend fun searchUsers(query: String, limit: Int): List<AdminUserSummary> =
        adminUserQueryDao.search(query, limit).map { it.toSummary() }

    override suspend fun getUserModeration(userId: UUID): UserModerationDetail {
        val row = adminUserQueryDao.findById(userId) ?: throw AdminUserNotFoundException(userId)
        val violations = moderationViolationDao.listForUser(userId, VIOLATIONS_LIST_LIMIT)
        val activeCount = moderationViolationDao.countActiveForUser(userId)

        return UserModerationDetail(
            user = row.toSummary(),
            activeViolationCount = activeCount,
            needsReview = activeCount >= NEEDS_REVIEW_THRESHOLD,
            violations = violations,
            recentNotifications = notificationService.listForUser(userId, RECENT_NOTIFICATIONS_LIMIT),
        )
    }

    override suspend fun banUser(userId: UUID, durationDays: Int?, permanent: Boolean, reason: String?, adminId: UUID) {
        val bannedUntil = if (permanent) null else durationDays?.let { Instant.now().plus(it.toLong(), ChronoUnit.DAYS) }
        val body = buildBanNotificationBody(permanent, bannedUntil, reason)
        val credentialId = banDao.banUserAtomically(userId, bannedUntil, permanent, reason, adminId, body)
        sessionService.revokeAllSessions(credentialId, RevokeReason.ACCOUNT_SUSPENDED)
    }

    override suspend fun unbanUser(userId: UUID, adminId: UUID) {
        val body = "Your account has been reinstated. You can sign in again."
        banDao.unbanUserAtomically(userId, adminId, body)
    }

    override suspend fun revokeViolation(violationId: UUID, adminId: UUID) {
        when (val outcome = moderationViolationDao.revokeAtomically(violationId, adminId)) {
            is RevokeViolationOutcome.Revoked -> notificationService.create(
                outcome.userId,
                NotificationType.VIOLATION_REVOKED,
                "Violation revoked",
                "An administrator has revoked one of your recorded violations.",
                blocking = false,
            )
            RevokeViolationOutcome.AlreadyRevoked -> Unit
            RevokeViolationOutcome.NotFound -> throw ViolationNotFoundException(violationId)
        }
    }

    override suspend fun listAuditLog(limit: Int): List<AdminAuditLogEntry> = adminAuditLogDao.listRecent(limit)

    private fun buildBanNotificationBody(permanent: Boolean, bannedUntil: Instant?, reason: String?): String {
        val durationClause = if (permanent) {
            "This suspension is permanent."
        } else {
            "This suspension lasts until $bannedUntil."
        }
        val reasonClause = reason?.takeIf { it.isNotBlank() }?.let { " Reason: $it." } ?: ""
        return "Your account has been suspended.$reasonClause $durationClause " +
            "If you believe this is a mistake, contact threvioapp@gmail.com."
    }

    private fun AdminUserRow.toSummary() = AdminUserSummary(
        id = id,
        username = username,
        email = email,
        fullName = fullName,
        role = role,
        banState = BanState(
            isBanned = banPermanent || (bannedUntil != null && bannedUntil.isAfter(Instant.now())),
            permanent = banPermanent,
            bannedUntil = bannedUntil,
            reason = banReason,
            bannedAt = bannedAt,
            bannedBy = bannedBy,
        ),
    )
}
