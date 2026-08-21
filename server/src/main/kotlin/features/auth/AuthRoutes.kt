package com.revio.server.features.auth

import com.revio.server.core.error.AuthBadRequestException
import com.revio.server.core.error.AuthConflictException
import com.revio.server.core.error.AuthErrorCode
import com.revio.server.core.error.AuthForbiddenException
import com.revio.server.core.error.AuthNotFoundException
import com.revio.server.core.error.AuthUnauthorizedException
import com.revio.server.features.account_deletion.IAccountDeletionService
import com.revio.server.features.auth.dto.DeleteAccountRequest
import com.revio.server.features.auth.dto.DeletionContextDTO
import com.revio.server.features.auth.dto.LoginRequest
import com.revio.server.features.auth.dto.RefreshRequest
import com.revio.server.features.auth.dto.RegisterRequest
import com.revio.server.features.auth.dto.SessionDTO
import com.revio.server.features.auth.dto.UpdatePasswordRequest
import com.revio.server.core.util.getUuidClaim
import com.revio.server.core.util.hashEmailForLogging
import com.revio.server.features.auth.dto.AuthResponse
import com.revio.server.features.auth.dto.OnboardingStep
import com.revio.server.features.auth.dto.WaitlistPrefillDTO
import com.revio.server.features.auth.dto.WaitlistUsernameStatus
import com.revio.server.features.auth.session.ISessionService
import com.revio.server.features.auth.session.IAuthSessionDAO
import com.revio.server.features.auth.session.RefreshTokenConsumedException
import com.revio.server.features.auth.session.RefreshTokenExpiredException
import com.revio.server.features.auth.session.RefreshTokenInvalidException
import com.revio.server.features.auth.session.RefreshTokenReusedException
import com.revio.server.features.auth.session.RevokeReason
import com.revio.server.features.auth.session.SessionRevokedException
import com.revio.server.features.auth.session.SessionScope
import com.revio.server.features.leaderboard.ILeaderboardService
import com.revio.server.features.user.BanState
import com.revio.server.features.user.IUserDAO
import com.revio.server.features.user.IUserService
import com.revio.server.features.user.UserRole
import com.revio.server.features.user.UsernameAvailabilityResult
import com.revio.server.features.waitlist.IWaitlistLookupService
import features.like.ILikeDAO
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.UUID

private val logger = LoggerFactory.getLogger("com.revio.server.features.auth.AuthRoutes")
private val refreshTokenHasher = RefreshTokenGenerator()

