package com.revio.server.features.notification.dto

import com.revio.server.core.serialization.UUIDSerializer
import com.revio.server.features.notification.DevicePlatform
import com.revio.server.features.notification.FirebaseProject
import com.revio.server.features.notification.UserDevice
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class RegisterDeviceRequest(
    val deviceId: String,
    val fcmToken: String,
    val firebaseProject: FirebaseProject,
    val platform: DevicePlatform,
    val appVersion: String,
    val timezone: String? = null,
    val locale: String? = null,
)

@Serializable
data class DeviceDTO(
    @Serializable(with = UUIDSerializer::class)
    val id: UUID,
    val deviceId: String,
    val firebaseProject: FirebaseProject,
    val platform: DevicePlatform,
    val appVersion: String,
    val timezone: String?,
    val locale: String?,
    val isActive: Boolean,
)

fun UserDevice.toDTO() = DeviceDTO(
    id = id,
    deviceId = deviceId,
    firebaseProject = firebaseProject,
    platform = platform,
    appVersion = appVersion,
    timezone = timezone,
    locale = locale,
    isActive = isActive,
)
