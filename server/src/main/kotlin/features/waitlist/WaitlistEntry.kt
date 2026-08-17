package com.revio.server.features.waitlist

import java.time.OffsetDateTime
import java.util.UUID

/** A row read back from the local waitlist_signups copy — see [WaitlistTable]. */
data class WaitlistEntry(
    val id: UUID,
    val email: String,
    val emailNormalized: String,
    val username: String?,
    val platform: String?,
    val country: String?,
    val sourceCreatedAt: OffsetDateTime,
    val sourceUpdatedAt: OffsetDateTime?,
    val syncedAt: OffsetDateTime,
)