fun Route.authRoutes() {
    val authService: IAuthService by application.inject()
    val googleTokenVerifier: GoogleTokenVerifier by application.inject()
    val jwtService: JwtService by application.inject()
    val sessionService: ISessionService by application.inject()
    val sessionDao: IAuthSessionDAO by application.inject()
    val userService: IUserService by application.inject()
    val userDao: IUserDAO by application.inject()
    val likeDao: ILikeDAO by application.inject()
    val leaderboardService: ILeaderboardService by application.inject()
    val accountDeletionService: IAccountDeletionService by application.inject()
    val waitlistLookupService: IWaitlistLookupService by application.inject()

    route("/auth") {
        post("/register") {
            val request = call.receive<RegisterRequest>()

            try {
                val authCredential = when (request.provider) {
                    AuthProvider.REGULAR -> {
                        val normalizedEmail = request.email
                            ?.trim()
                            ?.lowercase()
                            ?: throw AuthBadRequestException(
                                AuthErrorCode.VALIDATION_ERROR,
                                "Email is required"
                            )

                        if (normalizedEmail.isBlank() || !isValidEmail(normalizedEmail)) {
                            throw AuthBadRequestException(
                                AuthErrorCode.VALIDATION_ERROR,
                                "Invalid email"
                            )
                        }

                        val password = request.password
                            ?.trim()
                            ?: throw AuthBadRequestException(
                                AuthErrorCode.VALIDATION_ERROR,
                                "Password is required for regular registration"
                            )

                        if (!isValidPassword(password)) {
                            throw AuthBadRequestException(
                                AuthErrorCode.WEAK_PASSWORD,
                                PASSWORD_REQUIREMENTS_MESSAGE
                            )
                        }

                        AuthCredential(
                            email = normalizedEmail,
                            password = password,
                            googleId = null,
                            provider = AuthProvider.REGULAR
                        )
                    }
                    AuthProvider.GOOGLE -> {
                        if (request.googleIdToken.isNullOrBlank()) {
                            throw AuthBadRequestException(
                                AuthErrorCode.VALIDATION_ERROR,
                                "Google ID token is required"
                            )
                        }

                        val googleUser = googleTokenVerifier.verify(request.googleIdToken)
                            ?: throw AuthUnauthorizedException(
                                AuthErrorCode.INVALID_GOOGLE_TOKEN,
                                "Invalid Google token"
                            )

                        AuthCredential(
                            email = googleUser.email.trim().lowercase(),
                            password = null,
                            googleId = googleUser.googleId,
                            provider = AuthProvider.GOOGLE
                        )
                    }
                }

                try {
                    val credentialId = authService.createCredentials(authCredential)

                    // Register always creates a brand new credential, so this is always the "new
                    // account" case. WaitlistLookupService.lookup() never throws, so this can never
                    // fail registration.
                    val waitlistEntry = waitlistLookupService.lookup(authCredential.email)
                    val waitlistPrefill = waitlistEntry?.let { entry ->
                        val usernameCheck = userService.checkUsernameAvailabilityForNewUser(entry.username.orEmpty())
                        WaitlistPrefillDTO(
                            suggestedUsername = entry.username,
                            suggestedUsernameStatus = usernameCheck.toWaitlistUsernameStatus(),
                        )
                    }

                    val (session, refreshToken) = sessionService.createSession(
                        credentialId = credentialId,
                        scope = SessionScope.ONBOARDING,
                        deviceId = request.deviceId,
                        deviceName = request.deviceName,
                        userAgent = call.request.headers[HttpHeaders.UserAgent],
                        ip = call.request.local.remoteHost
                    )
                    // No userId exists yet at registration (only the auth credential is created here),
                    // so there is no role to read — isAdmin stays at its false default.
                    val accessToken = jwtService.generateAccessToken(
                        session = session,
                        credentialId = credentialId,
                        email = authCredential.email
                    )

                    call.respond(
                        HttpStatusCode.Created,
                        AuthResponse(
                            accessToken = accessToken,
                            refreshToken = refreshToken,
                            expiresIn = JwtService.EXPIRES_IN_SECONDS,
                            scope = session.scope.name,
                            onboardingStep = OnboardingStep.PROFILE_REQUIRED,
                            waitlist = waitlistPrefill,
                        )
                    )
                    // Ev. 8 — auth_result (server-side), pas 3.5c.
                    logger.info("auth_result outcome=success event=register method={}", request.provider)
                } catch (e: IllegalArgumentException) {
                    throw AuthConflictException(
                        AuthErrorCode.EMAIL_TAKEN,
                        e.message ?: "Email is already registered"
                    )
                }
            } catch (e: com.revio.server.core.error.AuthApiException) {
                // Ev. 8 — auth_result (server-side), pas 3.5c: distinguishes outcome and method so
                // a failure rate can be computed per provider, mirroring the client-side "Ev. 7"
                // event from pas 2.2b without duplicating its Analytics-specific shape here.
                logger.info("auth_result outcome=failure event=register method={} code={}", request.provider, e.code)
                throw e
            }
        }

        post("/login") {
            val request = call.receive<LoginRequest>()

            try {
                val result = try {
                    when (request.provider) {
                        AuthProvider.REGULAR -> {
                            val email = request.email
                                ?.trim()
                                ?.lowercase()
                                ?: throw AuthBadRequestException(
                                    AuthErrorCode.VALIDATION_ERROR,
                                    "Email is required"
                                )

                            if (!isValidEmail(email)) {
                                throw AuthBadRequestException(
                                    AuthErrorCode.VALIDATION_ERROR,
                                    "Invalid email format"
                                )
                            }

                            val password = request.password
                                ?: throw AuthBadRequestException(
                                    AuthErrorCode.VALIDATION_ERROR,
                                    "Password is required"
                                )
                            authService.regularLogin(email, password)
                        }
                        AuthProvider.GOOGLE -> {
                            val googleIdToken = request.googleIdToken
                                ?: throw AuthBadRequestException(
                                    AuthErrorCode.VALIDATION_ERROR,
                                    "Google ID token is required"
                                )
                            authService.googleLogin(googleIdToken)
                        }
                    }
                } catch (e: IllegalArgumentException) {
                    if (e.message?.contains("registered with password login", ignoreCase = true) == true) {
                        throw AuthConflictException(
                            AuthErrorCode.PROVIDER_MISMATCH,
                            e.message ?: "Account uses a different sign-in provider"
                        )
                    }
                    throw e
                }
                if (result != null) {
                    if (result.userId != null) {
                        val banState = userDao.findBanState(result.userId)
                        if (banState?.isActive() == true) {
                            throw AuthForbiddenException(AuthErrorCode.ACCOUNT_SUSPENDED, banSuspensionMessage(banState))
                        }
                    }
                    val scope = if (result.userId == null) SessionScope.ONBOARDING else SessionScope.FULL
                    val (session, refreshToken) = sessionService.createSession(
                        credentialId = result.id,
                        scope = scope,
                        userId = result.userId,
                        deviceId = request.deviceId,
                        deviceName = request.deviceName,
                        userAgent = call.request.headers[HttpHeaders.UserAgent],
                        ip = call.request.local.remoteHost
                    )
                    val accessToken = jwtService.generateAccessToken(
                        session = session,
                        credentialId = result.id,
                        email = result.email,
                        userId = result.userId,
                        isAdmin = userDao.isAdmin(result.userId),
                    )
                    val onboardingStep =
                        if (result.userId == null) OnboardingStep.PROFILE_REQUIRED
                        else OnboardingStep.COMPLETED
                    call.respond(
                        HttpStatusCode.OK,
                        AuthResponse(
                            accessToken = accessToken,
                            refreshToken = refreshToken,
                            expiresIn = JwtService.EXPIRES_IN_SECONDS,
                            scope = session.scope.name,
                            onboardingStep = onboardingStep,
                            waitlist = result.waitlist,
                        )
                    )
                    // Ev. 8 — auth_result (server-side), pas 3.5c.
                    logger.info("auth_result outcome=success event=login method={}", request.provider)
                } else {
                    throw AuthUnauthorizedException(
                        AuthErrorCode.INVALID_CREDENTIALS,
                        "Invalid credentials"
                    )
                }
            } catch (e: com.revio.server.core.error.AuthApiException) {
                // Ev. 8 — auth_result (server-side), pas 3.5c: distinguishes outcome and method so
                // a failure rate can be computed per provider, mirroring the client-side "Ev. 7"
                // event from pas 2.2b without duplicating its Analytics-specific shape here.
                logger.info("auth_result outcome=failure event=login method={} code={}", request.provider, e.code)
                throw e
            }
        }

        post("/refresh") {
            val request = call.receive<RefreshRequest>()
            if (request.refreshToken.isBlank()) {
                throw AuthBadRequestException(
                    AuthErrorCode.VALIDATION_ERROR,
                    "Refresh token is required"
                )
            }

            val (session, refreshToken) = try {
                sessionService.refreshTokens(request.refreshToken, request.deviceId)
            } catch (_: RefreshTokenConsumedException) {
                throw AuthUnauthorizedException(
                    AuthErrorCode.REFRESH_TOKEN_CONSUMED,
                    "Refresh token was already consumed"
                )
            } catch (_: RefreshTokenReusedException) {
                logger.warn(
                    "Refresh token reuse detected, tokenHash={}",
                    refreshTokenHasher.hashOf(request.refreshToken)
                )
                throw AuthUnauthorizedException(
                    AuthErrorCode.REFRESH_TOKEN_REUSED,
                    "Refresh token reuse detected"
                )
            } catch (_: RefreshTokenExpiredException) {
                throw AuthUnauthorizedException(
                    AuthErrorCode.REFRESH_TOKEN_EXPIRED,
                    "Refresh token has expired"
                )
            } catch (_: SessionRevokedException) {
                throw AuthUnauthorizedException(
                    AuthErrorCode.SESSION_REVOKED,
                    "Session is revoked"
                )
            } catch (_: RefreshTokenInvalidException) {
                throw AuthUnauthorizedException(
                    AuthErrorCode.REFRESH_TOKEN_INVALID,
                    "Refresh token is invalid"
                )
            }

            if (session.userId != null) {
                val banState = userDao.findBanState(session.userId)
                if (banState?.isActive() == true) {
                    throw AuthForbiddenException(AuthErrorCode.ACCOUNT_SUSPENDED, banSuspensionMessage(banState))
                }
            }

            val credential = authService.getCredentialsById(session.credentialId)
                ?: throw AuthUnauthorizedException(
                    AuthErrorCode.REFRESH_TOKEN_INVALID,
                    "Refresh token is invalid"
                )
            // Role is re-read on every refresh (not cached from the old token), so revoking an
            // admin takes effect at most one refresh cycle later, not only after the access
            // token's own 15-minute TTL expires.
            val accessToken = jwtService.generateAccessToken(
                session = session,
                credentialId = session.credentialId,
                email = credential.email,
                userId = session.userId,
                isAdmin = userDao.isAdmin(session.userId),
            )

            call.respond(
                HttpStatusCode.OK,
                AuthResponse(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expiresIn = JwtService.EXPIRES_IN_SECONDS,
                    scope = session.scope.name,
                    onboardingStep = if (session.scope == SessionScope.FULL) {
                        OnboardingStep.COMPLETED
                    } else {
                        OnboardingStep.PROFILE_REQUIRED
                    }
                )
            )
        }

        authenticate("jwt") {
            post("/logout") {
                val sessionId = call.getUuidClaim("sid")
                    ?: throw AuthUnauthorizedException(
                        AuthErrorCode.ACCESS_TOKEN_INVALID,
                        "Invalid or missing session id"
                    )
                val credentialId = call.getUuidClaim("credentialId")
                sessionService.revokeSession(sessionId, RevokeReason.LOGOUT)
                // Audit trail (pas 5.8, same technique as pas 5.4's account-deletion audit).
                logger.info("Logout audit: credentialId={}, sessionId={}", credentialId, sessionId)
                call.respond(HttpStatusCode.NoContent)
            }

            post("/logout-all") {
                val credentialId = call.getUuidClaim("credentialId")
                    ?: throw AuthUnauthorizedException(
                        AuthErrorCode.ACCESS_TOKEN_INVALID,
                        "Invalid or missing credential id"
                    )
                sessionService.revokeAllSessions(credentialId, RevokeReason.LOGOUT_ALL)
                call.respond(HttpStatusCode.NoContent)
            }

            get("/sessions") {
                val credentialId = call.getUuidClaim("credentialId")
                    ?: throw AuthUnauthorizedException(
                        AuthErrorCode.ACCESS_TOKEN_INVALID,
                        "Invalid or missing credential id"
                    )
                val currentSessionId = call.getUuidClaim("sid")
                    ?: throw AuthUnauthorizedException(
                        AuthErrorCode.ACCESS_TOKEN_INVALID,
                        "Invalid or missing session id"
                    )
                val sessions = sessionDao.listActiveSessions(credentialId).map { session ->
                    SessionDTO.from(session, current = session.id == currentSessionId)
                }
                call.respond(HttpStatusCode.OK, sessions)
            }

            get("/account/deletion-context") {
                val credentialId = call.getUuidClaim("credentialId")
                    ?: throw AuthUnauthorizedException(
                        AuthErrorCode.ACCESS_TOKEN_INVALID,
                        "Invalid or missing credential id"
                    )
                val userId = call.getUuidClaim("userId")
                    ?: throw AuthUnauthorizedException(
                        AuthErrorCode.ACCESS_TOKEN_INVALID,
                        "Invalid or missing user id"
                    )

                val credential = authService.getCredentialsById(credentialId)
                    ?: throw AuthNotFoundException(AuthErrorCode.ACCOUNT_NOT_FOUND, "Account not found")
                val user = userService.getSelf(userId)
                    ?: throw AuthNotFoundException(AuthErrorCode.ACCOUNT_NOT_FOUND, "Account not found")

                val likesReceived = likeDao.getLikesReceivedByUser(userId)
                val leaderboardRank = leaderboardService.getUserRank(userId)
                val accountAgeDays = user.createdAt
                    ?.let { Duration.between(it, Instant.now()).toDays().toInt() }
                    ?: 0

                call.respond(
                    HttpStatusCode.OK,
                    DeletionContextDTO(
                        provider = credential.provider,
                        postCount = user.postCount,
                        likesReceived = likesReceived.toInt(),
                        leaderboardRank = leaderboardRank,
                        streakDays = user.streakDays,
                        accountAgeDays = accountAgeDays,
                    )
                )
            }

            delete("/account") {
                val credentialId = call.getUuidClaim("credentialId")
                    ?: throw AuthUnauthorizedException(
                        AuthErrorCode.ACCESS_TOKEN_INVALID,
                        "Invalid or missing credential id"
                    )

                val request = try {
                    call.receive<DeleteAccountRequest>()
                } catch (e: Exception) {
                    throw AuthBadRequestException(
                        AuthErrorCode.VALIDATION_ERROR,
                        "Request body is required"
                    )
                }

                accountDeletionService.deleteAccount(credentialId, request)

                call.respond(
                    HttpStatusCode.OK,
                    mapOf("message" to "Account deleted successfully")
                )
            }

            put("/password") {
                val credentialId = call.getUuidClaim("credentialId")
                    ?: throw AuthUnauthorizedException(
                        AuthErrorCode.ACCESS_TOKEN_INVALID,
                        "Invalid or missing credential id"
                    )
                val sessionId = call.getUuidClaim("sid")
                    ?: throw AuthUnauthorizedException(
                        AuthErrorCode.ACCESS_TOKEN_INVALID,
                        "Invalid or missing session id"
                    )

                val request = call.receive<UpdatePasswordRequest>()

                if (request.oldPassword.isBlank()) {
                    throw AuthBadRequestException(
                        AuthErrorCode.VALIDATION_ERROR,
                        "Current password is required"
                    )
                }

                if (!isValidPassword(request.newPassword)) {
                    throw AuthBadRequestException(
                        AuthErrorCode.WEAK_PASSWORD,
                        PASSWORD_REQUIREMENTS_MESSAGE
                    )
                }

                try {
                    val updatedRows = authService.updatePassword(
                        credentialId = credentialId,
                        oldPassword = request.oldPassword,
                        newPassword = request.newPassword
                    )

                    if (updatedRows > 0) {
                        val (session, refreshToken) = sessionService.rotateForPasswordChange(sessionId)
                        val credential = authService.getCredentialsById(credentialId)
                            ?: throw AuthUnauthorizedException(
                                AuthErrorCode.ACCOUNT_NOT_FOUND,
                                "Account not found"
                            )
                        val accessToken = jwtService.generateAccessToken(
                            session = session,
                            credentialId = credentialId,
                            email = credential.email,
                            userId = session.userId,
                            isAdmin = userDao.isAdmin(session.userId),
                        )
                        // Audit trail (pas 5.8, same technique as pas 5.4's account-deletion audit).
                        logger.info(
                            "Password change audit: credentialId={}, emailHash={}",
                            credentialId, hashEmailForLogging(credential.email),
                        )
                        call.respond(
                            HttpStatusCode.OK,
                            AuthResponse(
                                accessToken = accessToken,
                                refreshToken = refreshToken,
                                expiresIn = JwtService.EXPIRES_IN_SECONDS,
                                scope = session.scope.name,
                                onboardingStep = if (session.scope == SessionScope.FULL) {
                                    OnboardingStep.COMPLETED
                                } else {
                                    OnboardingStep.PROFILE_REQUIRED
                                }
                            )
                        )
                    } else {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Credentials not found"))
                    }
                } catch (e: IllegalArgumentException) {
                    throw AuthBadRequestException(
                        if (e.message?.contains("provider", ignoreCase = true) == true) {
                            AuthErrorCode.PROVIDER_NOT_REGULAR
                        } else {
                            AuthErrorCode.INVALID_CURRENT_PASSWORD
                        },
                        e.message ?: "Unable to update password"
                    )
                }
            }
        }
    }
}

