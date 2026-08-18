package com.revio.server.features.auth.dto

import kotlinx.serialization.Serializable

enum class OnboardingStep {
    PROFILE_REQUIRED, COMPLETED
}

/** Mirrors [com.revio.server.features.user.UsernameAvailabilityResult.reason], plus AVAILABLE for a null reason. */
enum class WaitlistUsernameStatus {
    AVAILABLE, TAKEN, INVALID_FORMAT, TOO_SHORT, TOO_LONG
}

/**
 * Present on [AuthResponse] only when the registering email matches a waitlist entry.
 * [suggestedUsername] is the raw value from Supabase — it may not satisfy Revio's username rules,
 * which is exactly what [suggestedUsernameStatus] reports. The server never invents an
 * alternative; the client prefills the suggestion as-is and lets the existing username validation
 * flow (already required at profile creation) guide the user to fix it if needed.
 */
@Serializable
data class WaitlistPrefillDTO(
    val suggestedUsername: String?,
    val suggestedUsernameStatus: WaitlistUsernameStatus,
)

@Serializable
data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Int,
    val scope: String,
    val onboardingStep: OnboardingStep,
    val waitlist: WaitlistPrefillDTO? = null,
)
