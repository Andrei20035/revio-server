package com.revio.server.features.user

import com.revio.server.core.storage.IStorageService
import com.revio.server.features.leaderboard.StreakCalculator
import com.revio.server.features.user.dto.SelfUserDTO
import com.revio.server.features.user.dto.UpdateUserRequest
import com.revio.server.features.user.dto.UserDTO
import com.revio.server.features.user.dto.toDTO
import com.revio.server.features.user.dto.toSelfDTO
import org.jetbrains.exposed.exceptions.ExposedSQLException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Postgres SQLSTATE for a unique constraint violation. */
private const val POSTGRES_UNIQUE_VIOLATION = "23505"

interface IUserService {
    suspend fun createUserProfile(authCredentialId: UUID, user: User): CreateUserProfileResult
    suspend fun getUserById(userId: UUID): UserDTO?
    suspend fun getUserByAuthCredentialId(authCredentialId: UUID): UserDTO?
    suspend fun updateProfilePicture(userId: UUID, imagePath: String): UserDTO
    suspend fun getSelf(userId: UUID): SelfUserDTO?
    suspend fun updateUserProfile(userId: UUID, request: UpdateUserRequest): SelfUserDTO
    suspend fun checkUsernameAvailability(userId: UUID, username: String): UsernameAvailabilityResult

    /**
     * Same validation and availability rules as [checkUsernameAvailability], for a username
     * suggested before any profile exists yet (the waitlist prefill at registration) — there is
     * no user to exclude from the uniqueness check.
     */
    suspend fun checkUsernameAvailabilityForNewUser(username: String): UsernameAvailabilityResult

    /**
     * Marks that [userId] just opened the feed (plan §18, step 6.2). Thin passthrough to
     * [IUserDAO.updateLastFeedOpenIfStale] — the throttling (and the "don't write on every
     * request" guarantee) lives entirely in that single conditional `UPDATE`; this method adds no
     * logic of its own.
     */
    suspend fun markFeedOpened(userId: UUID)
}

data class UsernameAvailabilityResult(
    val available: Boolean,
    val normalized: String,
    val reason: String?,
)

val profileChangeCooldown: Duration = Duration.ofDays(30)