/** Re-reads the current DB role for [userId] (null when no user profile exists yet, e.g. mid-onboarding). */
private suspend fun IUserDAO.isAdmin(userId: UUID?): Boolean =
    userId != null && getUserById(userId)?.role == UserRole.ADMIN

private fun banSuspensionMessage(banState: BanState): String {
    val durationClause = if (banState.permanent) "permanently" else "until ${banState.bannedUntil}"
    val reasonClause = banState.reason?.takeIf { it.isNotBlank() }?.let { " Reason: $it." } ?: ""
    return "Your account has been suspended $durationClause.$reasonClause " +
        "Contact threvioapp@gmail.com if you believe this is a mistake."
}

/** Also used by [AuthService.googleLogin] to build the Google new-account waitlist prefill. */
internal fun UsernameAvailabilityResult.toWaitlistUsernameStatus(): WaitlistUsernameStatus =
    when {
        available -> WaitlistUsernameStatus.AVAILABLE
        reason == "TAKEN" -> WaitlistUsernameStatus.TAKEN
        reason == "TOO_SHORT" -> WaitlistUsernameStatus.TOO_SHORT
        reason == "TOO_LONG" -> WaitlistUsernameStatus.TOO_LONG
        else -> WaitlistUsernameStatus.INVALID_FORMAT
    }

private fun isValidEmail(email: String): Boolean {
    val emailRegex = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    return emailRegex.matches(email)
}

private fun isValidPassword(password: String): Boolean {
    return password.length >= 8 &&
        password.any(Char::isUpperCase) &&
        password.any(Char::isLowerCase) &&
        password.any(Char::isDigit) &&
        password.any { !it.isLetterOrDigit() }
}

private const val PASSWORD_REQUIREMENTS_MESSAGE =
    "Password must be at least 8 characters long and include uppercase, lowercase, digit, and special characters."
