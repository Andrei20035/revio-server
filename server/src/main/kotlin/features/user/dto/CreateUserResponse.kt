package com.revio.server.features.user.dto

import com.revio.server.core.serialization.UUIDSerializer
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class CreateUserResponse(
    val accessToken: String,
    val refreshToken: String,
    @Serializable(with = UUIDSerializer::class)
    val userId: UUID,
    val isEarlySpotter: Boolean = false,
    val earlySpotterNumber: Int? = null,
    /** Non-null only when the 300-point Early Spotter bonus was granted by this exact call. */
    val earlySpotterBonusPoints: Int? = null,
    /**
     * Announcement keys (e.g. "EARLY_SPOTTER_WELCOME", "EARLY_SPOTTER_BONUS") the client can show
     * immediately, without a round trip to the announcements endpoint. Empty when none were
     * created by this call. The announcements endpoint remains the recovery path after
     * restart/relogin/another device.
     */
    val pendingAnnouncements: List<String> = emptyList(),
)
