package com.revio.server.features.notification

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.not
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

data class UserDevice(
    val id: UUID,
    val userId: UUID,
    val deviceId: String,
    val fcmToken: String?,
    val firebaseProject: FirebaseProject,
    val platform: DevicePlatform,
    val appVersion: String,
    val timezone: String?,
    val locale: String?,
    val isActive: Boolean,
)

interface IUserDeviceDAO {
    /**
     * Registers or refreshes a device's push token for [userId]. Upserts on (userId, deviceId):
     * an existing row for the same device is updated in place rather than duplicated. If
     * [fcmToken] currently belongs to a different (userId, deviceId) row — a stale registration
     * from a reinstall or account switch — that row is deactivated and stripped of the token
     * first, since fcm_token is globally UNIQUE. Returns the id of the (created or updated) row.
     */
    suspend fun registerDevice(
        userId: UUID,
        deviceId: String,
        fcmToken: String,
        firebaseProject: FirebaseProject,
        platform: DevicePlatform,
        appVersion: String,
        timezone: String?,
        locale: String?,
    ): UUID

    /** Fetch the device row for [userId] + [deviceId], or null if never registered. */
    suspend fun findByUserAndDevice(userId: UUID, deviceId: String): UserDevice?

    /** Fetch the device row currently holding [fcmToken], or null if no row holds it. */
    suspend fun findByToken(fcmToken: String): UserDevice?

    /** Fetch a single device row by its own id, or null if it doesn't exist. */
    suspend fun findById(id: UUID): UserDevice?

    /**
     * Every currently-active device registered for [userId] — the fan-out target list for a
     * notification event (plan §18, step 4.5): one outbox row gets enqueued per device returned
     * here.
     */
    suspend fun findActiveByUser(userId: UUID): List<UserDevice>

    /**
     * Deactivates [userId]'s [deviceId] row (is_active=false, fcm_token cleared) — e.g. on
     * logout or explicit unregistration. The row itself is kept as device history, per D4 in the
     * push-notifications plan. @return true if a row was found and deactivated.
     */
    suspend fun deactivate(userId: UUID, deviceId: String): Boolean

    /**
     * Same as [deactivate], keyed by the row's own id instead of (userId, deviceId) — used by the
     * push dispatcher (plan §18, step 3.6) when FCM reports a token as UNREGISTERED/
     * INVALID_ARGUMENT and it only has the outbox row's device id on hand. @return true if a row
     * was found and deactivated.
     */
    suspend fun deactivateById(id: UUID): Boolean
}

class UserDeviceDAO : IUserDeviceDAO {

    override suspend fun registerDevice(
        userId: UUID,
        deviceId: String,
        fcmToken: String,
        firebaseProject: FirebaseProject,
        platform: DevicePlatform,
        appVersion: String,
        timezone: String?,
        locale: String?,
    ): UUID = transaction {
        val now = Instant.now().atOffset(ZoneOffset.UTC)

        // A stale row (different device, or the same device under a different user — e.g. a
        // reinstall or account switch) may already hold this token. It must give it up before we
        // can (re)assign it below, since fcm_token is globally UNIQUE and the constraint is not
        // deferrable.
        UserDeviceTable.update({
            (UserDeviceTable.fcmToken eq fcmToken) and
                not((UserDeviceTable.userId eq userId) and (UserDeviceTable.deviceId eq deviceId))
        }) {
            it[UserDeviceTable.fcmToken] = null
            it[UserDeviceTable.isActive] = false
            it[UserDeviceTable.updatedAt] = now
        }

        val existingId = UserDeviceTable
            .select(UserDeviceTable.id)
            .where { (UserDeviceTable.userId eq userId) and (UserDeviceTable.deviceId eq deviceId) }
            .singleOrNull()
            ?.get(UserDeviceTable.id)?.value

        if (existingId != null) {
            UserDeviceTable.update({ UserDeviceTable.id eq existingId }) {
                it[UserDeviceTable.fcmToken] = fcmToken
                it[UserDeviceTable.firebaseProject] = firebaseProject
                it[UserDeviceTable.platform] = platform
                it[UserDeviceTable.appVersion] = appVersion
                it[UserDeviceTable.timezone] = timezone
                it[UserDeviceTable.locale] = locale
                it[UserDeviceTable.isActive] = true
                it[UserDeviceTable.lastSeenAt] = now
                it[UserDeviceTable.updatedAt] = now
            }
            existingId
        } else {
            UserDeviceTable.insertAndGetId {
                it[UserDeviceTable.userId] = userId
                it[UserDeviceTable.deviceId] = deviceId
                it[UserDeviceTable.fcmToken] = fcmToken
                it[UserDeviceTable.firebaseProject] = firebaseProject
                it[UserDeviceTable.platform] = platform
                it[UserDeviceTable.appVersion] = appVersion
                it[UserDeviceTable.timezone] = timezone
                it[UserDeviceTable.locale] = locale
            }.value
        }
    }

    override suspend fun findByUserAndDevice(userId: UUID, deviceId: String): UserDevice? = transaction {
        UserDeviceTable
            .selectAll()
            .where { (UserDeviceTable.userId eq userId) and (UserDeviceTable.deviceId eq deviceId) }
            .singleOrNull()
            ?.toUserDevice()
    }

    override suspend fun findByToken(fcmToken: String): UserDevice? = transaction {
        UserDeviceTable
            .selectAll()
            .where { UserDeviceTable.fcmToken eq fcmToken }
            .singleOrNull()
            ?.toUserDevice()
    }

    override suspend fun findById(id: UUID): UserDevice? = transaction {
        UserDeviceTable
            .selectAll()
            .where { UserDeviceTable.id eq id }
            .singleOrNull()
            ?.toUserDevice()
    }

    override suspend fun findActiveByUser(userId: UUID): List<UserDevice> = transaction {
        UserDeviceTable
            .selectAll()
            .where { (UserDeviceTable.userId eq userId) and (UserDeviceTable.isActive eq true) }
            .map { it.toUserDevice() }
    }

    override suspend fun deactivate(userId: UUID, deviceId: String): Boolean = transaction {
        val now = Instant.now().atOffset(ZoneOffset.UTC)
        val updated = UserDeviceTable.update({
            (UserDeviceTable.userId eq userId) and (UserDeviceTable.deviceId eq deviceId)
        }) {
            it[UserDeviceTable.isActive] = false
            it[UserDeviceTable.fcmToken] = null
            it[UserDeviceTable.updatedAt] = now
        }
        updated > 0
    }

    override suspend fun deactivateById(id: UUID): Boolean = transaction {
        val now = Instant.now().atOffset(ZoneOffset.UTC)
        val updated = UserDeviceTable.update({ UserDeviceTable.id eq id }) {
            it[UserDeviceTable.isActive] = false
            it[UserDeviceTable.fcmToken] = null
            it[UserDeviceTable.updatedAt] = now
        }
        updated > 0
    }

    private fun ResultRow.toUserDevice() = UserDevice(
        id = this[UserDeviceTable.id].value,
        userId = this[UserDeviceTable.userId].value,
        deviceId = this[UserDeviceTable.deviceId],
        fcmToken = this[UserDeviceTable.fcmToken],
        firebaseProject = this[UserDeviceTable.firebaseProject],
        platform = this[UserDeviceTable.platform],
        appVersion = this[UserDeviceTable.appVersion],
        timezone = this[UserDeviceTable.timezone],
        locale = this[UserDeviceTable.locale],
        isActive = this[UserDeviceTable.isActive],
    )
}
