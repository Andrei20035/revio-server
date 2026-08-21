package com.revio.server.features.auth.session

import com.revio.server.features.auth.RefreshTokenGenerator
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.UUID

// ── exceptions thrown by SessionService ──────────────────────────────────────

class RefreshTokenConsumedException : Exception("Refresh token already consumed")
class RefreshTokenReusedException : Exception("Refresh token reuse detected")
class RefreshTokenInvalidException : Exception("Refresh token not found")
class RefreshTokenExpiredException : Exception("Refresh token expired")
class SessionRevokedException : Exception("Session is revoked")
class SessionNotFoundException : Exception("Session not found")

// ─────────────────────────────────────────────────────────────────────────────

class SessionService(
    private val dao: IAuthSessionDAO,
    private val refreshTokenGenerator: RefreshTokenGenerator,
) : ISessionService {

    companion object {
        private val logger = LoggerFactory.getLogger(SessionService::class.java)
        val IDLE_TTL: Duration = Duration.ofDays(30)
        val ABSOLUTE_TTL: Duration = Duration.ofDays(180)
        const val GRACE_WINDOW_SECONDS: Long = 30L
    }

    /**
     * Creates a new session for the given credential.
     * Revokes any existing ACTIVE session first (last-login-wins).
     * Returns (AuthSession, rawRefreshToken).
     */
    override suspend fun createSession(
        credentialId: UUID,
        scope: SessionScope,
        userId: UUID?,
        deviceId: String?,
        deviceName: String?,
        userAgent: String?,
        ip: String?,
    ): Pair<AuthSession, String> {
        val (rawToken, hash) = refreshTokenGenerator.generate()
        val now = Instant.now()

        val session = dao.replaceActiveSession(
            NewAuthSession(
                credentialId = credentialId,
                userId = userId,
                scope = scope,
                refreshTokenHash = hash,
                deviceId = deviceId,
                deviceName = deviceName,
                userAgent = userAgent,
                ipAddress = ip,
                idleExpiresAt = now + IDLE_TTL,
                absoluteExpiresAt = now + ABSOLUTE_TTL,
            )
        )
        return Pair(session, rawToken)
    }

    /**
     * Rotates the refresh token.
     * Implements the grace-window / reuse-detection algorithm from plan B.5.
     *
     * Throws:
     * - [SessionRevokedException] — session is not ACTIVE
     * - [RefreshTokenExpiredException] — idle or absolute TTL exceeded
     * - [RefreshTokenConsumedException] — previous token within grace window (retry, not theft)
     * - [RefreshTokenReusedException] — previous token after grace window (revokes session)
     * - [RefreshTokenInvalidException] — token not found in any session
     */
    override suspend fun refreshTokens(
        rawRefreshToken: String,
        deviceId: String?,
    ): Pair<AuthSession, String> {
        val hash = refreshTokenGenerator.hashOf(rawRefreshToken)
        val now = Instant.now()

        val (newRaw, newHash) = refreshTokenGenerator.generate()
        return when (
            val result = dao.rotateRefreshTokenAtomically(
                presentedHash = hash,
                newHash = newHash,
                now = now,
                newIdleExpiresAt = now + IDLE_TTL,
                graceWindowSeconds = GRACE_WINDOW_SECONDS,
            )
        ) {
            is RefreshRotationResult.Rotated -> Pair(result.session, newRaw)
            RefreshRotationResult.Consumed -> throw RefreshTokenConsumedException()
            RefreshRotationResult.Reused -> throw RefreshTokenReusedException()
            RefreshRotationResult.Invalid -> throw RefreshTokenInvalidException()
            RefreshRotationResult.Expired -> throw RefreshTokenExpiredException()
            RefreshRotationResult.Revoked -> throw SessionRevokedException()
        }
    }

    override suspend fun revokeSession(sessionId: UUID, reason: RevokeReason) {
        dao.revokeSession(sessionId, reason)
    }

    override suspend fun revokeAllSessions(
        credentialId: UUID,
        reason: RevokeReason,
        exceptSessionId: UUID?,
    ) {
        dao.revokeActiveByCredential(credentialId, reason, exceptSessionId)
    }

    /**
     * Promotes an ONBOARDING session to FULL after profile creation.
     * Rotates the refresh token in the same call.
     */
    override suspend fun promoteSession(sessionId: UUID, userId: UUID): Pair<AuthSession, String> {
        val (rawToken, newHash) = refreshTokenGenerator.generate()
        val session = dao.promoteToFullAtomically(
            sessionId = sessionId,
            userId = userId,
            newHash = newHash,
            now = Instant.now(),
        ) ?: throw SessionNotFoundException()
        return Pair(session, rawToken)
    }

    /**
     * After a password change: rotates the current session's refresh token and revokes all others.
     */
    override suspend fun rotateForPasswordChange(sessionId: UUID): Pair<AuthSession, String> {
        val (rawToken, newHash) = refreshTokenGenerator.generate()
        val now = Instant.now()
        val updated = dao.rotateForPasswordChangeAtomically(
            sessionId = sessionId,
            newHash = newHash,
            now = now,
            newIdleExpiresAt = now + IDLE_TTL,
        ) ?: throw SessionNotFoundException()
        return Pair(updated, rawToken)
    }

    /**
     * Validates that the session referenced by a JWT is still ACTIVE and version-consistent.
     * Returns the session if valid, null otherwise.
     */
    override suspend fun validateSessionForRequest(
        sessionId: UUID,
        credentialId: UUID,
        version: Int,
    ): AuthSession? {
        val session = dao.findById(sessionId)
        if (session == null) {
            logger.warn("Session rejected: session_not_found, sessionId={}", sessionId)
            return null
        }
        val now = Instant.now()
        if (session.status != SessionStatus.ACTIVE) {
            logger.warn("Session rejected: not_active, sessionId={}, status={}", sessionId, session.status)
            return null
        }
        if (session.credentialId != credentialId) {
            logger.warn("Session rejected: credential_mismatch, sessionId={}", sessionId)
            return null
        }
        if (session.version != version) {
            logger.warn("Session rejected: version_mismatch, sessionId={}, expected={}, actual={}", sessionId, session.version, version)
            return null
        }
        if (now > session.idleExpiresAt || now > session.absoluteExpiresAt) {
            logger.warn("Session rejected: expired, sessionId={}", sessionId)
            return null
        }
        return session
    }

}
