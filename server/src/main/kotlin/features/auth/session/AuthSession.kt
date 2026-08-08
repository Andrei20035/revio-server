package com.revio.server.features.auth.session

import java.time.Instant
import java.util.UUID

enum class SessionStatus { ACTIVE, REVOKED, EXPIRED }

enum class SessionScope { ONBOARDING, FULL }

enum class RevokeReason {
    SUPERSEDED, LOGOUT, LOGOUT_ALL,
    PASSWORD_CHANGED, ACCOUNT_DELETED,
    REFRESH_TOKEN_REUSED, IDLE_EXPIRED, ABSOLUTE_EXPIRED,
    ACCOUNT_SUSPENDED
}

data class AuthSession(
    val id: UUID,
    val credentialId: UUID,
    val userId: UUID?,
    val version: Int,
    val scope: SessionScope,
    val refreshTokenHash: String,
    val prevTokenHash: String?,
    val prevRotatedAt: Instant?,
    val status: SessionStatus,
    val revokedReason: RevokeReason?,
    val deviceId: String?,
    val deviceName: String?,
    val userAgent: String?,
    val ipAddress: String?,
    val createdAt: Instant,
    val lastUsedAt: Instant,
    val idleExpiresAt: Instant,
    val absoluteExpiresAt: Instant,
)

data class NewAuthSession(
    val credentialId: UUID,
    val userId: UUID?,
    val scope: SessionScope,
    val refreshTokenHash: String,
    val deviceId: String?,
    val deviceName: String?,
    val userAgent: String?,
    val ipAddress: String?,
    val idleExpiresAt: Instant,
    val absoluteExpiresAt: Instant,
)

sealed class RefreshRotationResult {
    data class Rotated(val session: AuthSession) : RefreshRotationResult()
    data object Consumed : RefreshRotationResult()
    data object Reused : RefreshRotationResult()
    data object Invalid : RefreshRotationResult()
    data object Expired : RefreshRotationResult()
    data object Revoked : RefreshRotationResult()
}
