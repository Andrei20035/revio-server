package com.revio.server.features.moderation

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

data class ModerationViolation(
    val id: UUID,
    val userId: UUID,
    val postId: UUID,
    val postImageKey: String?,
    val postCaption: String?,
    val reason: ModerationReason,
    val reasonDetails: String?,
    val adminId: UUID,
    val createdAt: Instant,
    val revokedAt: Instant?,
    val revokedBy: UUID?,
)

/** Outcome of [IModerationViolationDAO.revokeAtomically]. */
sealed class RevokeViolationOutcome {
    data class Revoked(val userId: UUID) : RevokeViolationOutcome()
    data object AlreadyRevoked : RevokeViolationOutcome()
    data object NotFound : RevokeViolationOutcome()
}

interface IModerationViolationDAO {
    /**
     * Inserts a violation row. Must be called inside an already-open transaction — same reason as
     * [com.revio.server.features.notification.INotificationDAO.insertInCurrentTransaction]: this
     * needs to commit or roll back together with the post removal that creates it.
     */
    fun insertInCurrentTransaction(
        userId: UUID,
        postId: UUID,
        postImageKey: String?,
        postCaption: String?,
        reason: ModerationReason,
        reasonDetails: String?,
        adminId: UUID,
    ): UUID

    suspend fun findById(violationId: UUID): ModerationViolation?

    /** Every violation ever recorded for [userId] (revoked or not), newest first. */
    suspend fun listForUser(userId: UUID, limit: Int): List<ModerationViolation>

    /** Count of [userId]'s violations that haven't been revoked — the count the card's "3 abateri" threshold reads. */
    suspend fun countActiveForUser(userId: UUID): Long

    /**
     * Marks the violation revoked and writes an audit entry, atomically. Idempotent: revoking an
     * already-revoked violation is a no-op, not an error.
     */
    suspend fun revokeAtomically(violationId: UUID, adminId: UUID): RevokeViolationOutcome
}

class ModerationViolationDAO(
    // Defaulted so PostRemovalDAO's own default (features/post/PostRemovalDAO.kt) and any caller
    // that only needs the insert path keep compiling without wiring an audit DAO explicitly.
    private val adminAuditLogDao: IAdminAuditLogDAO = AdminAuditLogDAO(),
) : IModerationViolationDAO {

    override fun insertInCurrentTransaction(
        userId: UUID,
        postId: UUID,
        postImageKey: String?,
        postCaption: String?,
        reason: ModerationReason,
        reasonDetails: String?,
        adminId: UUID,
    ): UUID = ModerationViolationTable.insert {
        it[ModerationViolationTable.userId] = userId
        it[ModerationViolationTable.postId] = postId
        it[ModerationViolationTable.postImageKey] = postImageKey
        it[ModerationViolationTable.postCaption] = postCaption
        it[ModerationViolationTable.reason] = reason
        it[ModerationViolationTable.reasonDetails] = reasonDetails
        it[ModerationViolationTable.adminId] = adminId
    }[ModerationViolationTable.id].value

    override suspend fun findById(violationId: UUID): ModerationViolation? = transaction {
        ModerationViolationTable
            .selectAll()
            .where { ModerationViolationTable.id eq violationId }
            .singleOrNull()
            ?.toViolation()
    }

    override suspend fun listForUser(userId: UUID, limit: Int): List<ModerationViolation> = transaction {
        ModerationViolationTable
            .selectAll()
            .where { ModerationViolationTable.userId eq userId }
            .orderBy(ModerationViolationTable.createdAt to SortOrder.DESC)
            .limit(limit)
            .map { it.toViolation() }
    }

    override suspend fun countActiveForUser(userId: UUID): Long = transaction {
        ModerationViolationTable
            .selectAll()
            .where { (ModerationViolationTable.userId eq userId) and (ModerationViolationTable.revokedAt.isNull()) }
            .count()
    }

    override suspend fun revokeAtomically(violationId: UUID, adminId: UUID): RevokeViolationOutcome = transaction {
        val row = ModerationViolationTable
            .selectAll()
            .where { ModerationViolationTable.id eq violationId }
            .singleOrNull()
            ?: return@transaction RevokeViolationOutcome.NotFound

        if (row[ModerationViolationTable.revokedAt] != null) {
            return@transaction RevokeViolationOutcome.AlreadyRevoked
        }

        ModerationViolationTable.update({ ModerationViolationTable.id eq violationId }) {
            it[ModerationViolationTable.revokedAt] = Instant.now()
            it[ModerationViolationTable.revokedBy] = adminId
        }

        adminAuditLogDao.insertInCurrentTransaction(
            adminId = adminId,
            action = "VIOLATION_REVOKED",
            targetType = "VIOLATION",
            targetId = violationId,
        )

        RevokeViolationOutcome.Revoked(row[ModerationViolationTable.userId])
    }

    private fun ResultRow.toViolation() = ModerationViolation(
        id = this[ModerationViolationTable.id].value,
        userId = this[ModerationViolationTable.userId],
        postId = this[ModerationViolationTable.postId],
        postImageKey = this[ModerationViolationTable.postImageKey],
        postCaption = this[ModerationViolationTable.postCaption],
        reason = this[ModerationViolationTable.reason],
        reasonDetails = this[ModerationViolationTable.reasonDetails],
        adminId = this[ModerationViolationTable.adminId],
        createdAt = this[ModerationViolationTable.createdAt],
        revokedAt = this[ModerationViolationTable.revokedAt],
        revokedBy = this[ModerationViolationTable.revokedBy],
    )
}
