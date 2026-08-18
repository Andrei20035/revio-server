package com.revio.server.features.auth.dto

import com.revio.server.features.auth.AuthCredential
import com.revio.server.features.auth.AuthProvider
import java.util.*

data class AuthDTO(
    val id: UUID,
    val email: String,
    val provider: AuthProvider,
    val userId: UUID? = null,
    /**
     * Set only for a brand-new Google account whose email matches a waitlist entry — the same
     * contract as [com.revio.server.features.auth.dto.AuthResponse.waitlist] on email/password
     * registration. Null for existing-account logins (regular or Google), which never need
     * Profile Customization.
     */
    val waitlist: WaitlistPrefillDTO? = null,
)

fun AuthCredential.toDTO(userId: UUID? = null, waitlist: WaitlistPrefillDTO? = null): AuthDTO {
    return AuthDTO(
        id = this.id!!,
        email = this.email,
        provider = this.provider,
        userId = userId,
        waitlist = waitlist,
    )
}
