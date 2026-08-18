package com.revio.server.features.announcement.dto

import kotlinx.serialization.Serializable

@Serializable
data class AnnouncementAckRequest(
    val key: String,
)
