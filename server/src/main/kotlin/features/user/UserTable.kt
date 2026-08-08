package com.revio.server.features.user

import com.revio.server.features.auth.AuthTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestamp

object UserTable : UUIDTable("users") {
    val authCredentialId = uuid("auth_credential_id").uniqueIndex().references(AuthTable.id, onDelete = ReferenceOption.CASCADE)
    val profilePicturePath = text("profile_picture_path").nullable()
    val fullName = varchar("full_name", 150)
    val phoneNumber = varchar("phone_number", 20).nullable()
    val birthDate = date("birth_date")
    val username = varchar("username", 50)
    val country = varchar("country", 50)
    val spotScore = integer("spot_score").default(0)
    val currentStreak = integer("current_streak").default(0)
    val longestStreak = integer("longest_streak").default(0)
    val lastStreakDate = date("last_streak_date").nullable()
    val lastStreakTimezone = varchar("last_streak_timezone", 64).nullable()
    val isEarlySpotter = bool("is_early_spotter").default(false)
    val earlySpotterNumber = integer("early_spotter_number").nullable()
    val role = enumerationByName("role", 20, UserRole::class).default(UserRole.USER)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    val fullNameChangedAt = timestamp("full_name_changed_at").nullable()
    val countryChangedAt = timestamp("country_changed_at").nullable()
    val birthDateChangedAt = timestamp("birth_date_changed_at").nullable()
    val usernameChangedAt = timestamp("username_changed_at").nullable()
    val phoneNumberChangedAt = timestamp("phone_number_changed_at").nullable()
    val bannedUntil = timestamp("banned_until").nullable()
    val banPermanent = bool("ban_permanent").default(false)
    val banReason = text("ban_reason").nullable()
    val bannedAt = timestamp("banned_at").nullable()
    val bannedBy = uuid("banned_by").references(UserTable.id, onDelete = ReferenceOption.SET_NULL).nullable()
}