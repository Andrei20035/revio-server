package com.revio.server.features.account_deletion

import at.favre.lib.crypto.bcrypt.BCrypt
import com.revio.server.core.error.AuthBadRequestException
import com.revio.server.core.error.AuthErrorCode
import com.revio.server.core.error.AuthNotFoundException
import com.revio.server.core.error.AuthUnauthorizedException
import com.revio.server.core.storage.IStorageService
import com.revio.server.core.util.hashEmailForLogging
import com.revio.server.features.auth.AuthProvider
import com.revio.server.features.auth.IAuthService
import com.revio.server.features.auth.dto.DeleteAccountRequest
import com.revio.server.features.auth.session.ISessionService
import com.revio.server.features.auth.session.RevokeReason
import com.revio.server.features.moderation.IOrphanedStorageObjectDAO
import com.revio.server.features.moderation.OrphanedStorageObjectDAO
import com.revio.server.features.post.PostTable
import com.revio.server.features.user.IUserService
import com.revio.server.features.user.dto.UserDTO
import com.revio.server.features.user_car.UserCarTable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.UUID

interface IAccountDeletionService {
    suspend fun deleteAccount(credentialId: UUID, request: DeleteAccountRequest)
}

class AccountDeletionService(
    private val authService: IAuthService,
    private val userService: IUserService,
    private val sessionService: ISessionService,
    private val feedbackDao: IAccountDeletionFeedbackDAO,
    private val storageService: IStorageService,
    // Defaulted for the same reason as PostServiceImpl's trailing orphanedStorageDao: pre-existing
    // callers that construct AccountDeletionService with the original 5 args keep compiling unchanged.
    private val orphanedStorageDao: IOrphanedStorageObjectDAO = OrphanedStorageObjectDAO(),
) : IAccountDeletionService {

    private val logger = LoggerFactory.getLogger(AccountDeletionService::class.java)

    override suspend fun deleteAccount(credentialId: UUID, request: DeleteAccountRequest) {
        // 1. Validate the confirmation barrier before touching any data.
        val credential = authService.getCredentialsById(credentialId)
            ?: throw AuthNotFoundException(AuthErrorCode.ACCOUNT_NOT_FOUND, "Account not found")
        val user = userService.getUserByAuthCredentialId(credentialId)
            ?: throw AuthNotFoundException(AuthErrorCode.ACCOUNT_NOT_FOUND, "Account not found")

        validateConfirmation(credential.provider, credential.password, user.username, request)
        val reason = validateReason(request.reason)

        // 2. Collect storage keys before any delete — the cascade destroys the rows
        // that name them, and there is no other index of them.
        val storageKeys = collectStorageKeys(user)

        // 3. Persist feedback (if a reason was given) in its own transaction,
        // committed independently.
        if (reason != null) {
            feedbackDao.insert(
                reason = reason,
                details = if (reason == DeletionReason.OTHER) request.details?.trim()?.take(500) else null,
                accountAgeDays = accountAgeDays(user.createdAt),
                postCount = user.postCount,
                spotScore = user.spotScore,
                streakDays = user.streakDays,
                provider = credential.provider.name,
            )
        }

        // 4. Revoke sessions so the user is signed out even if a later step throws.
        sessionService.revokeAllSessions(credentialId, RevokeReason.ACCOUNT_DELETED)

        // 5. Delete the credential; ON DELETE CASCADE removes everything else.
        authService.deleteCredentials(credentialId)

        // Audit trail (pas 5.4): the only record that this account ever existed and was
        // deliberately deleted, once ON DELETE CASCADE above has removed the row itself. Logged
        // after the delete actually commits, not before, so this is a record of what happened —
        // not of what was merely attempted. Email is hashed, never logged in clear (same
        // technique as pas 3.5a's refresh-token-reuse WARN); credentialId/userId appear in clear,
        // which is the existing, correct practice for server logs (see
        // docs/telemetry-naming-and-forbidden-data.md's server-logging section).
        logger.info(
            "Account deletion audit: credentialId={}, userId={}, provider={}, reason={}, emailHash={}",
            credentialId, user.id, credential.provider, reason, hashEmailForLogging(credential.email),
        )

        // 6. Best-effort storage cleanup, after commit and outside any transaction.
        storageKeys.forEach { key ->
            runCatching { storageService.deleteImage(key) }
                .onFailure { e ->
                    logger.warn("Account deleted but failed to delete storage object {}", key, e)
                    runCatching { orphanedStorageDao.recordFailure(key, e.message) }
                        .onFailure { logger.error("Failed to queue orphaned storage object {}", key, it) }
                }
        }
    }

    private fun validateConfirmation(
        provider: AuthProvider,
        storedPasswordHash: String?,
        username: String,
        request: DeleteAccountRequest,
    ) {
        when (provider) {
            AuthProvider.REGULAR -> {
                val password = request.password
                if (password.isNullOrBlank()) {
                    throw AuthBadRequestException(AuthErrorCode.VALIDATION_ERROR, "Password is required")
                }
                val storedPassword = storedPasswordHash
                    ?: throw AuthBadRequestException(AuthErrorCode.VALIDATION_ERROR, "Password is required")
                val passwordValid = BCrypt.verifyer().verify(password.toCharArray(), storedPassword).verified
                if (!passwordValid) {
                    throw AuthUnauthorizedException(AuthErrorCode.INVALID_CURRENT_PASSWORD, "Invalid password")
                }
            }

            AuthProvider.GOOGLE -> {
                val usernameConfirmation = request.usernameConfirmation
                if (usernameConfirmation.isNullOrBlank()) {
                    throw AuthBadRequestException(
                        AuthErrorCode.VALIDATION_ERROR,
                        "Username confirmation is required"
                    )
                }
                val normalizedInput = usernameConfirmation.trim().lowercase()
                val normalizedUsername = username.trim().lowercase()
                if (normalizedInput != normalizedUsername) {
                    throw AuthBadRequestException(
                        AuthErrorCode.USERNAME_CONFIRMATION_MISMATCH,
                        "Username does not match"
                    )
                }
            }
        }
    }

    private fun validateReason(reason: String?): DeletionReason? {
        if (reason == null) return null
        return try {
            DeletionReason.valueOf(reason)
        } catch (e: IllegalArgumentException) {
            throw AuthBadRequestException(AuthErrorCode.VALIDATION_ERROR, "Unknown deletion reason")
        }
    }

    private fun collectStorageKeys(user: UserDTO): Set<String> = transaction {
        val keys = mutableSetOf<String>()

        user.profilePicturePath
            ?.takeIf { it.isNotBlank() }
            ?.let { keys += storageService.normalizeObjectKey(it) }

        PostTable
            .select(PostTable.imageKey)
            .where { PostTable.userId eq user.id }
            .forEach { row ->
                val key = row[PostTable.imageKey]
                if (key.isNotBlank()) keys += storageService.normalizeObjectKey(key)
            }

        UserCarTable
            .select(UserCarTable.imageKey)
            .where { UserCarTable.userId eq user.id }
            .forEach { row ->
                val key = row[UserCarTable.imageKey]
                if (key.isNotBlank()) keys += storageService.normalizeObjectKey(key)
            }

        keys
    }

    private fun accountAgeDays(createdAt: Instant?): Int? =
        createdAt?.let { Duration.between(it, Instant.now()).toDays().toInt() }
}
