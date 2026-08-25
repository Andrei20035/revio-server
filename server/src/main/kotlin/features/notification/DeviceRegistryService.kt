package com.revio.server.features.notification

import com.revio.server.core.util.requireValidZone
import com.revio.server.features.notification.dto.DeviceDTO
import com.revio.server.features.notification.dto.RegisterDeviceRequest
import com.revio.server.features.notification.dto.toDTO
import java.util.UUID

interface IDeviceRegistryService {
    /**
     * Registers or refreshes [userId]'s device (upsert on userId+deviceId, via
     * [IUserDeviceDAO.registerDevice]). When [RegisterDeviceRequest.timezone] is present it must
     * be a valid IANA zone — validated strictly with [requireValidZone], not the silent-UTC-fallback
     * [com.revio.server.core.util.resolveZone] (that fallback exists for reading already-persisted,
     * best-effort timezones, not for accepting new client input — see ZoneUtils.kt). Throws
     * [IllegalArgumentException] on an invalid timezone.
     */
    suspend fun register(userId: UUID, request: RegisterDeviceRequest): DeviceDTO

    /** Deactivates [userId]'s [deviceId] (e.g. on logout). @return true if a row was found. */
    suspend fun unregister(userId: UUID, deviceId: String): Boolean
}

class DeviceRegistryService(
    private val userDeviceDao: IUserDeviceDAO,
) : IDeviceRegistryService {

    override suspend fun register(userId: UUID, request: RegisterDeviceRequest): DeviceDTO {
        request.timezone?.let { requireValidZone(it) }

        userDeviceDao.registerDevice(
            userId = userId,
            deviceId = request.deviceId,
            fcmToken = request.fcmToken,
            firebaseProject = request.firebaseProject,
            platform = request.platform,
            appVersion = request.appVersion,
            timezone = request.timezone,
            locale = request.locale,
        )

        val device = userDeviceDao.findByUserAndDevice(userId, request.deviceId)
            ?: error("Device row missing immediately after registerDevice for user=$userId device=${request.deviceId}")
        return device.toDTO()
    }

    override suspend fun unregister(userId: UUID, deviceId: String): Boolean =
        userDeviceDao.deactivate(userId, deviceId)
}