class UserService(
    private val userDao: IUserDAO,
    private val storageService: IStorageService,
) : IUserService {
    companion object {
        private const val minUsernameLength = 3
        private const val maxUsernameLength = 50
        private const val maxPhoneNumberLength = 20
        private val usernameRegex = Regex("^[a-z0-9._]+$")
        private val monthlyChangeCooldown: Duration = profileChangeCooldown
    }

    override suspend fun createUserProfile(authCredentialId: UUID, user: User): CreateUserProfileResult {
        require(user.authCredentialId == authCredentialId) { "authCredentialId mismatch" }
        if (userDao.getUserByAuthCredentialId(authCredentialId) != null) {
            throw UserProfileAlreadyExistsException("Profile already exists for this account")
        }

        val normalizedUsername = normalizeUsername(user.username)
        if (userDao.usernameExistsIgnoreCase(normalizedUsername)) {
            throw UsernameAlreadyExistsException("Username is already taken")
        }

        val sanitizedProfilePicture = normalizeOptionalImagePath(user.profilePicturePath)

        // The pre-check above closes most collisions cheaply, but two concurrent registrations
        // can both pass it before either commits — the DB-level unique index is the real
        // guarantee. Translate that race loss into the same exception the pre-check throws,
        // instead of letting a raw ExposedSQLException surface as a 500.
        return try {
            userDao.createUser(
                user.copy(
                    username = normalizedUsername,
                    profilePicturePath = sanitizedProfilePicture,
                )
            )
        } catch (e: ExposedSQLException) {
            if (e.sqlState == POSTGRES_UNIQUE_VIOLATION && e.message?.contains("idx_users_username_lower") == true) {
                throw UsernameAlreadyExistsException("Username is already taken")
            }
            throw e
        }
    }

    override suspend fun getUserById(userId: UUID): UserDTO? {
        val user = userDao.getUserById(userId) ?: return null
        val postCount = userDao.countPostsByUser(userId)
        return user.toResponse(postCount = postCount.toInt())
    }

    override suspend fun getUserByAuthCredentialId(authCredentialId: UUID): UserDTO? {
        val user = userDao.getUserByAuthCredentialId(authCredentialId) ?: return null
        val postCount = userDao.countPostsByUser(user.id)
        return user.toResponse(postCount = postCount.toInt())
    }

    override suspend fun updateProfilePicture(userId: UUID, imagePath: String): UserDTO {
        val normalizedImagePath = normalizeRequiredImagePath(imagePath)
        val updatedRows = userDao.updateProfilePicture(userId, normalizedImagePath)
        if (updatedRows == 0) {
            throw UserNotFoundException(userId)
        }
        val user = requireNotNull(userDao.getUserById(userId)) { "Updated user could not be loaded" }
        val postCount = userDao.countPostsByUser(userId)
        return user.toResponse(postCount = postCount.toInt())
    }

    override suspend fun getSelf(userId: UUID): SelfUserDTO? {
        val user = userDao.getUserById(userId) ?: return null
        val postCount = userDao.countPostsByUser(userId)
        return user.toSelfResponse(postCount = postCount.toInt())
    }

    override suspend fun updateUserProfile(userId: UUID, request: UpdateUserRequest): SelfUserDTO {
        val normalizedUsername = request.username?.let { normalizeUsername(it) }
        if (normalizedUsername != null && userDao.usernameExistsIgnoreSelf(normalizedUsername, userId)) {
            throw UsernameAlreadyExistsException("Username is already taken")
        }

        val normalizedFullName = request.fullName?.trim()?.also {
            require(it.isNotBlank()) { "Full name cannot be blank" }
        }

        val normalizedCountry = request.country?.trim()?.also {
            require(it.isNotBlank()) { "Country cannot be blank" }
        }

        request.birthDate?.let {
            require(!it.isAfter(LocalDate.now())) { "Birth date cannot be in the future" }
        }

        var setPhoneNull = false
        val normalizedPhoneNumber = request.phoneNumber?.trim()?.let { trimmed ->
            if (trimmed.isEmpty()) {
                setPhoneNull = true
                null
            } else {
                require(trimmed.length <= maxPhoneNumberLength) {
                    "Phone number must be at most $maxPhoneNumberLength characters"
                }
                if (userDao.phoneNumberExistsIgnoreSelf(trimmed, userId)) {
                    throw PhoneNumberAlreadyExistsException("Phone number is already in use")
                }
                trimmed
            }
        }

        val currentUser = userDao.getUserById(userId) ?: throw UserNotFoundException(userId)

        if (normalizedFullName != null && currentUser.fullNameChangedAt != null) {
            throw FullNameAlreadyChangedException("Full name can only be changed once")
        }
        if (normalizedCountry != null && currentUser.countryChangedAt != null) {
            throw CountryAlreadyChangedException("Country can only be changed once")
        }
        if (request.birthDate != null && currentUser.birthDateChangedAt != null) {
            throw BirthDateAlreadyChangedException("Date of birth can only be changed once")
        }
        if (normalizedUsername != null) {
            currentUser.usernameChangedAt?.let { lastChanged ->
                if (Duration.between(lastChanged, Instant.now()) < monthlyChangeCooldown) {
                    throw UsernameChangeTooSoonException("Username can only be changed once a month")
                }
            }
        }
        if (normalizedPhoneNumber != null || setPhoneNull) {
            currentUser.phoneNumberChangedAt?.let { lastChanged ->
                if (Duration.between(lastChanged, Instant.now()) < monthlyChangeCooldown) {
                    throw PhoneNumberChangeTooSoonException("Phone number can only be changed once a month")
                }
            }
        }

        val effectiveFullName = normalizedFullName?.takeIf { it != currentUser.fullName }
        val effectiveCountry = normalizedCountry?.takeIf { it != currentUser.country }
        val effectiveBirthDate = request.birthDate?.takeIf { it != currentUser.birthDate }
        val effectiveUsername = normalizedUsername?.takeIf { it != currentUser.username }
        val effectivePhoneNumber = normalizedPhoneNumber?.takeIf { it != currentUser.phoneNumber }
        val effectiveSetPhoneNull = setPhoneNull && currentUser.phoneNumber != null

        val updatedRows = userDao.updateUserProfile(
            userId = userId,
            fullName = effectiveFullName,
            username = effectiveUsername,
            country = effectiveCountry,
            phoneNumber = effectivePhoneNumber,
            setPhoneNull = effectiveSetPhoneNull,
            birthDate = effectiveBirthDate,
        )
        if (updatedRows == 0) {
            throw UserNotFoundException(userId)
        }
        val user = requireNotNull(userDao.getUserById(userId)) { "Updated user could not be loaded" }
        val postCount = userDao.countPostsByUser(userId)
        return user.toSelfResponse(postCount = postCount.toInt())
    }

    override suspend fun checkUsernameAvailability(userId: UUID, username: String): UsernameAvailabilityResult {
        val trimmedLower = username.trim().lowercase()
        validateUsernameFormat(trimmedLower)?.let { return it }

        val currentUser = userDao.getUserById(userId)
        if (currentUser != null && currentUser.username.lowercase() == trimmedLower) {
            return UsernameAvailabilityResult(available = true, normalized = trimmedLower, reason = null)
        }

        if (userDao.usernameExistsIgnoreSelf(trimmedLower, userId)) {
            return UsernameAvailabilityResult(available = false, normalized = trimmedLower, reason = "TAKEN")
        }

        return UsernameAvailabilityResult(available = true, normalized = trimmedLower, reason = null)
    }

    override suspend fun checkUsernameAvailabilityForNewUser(username: String): UsernameAvailabilityResult {
        val trimmedLower = username.trim().lowercase()
        validateUsernameFormat(trimmedLower)?.let { return it }

        if (userDao.usernameExistsIgnoreCase(trimmedLower)) {
            return UsernameAvailabilityResult(available = false, normalized = trimmedLower, reason = "TAKEN")
        }

        return UsernameAvailabilityResult(available = true, normalized = trimmedLower, reason = null)
    }

    override suspend fun markFeedOpened(userId: UUID) {
        userDao.updateLastFeedOpenIfStale(userId)
    }

    /** Shared format checks for both [checkUsernameAvailability] and [checkUsernameAvailabilityForNewUser]. */
    private fun validateUsernameFormat(trimmedLower: String): UsernameAvailabilityResult? {
        if (trimmedLower.isBlank()) {
            return UsernameAvailabilityResult(available = false, normalized = trimmedLower, reason = "INVALID_FORMAT")
        }
        if (trimmedLower.length < minUsernameLength) {
            return UsernameAvailabilityResult(available = false, normalized = trimmedLower, reason = "TOO_SHORT")
        }
        if (trimmedLower.length > maxUsernameLength) {
            return UsernameAvailabilityResult(available = false, normalized = trimmedLower, reason = "TOO_LONG")
        }
        if (!usernameRegex.matches(trimmedLower)) {
            return UsernameAvailabilityResult(available = false, normalized = trimmedLower, reason = "INVALID_FORMAT")
        }
        return null
    }

    private fun normalizeUsername(username: String): String {
        val normalized = username.trim().lowercase()
        require(normalized.isNotBlank()) { "Username cannot be blank" }
        require(normalized.length in minUsernameLength..maxUsernameLength) {
            "Username must be between $minUsernameLength and $maxUsernameLength characters"
        }
        require(usernameRegex.matches(normalized)) {
            "Username may contain only lowercase letters, digits, dot, and underscore"
        }
        return normalized
    }

    private fun normalizeOptionalImagePath(imagePath: String?): String? {
        return imagePath?.trim()?.takeIf { it.isNotEmpty() }?.let(storageService::normalizeObjectKey)
    }

    private fun normalizeRequiredImagePath(imagePath: String): String {
        val normalized = imagePath.trim()
        require(normalized.isNotBlank()) { "Profile picture path cannot be blank" }
        return storageService.normalizeObjectKey(normalized)
    }

    private fun User.toResponse(postCount: Int = 0): UserDTO {
        return toDTO(
            profilePictureUrl = profilePicturePath?.let(storageService::resolveUrl),
            postCount = postCount,
            streakDays = StreakCalculator.displayedStreak(
                currentStreak, lastStreakDate, lastStreakTimezone, Instant.now()
            ),
        )
    }

    private fun User.toSelfResponse(postCount: Int = 0): SelfUserDTO {
        return toSelfDTO(
            profilePictureUrl = profilePicturePath?.let(storageService::resolveUrl),
            postCount = postCount,
            streakDays = StreakCalculator.displayedStreak(
                currentStreak, lastStreakDate, lastStreakTimezone, Instant.now()
            ),
        )
    }
}

class UsernameAlreadyExistsException(message: String) : RuntimeException(message)

class UserProfileAlreadyExistsException(message: String) : RuntimeException(message)

class PhoneNumberAlreadyExistsException(message: String) : RuntimeException(message)

class UserNotFoundException(userId: UUID) : RuntimeException("User $userId not found")

class FullNameAlreadyChangedException(message: String) : RuntimeException(message)

class CountryAlreadyChangedException(message: String) : RuntimeException(message)

class BirthDateAlreadyChangedException(message: String) : RuntimeException(message)

class UsernameChangeTooSoonException(message: String) : RuntimeException(message)

class PhoneNumberChangeTooSoonException(message: String) : RuntimeException(message)
