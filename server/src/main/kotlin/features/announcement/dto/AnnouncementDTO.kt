package com.revio.server.features.announcement.dto

import kotlinx.serialization.Serializable

@Serializable
data class AnnouncementDTO(
    val key: String,
    val status: String,
    val payload: String? = null,
)
