package com.revio.server.features.auth

import at.favre.lib.crypto.bcrypt.BCrypt
import com.revio.server.features.auth.dto.AuthDTO
import com.revio.server.features.auth.dto.WaitlistPrefillDTO
import com.revio.server.features.auth.dto.toDTO
import com.revio.server.features.user.IUserService
import com.revio.server.features.waitlist.IWaitlistLookupService
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.*

data class GoogleUser(
    val email: String,
    val googleId: String
)

interface GoogleTokenVerifier {
    fun verify(googleIdToken: String): GoogleUser?
}

class GoogleTokenVerifierImpl : GoogleTokenVerifier {

    private val clientId: String = System.getenv("GOOGLE_CLIENT_ID")
        ?: throw IllegalStateException("GOOGLE_CLIENT_ID is not set")

    private val verifier = GoogleIdTokenVerifier.Builder(
        NetHttpTransport(),
        GsonFactory.getDefaultInstance()
    )
        .setAudience(listOf(clientId))
        .build()

    override fun verify(googleIdToken: String): GoogleUser? {
        val idToken = verifier.verify(googleIdToken) ?: return null
        val payload = idToken.payload

        val email = payload.email ?: return null
        val emailVerified = payload.emailVerified

        if (emailVerified != true) return null

        return GoogleUser(
            email = email.trim().lowercase(),
            googleId = payload.subject
        )
    }
}

interface IAuthService {
    suspend fun createCredentials(authCredential: AuthCredential): UUID
    suspend fun regularLogin(email: String, password: String): AuthDTO?
    suspend fun googleLogin(googleIdToken: String): AuthDTO?
    suspend fun updatePassword(credentialId: UUID, oldPassword: String, newPassword: String): Int
    suspend fun deleteCredentials(credentialId: UUID): Int
    suspend fun getCredentialsById(credentialId: UUID): AuthCredential?
}

