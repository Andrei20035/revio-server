package com.revio.server.features.moderation

import com.revio.server.features.auth.AuthTable
import com.revio.server.features.user.UserRole
import com.revio.server.features.user.UserTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.UUID

/** users joined with its auth_credentials row — the admin panel needs email, which User doesn't carry. */
data class AdminUserRow(
    val id: UUID,
    val username: String,
    val email: String,
    val fullName: String,
    val role: UserRole,
    val bannedUntil: Instant?,
    val banPermanent: Boolean,
    val banReason: String?,
    val bannedAt: Instant?,
    val bannedBy: UUID?,
)

interface IAdminUserQueryDAO {
    /** Case-insensitive substring match on username or email, for the admin panel's "find user" screen. */
    suspend fun search(query: String, limit: Int): List<AdminUserRow>

    suspend fun findById(userId: UUID): AdminUserRow?
}

class AdminUserQueryDAO : IAdminUserQueryDAO {

    private val columns = listOf(
        UserTable.id, UserTable.username, AuthTable.email, UserTable.fullName, UserTable.role,
        UserTable.bannedUntil, UserTable.banPermanent, UserTable.banReason, UserTable.bannedAt, UserTable.bannedBy,
    )

    override suspend fun search(query: String, limit: Int): List<AdminUserRow> = transaction {
        val pattern = "%${query.trim().lowercase()}%"
        (UserTable innerJoin AuthTable)
            .select(columns)
            .where { (UserTable.username.lowerCase() like pattern) or (AuthTable.email.lowerCase() like pattern) }
            .orderBy(UserTable.username to SortOrder.ASC)
            .limit(limit)
            .map { it.toRow() }
    }

    override suspend fun findById(userId: UUID): AdminUserRow? = transaction {
        (UserTable innerJoin AuthTable)
            .select(columns)
            .where { UserTable.id eq userId }
            .singleOrNull()
            ?.toRow()
    }

    private fun ResultRow.toRow() = AdminUserRow(
        id = this[UserTable.id].value,
        username = this[UserTable.username],
        email = this[AuthTable.email],
        fullName = this[UserTable.fullName],
        role = this[UserTable.role],
        bannedUntil = this[UserTable.bannedUntil],
        banPermanent = this[UserTable.banPermanent],
        banReason = this[UserTable.banReason],
        bannedAt = this[UserTable.bannedAt],
        bannedBy = this[UserTable.bannedBy],
    )
}
