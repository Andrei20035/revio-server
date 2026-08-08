package com.revio.server.features.moderation

import com.revio.server.features.notification.INotificationDAO
import com.revio.server.features.notification.NotificationDAO
import com.revio.server.features.notification.NotificationType
import com.revio.server.features.user.UserTable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

interface IBanDAO {
    /**
     * Sets the ban fields on [userId], writes an audit log entry and an ACCOUNT_SUSPENDED
     * notification, all atomically. Session revocation is a deliberate separate step the caller
     * performs AFTER this commits — it isn't a DB-transactional concern.
     * @return the user's auth_credential_id, needed to revoke sessions.
     * @throws AdminUserNotFoundException if [userId] doesn't exist.
     */
    suspend fun banUserAtomically(
        userId: UUID,
        bannedUntil: Instant?,
        permanent: Boolean,
        reason: String?,
        adminId: UUID,
        notificationBody: String,
    ): UUID

    /** Clears the ban fields, writes an audit log entry and an ACCOUNT_UNSUSPENDED notification, atomically. */
    suspend fun unbanUserAtomically(userId: UUID, adminId: UUID, notificationBody: String): UUID
}

class BanDAO(
    private val adminAuditLogDao: IAdminAuditLogDAO = AdminAuditLogDAO(),
    private val notificationDao: INotificationDAO = NotificationDAO(),
) : IBanDAO {

    override suspend fun banUserAtomically(
        userId: UUID,
        bannedUntil: Instant?,
        permanent: Boolean,
        reason: String?,
        adminId: UUID,
        notificationBody: String,
    ): UUID = transaction {
        val credentialId = requireCredentialId(userId)

        UserTable.update({ UserTable.id eq userId }) {
            it[UserTable.bannedUntil] = bannedUntil
            it[UserTable.banPermanent] = permanent
            it[UserTable.banReason] = reason
            it[UserTable.bannedAt] = Instant.now()
            it[UserTable.bannedBy] = adminId
        }

        adminAuditLogDao.insertInCurrentTransaction(
            adminId = adminId,
            action = "USER_BANNED",
            targetType = "USER",
            targetId = userId,
        )

        notificationDao.insertInCurrentTransaction(
            userId = userId,
            type = NotificationType.ACCOUNT_SUSPENDED,
            title = "Account suspended",
            body = notificationBody,
            blocking = true,
        )

        credentialId
    }

    override suspend fun unbanUserAtomically(userId: UUID, adminId: UUID, notificationBody: String): UUID = transaction {
        val credentialId = requireCredentialId(userId)

        UserTable.update({ UserTable.id eq userId }) {
            it[UserTable.bannedUntil] = null
            it[UserTable.banPermanent] = false
            it[UserTable.banReason] = null
            it[UserTable.bannedAt] = null
            it[UserTable.bannedBy] = null
        }

        adminAuditLogDao.insertInCurrentTransaction(
            adminId = adminId,
            action = "USER_UNBANNED",
            targetType = "USER",
            targetId = userId,
        )

        notificationDao.insertInCurrentTransaction(
            userId = userId,
            type = NotificationType.ACCOUNT_UNSUSPENDED,
            title = "Account reinstated",
            body = notificationBody,
            blocking = true,
        )

        credentialId
    }

    private fun requireCredentialId(userId: UUID): UUID =
        UserTable.select(UserTable.authCredentialId)
            .where { UserTable.id eq userId }
            .singleOrNull()
            ?.get(UserTable.authCredentialId)
            ?: throw AdminUserNotFoundException(userId)
}