class AuthService(
    private val authDao: IAuthDAO,
    private val userService: IUserService,
    private val googleTokenVerifier: GoogleTokenVerifier = GoogleTokenVerifierImpl(),
    private val waitlistLookupService: IWaitlistLookupService,
) : IAuthService {

    companion object {
        private val logger = LoggerFactory.getLogger(AuthService::class.java)
    }

    override suspend fun createCredentials(authCredential: AuthCredential): UUID {
        val normalizedEmail = authCredential.email.trim().lowercase()

        val existing = authDao.getCredentialsForLogin(normalizedEmail)
        if (existing != null) {
            throw IllegalArgumentException("Email is already registered")
        }

        val credentialToSave = when (authCredential.provider) {
            AuthProvider.REGULAR -> {
                val password = authCredential.password
                    ?: throw IllegalArgumentException("Password is required")

                val hashedPassword = BCrypt.withDefaults()
                    .hashToString(12, password.toCharArray())

                authCredential.copy(
                    email = normalizedEmail,
                    password = hashedPassword,
                    googleId = null
                )
            }

            AuthProvider.GOOGLE -> {
                val googleId = authCredential.googleId
                    ?: throw IllegalArgumentException("Google ID is required")

                authCredential.copy(
                    email = normalizedEmail,
                    password = null,
                    googleId = googleId
                )
            }
        }

        return try {
            authDao.createCredentials(credentialToSave)
        } catch (e: IllegalStateException) {
            throw CredentialCreationException("Unable to create credentials", e)
        }
    }

    override suspend fun regularLogin(email: String, password: String): AuthDTO? {
        val normalizedEmail = email.trim().lowercase()

        val authCredential = authDao.getCredentialsForLogin(normalizedEmail)
            ?: return null

        if (authCredential.provider != AuthProvider.REGULAR) {
            return null
        }

        val storedPassword = authCredential.password ?: return null

        val passwordValid = BCrypt.verifyer()
            .verify(password.toCharArray(), storedPassword)
            .verified

        if (!passwordValid) return null

        val userId = authCredential.currentUserId()
        // Waitlist recognition is only meaningful pre-profile (Profile Customization reads the
        // prefill); an existing profile never needs it, so this only looks up when userId == null.
        // WaitlistLookupService.lookup() never throws, so this can never fail the login.
        val waitlistPrefill = if (userId == null) resolveWaitlistPrefill(normalizedEmail) else null
        return authCredential.toDTO(userId, waitlist = waitlistPrefill)
    }

    override suspend fun googleLogin(googleIdToken: String): AuthDTO? {
        logger.debug("googleLogin called, token present={}", googleIdToken.isNotBlank())

        val googleUser = googleTokenVerifier.verify(googleIdToken)
            ?: return null

        logger.debug("googleLogin verified, email={}", hashEmailForLogging(googleUser.email))

        val existingCredential = authDao.getCredentialsForLogin(googleUser.email)

        logger.debug(
            "googleLogin existingCredential exists={}, provider={}, googleIdMatches={}",
            existingCredential != null,
            existingCredential?.provider,
            existingCredential?.googleId == googleUser.googleId,
        )

        return when {
            existingCredential != null &&
                    existingCredential.provider == AuthProvider.GOOGLE &&
                    existingCredential.googleId == googleUser.googleId -> {
                val userId = existingCredential.currentUserId()
                // Same pre-profile-only waitlist recognition as regularLogin() above.
                val waitlistPrefill = if (userId == null) resolveWaitlistPrefill(googleUser.email) else null
                existingCredential.toDTO(userId, waitlist = waitlistPrefill)
            }

            existingCredential != null &&
                    existingCredential.provider != AuthProvider.GOOGLE -> {
                throw IllegalArgumentException("This email is already registered with password login.")
            }

            existingCredential == null -> {
                // New Google account — same waitlist recognition contract as email/password
                // registration (AuthRoutes.kt /register). WaitlistLookupService.lookup() never
                // throws, so this can never fail the login.
                val waitlistEntry = waitlistLookupService.lookup(googleUser.email)
                val waitlistPrefill = waitlistEntry?.let { entry ->
                    val usernameCheck = userService.checkUsernameAvailabilityForNewUser(entry.username.orEmpty())
                    WaitlistPrefillDTO(
                        suggestedUsername = entry.username,
                        suggestedUsernameStatus = usernameCheck.toWaitlistUsernameStatus(),
                    )
                }

                val newCredential = AuthCredential(
                    email = googleUser.email,
                    password = null,
                    provider = AuthProvider.GOOGLE,
                    googleId = googleUser.googleId
                )

                val credentialId = createCredentials(newCredential)

                newCredential.copy(id = credentialId).toDTO(waitlist = waitlistPrefill)
            }

            else -> null
        }
    }

    /** Truncated SHA-256 of the email — never log emails in clear text. */
    private fun hashEmailForLogging(email: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(email.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(12)
    }

    override suspend fun updatePassword(credentialId: UUID, oldPassword: String, newPassword: String): Int {
        val authCredential = authDao.getCredentialsById(credentialId)
            ?: throw IllegalArgumentException("Credentials not found")

        if (authCredential.provider != AuthProvider.REGULAR) {
            throw IllegalArgumentException("Password cannot be updated for this provider")
        }

        val storedPassword = authCredential.password
            ?: throw IllegalArgumentException("Password not found")

        val oldPasswordValid = BCrypt.verifyer()
            .verify(oldPassword.toCharArray(), storedPassword)
            .verified

        if (!oldPasswordValid) {
            throw IllegalArgumentException("Invalid current password")
        }

        val newHashedPassword = BCrypt.withDefaults()
            .hashToString(12, newPassword.toCharArray())

        return authDao.updatePassword(credentialId, newHashedPassword)
    }

    override suspend fun deleteCredentials(credentialId: UUID): Int {
        return authDao.deleteCredentials(credentialId)
    }

    override suspend fun getCredentialsById(credentialId: UUID): AuthCredential? {
        return authDao.getCredentialsById(credentialId)
    }

    private suspend fun AuthCredential.currentUserId(): UUID? {
        val credentialId = id ?: return null
        return userService.getUserByAuthCredentialId(credentialId)?.id
    }

    /** Same waitlist recognition contract used by createCredentials' callers (AuthRoutes.kt /register). */
    private suspend fun resolveWaitlistPrefill(normalizedEmail: String): WaitlistPrefillDTO? {
        val waitlistEntry = waitlistLookupService.lookup(normalizedEmail) ?: return null
        val usernameCheck = userService.checkUsernameAvailabilityForNewUser(waitlistEntry.username.orEmpty())
        return WaitlistPrefillDTO(
            suggestedUsername = waitlistEntry.username,
            suggestedUsernameStatus = usernameCheck.toWaitlistUsernameStatus(),
        )
    }
}

class CredentialCreationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
