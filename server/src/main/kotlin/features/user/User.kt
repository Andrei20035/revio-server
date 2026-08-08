package com.revio.server.features.user

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class User(
    val id: UUID = UUID.randomUUID(),
    val authCredentialId: UUID,
    val profilePicturePath: String? = null,
    val fullName: String,
    val phoneNumber: String?,
    val birthDate: LocalDate,
    val username: String,
    val country: String,
    val spotScore: Int = 0,
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    val lastStreakDate: LocalDate? = null,
    val lastStreakTimezone: String? = null,
    val isEarlySpotter: Boolean = false,
    val earlySpotterNumber: Int? = null,
    val role: UserRole = UserRole.USER,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    val fullNameChangedAt: Instant? = null,
    val countryChangedAt: Instant? = null,
    val birthDateChangedAt: Instant? = null,
    val usernameChangedAt: Instant? = null,
    val phoneNumberChangedAt: Instant? = null,
    val bannedUntil: Instant? = null,
    val banPermanent: Boolean = false,
    val banReason: String? = null,
    val bannedAt: Instant? = null,
    val bannedBy: UUID? = null,
)