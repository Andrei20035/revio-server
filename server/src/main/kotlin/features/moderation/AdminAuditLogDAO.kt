package com.revio.server.features.moderation

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.UUID

data class AdminAuditLogEntry(
    val id: UUID,
    val adminId: UUID,
    val action: String,
    val targetType: String,
    val targetId: UUID,
    val metadata: String?,
    val createdAt: Instant,
)

interface IAdminAuditLogDAO {
    /** Must be called inside an already-open transaction — see [IModerationViolationDAO]'s equivalent. */
    fun insertInCurrentTransaction(
        adminId: UUID,
        action: String,
        targetType: String,
        targetId: UUID,
        metadata: String? = null,
    )

    /** Every administrative action, newest first — backs the admin panel's audit history screen. */
    suspend fun listRecent(limit: Int): List<AdminAuditLogEntry>
}

class AdminAuditLogDAO : IAdminAuditLogDAO {
    override fun insertInCurrentTransaction(
        adminId: UUID,
        action: String,
        targetType: String,
        targetId: UUID,
        metadata: String?,
    ) {
        AdminAuditLogTable.insert {
            it[AdminAuditLogTable.adminId] = adminId
            it[AdminAuditLogTable.action] = action
            it[AdminAuditLogTable.targetType] = targetType
            it[AdminAuditLogTable.targetId] = targetId
            it[AdminAuditLogTable.metadata] = metadata
        }
    }

    override suspend fun listRecent(limit: Int): List<AdminAuditLogEntry> = transaction {
        AdminAuditLogTable
            .selectAll()
            .orderBy(AdminAuditLogTable.createdAt to SortOrder.DESC)
            .limit(limit)
            .map { it.toEntry() }
    }

    private fun ResultRow.toEntry() = AdminAuditLogEntry(
        id = this[AdminAuditLogTable.id].value,
        adminId = this[AdminAuditLogTable.adminId],
        action = this[AdminAuditLogTable.action],
        targetType = this[AdminAuditLogTable.targetType],
        targetId = this[AdminAuditLogTable.targetId],
        metadata = this[AdminAuditLogTable.metadata],
        createdAt = this[AdminAuditLogTable.createdAt],
    )
}
