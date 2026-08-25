package service

import com.revio.server.features.auth.RefreshTokenGenerator
import com.revio.server.features.auth.session.AuthSessionDAO
import com.revio.server.features.auth.session.RevokeReason
import com.revio.server.features.auth.session.SessionScope
import com.revio.server.features.auth.session.SessionService
import com.revio.server.features.notification.DevicePlatform
import com.revio.server.features.notification.FirebaseProject
import com.revio.server.features.notification.UserDeviceDAO
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import testutils.TestDatabaseFactory
import testutils.UserTestSeed
import java.util.UUID

/**
 * Verifies the 1.8 hook: SessionService deactivates a user_devices row when a session is revoked
 * for a device-deactivating reason (LOGOUT, LOGOUT_ALL, SUPERSEDED, ACCOUNT_DELETED,
 * ACCOUNT_SUSPENDED). Uses a real DB (not mocked DAOs) since the behavior spans two tables
 * (auth_sessions + user_devices) via SessionService's default IUserDeviceDAO collaborator.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionDeviceDeactivationTest {

    private val authSessionDao = AuthSessionDAO()
    private val userDeviceDao = UserDeviceDAO()
    private val generator = RefreshTokenGenerator()
    private val service = SessionService(authSessionDao, generator, userDeviceDao)

    @BeforeAll
    fun setup() {
        TestDatabaseFactory.start()
    }

    @AfterAll
    fun tearDown() {
        TestDatabaseFactory.stop()
    }

    @BeforeEach
    fun clean() {
        TestDatabaseFactory.cleanDatabase()
    }

    private fun seedUser(email: String = "user@example.com", username: String = "alice"): Pair<UUID, UUID> {
        val cred = UserTestSeed.seedAuthCredential(email)
        val userId = UserTestSeed.seedUser(cred.authCredentialId, username = username)
        return cred.authCredentialId to userId
    }

    private suspend fun registerDevice(userId: UUID, deviceId: String, token: String = "token-$deviceId") {
        userDeviceDao.registerDevice(
            userId = userId,
            deviceId = deviceId,
            fcmToken = token,
            firebaseProject = FirebaseProject.DEBUG,
            platform = DevicePlatform.ANDROID,
            appVersion = "1.0.0",
            timezone = null,
            locale = null,
        )
    }

    @Test
    fun `revokeSession with LOGOUT deactivates the session's device`() = runTest {
        val (authId, userId) = seedUser()
        registerDevice(userId, "device-1")
        val (session) = service.createSession(
            credentialId = authId, scope = SessionScope.FULL, userId = userId,
            deviceId = "device-1", deviceName = null, userAgent = null, ip = null,
        )

        service.revokeSession(session.id, RevokeReason.LOGOUT)

        val device = userDeviceDao.findByUserAndDevice(userId, "device-1")
        assertEquals(false, device?.isActive)
        assertNull(device?.fcmToken)
    }

    @Test
    fun `revokeSession with a non-deactivating reason leaves the device active`() = runTest {
        val (authId, userId) = seedUser()
        registerDevice(userId, "device-1")
        val (session) = service.createSession(
            credentialId = authId, scope = SessionScope.FULL, userId = userId,
            deviceId = "device-1", deviceName = null, userAgent = null, ip = null,
        )

        service.revokeSession(session.id, RevokeReason.IDLE_EXPIRED)

        val device = userDeviceDao.findByUserAndDevice(userId, "device-1")
        assertTrue(device?.isActive == true)
    }

    @Test
    fun `revokeAllSessions with LOGOUT_ALL deactivates the credential's device`() = runTest {
        val (authId, userId) = seedUser()
        registerDevice(userId, "device-1")
        service.createSession(
            credentialId = authId, scope = SessionScope.FULL, userId = userId,
            deviceId = "device-1", deviceName = null, userAgent = null, ip = null,
        )

        service.revokeAllSessions(authId, RevokeReason.LOGOUT_ALL)

        val device = userDeviceDao.findByUserAndDevice(userId, "device-1")
        assertEquals(false, device?.isActive)
    }

    @Test
    fun `revokeAllSessions with ACCOUNT_SUSPENDED deactivates the device`() = runTest {
        val (authId, userId) = seedUser()
        registerDevice(userId, "device-1")
        service.createSession(
            credentialId = authId, scope = SessionScope.FULL, userId = userId,
            deviceId = "device-1", deviceName = null, userAgent = null, ip = null,
        )

        service.revokeAllSessions(authId, RevokeReason.ACCOUNT_SUSPENDED)

        val device = userDeviceDao.findByUserAndDevice(userId, "device-1")
        assertEquals(false, device?.isActive)
    }

    @Test
    fun `revokeAllSessions excludes exceptSessionId from device deactivation`() = runTest {
        val (authId, userId) = seedUser()
        registerDevice(userId, "device-1")
        val (session) = service.createSession(
            credentialId = authId, scope = SessionScope.FULL, userId = userId,
            deviceId = "device-1", deviceName = null, userAgent = null, ip = null,
        )

        // Excludes the only active session — nothing should be revoked or deactivated.
        service.revokeAllSessions(authId, RevokeReason.LOGOUT_ALL, exceptSessionId = session.id)

        val device = userDeviceDao.findByUserAndDevice(userId, "device-1")
        assertTrue(device?.isActive == true)
    }

    @Test
    fun `logging into the same account on a different device deactivates the old device's token (SUPERSEDED)`() = runTest {
        val (authId, userId) = seedUser()
        registerDevice(userId, "device-old")
        registerDevice(userId, "device-new")

        service.createSession(
            credentialId = authId, scope = SessionScope.FULL, userId = userId,
            deviceId = "device-old", deviceName = null, userAgent = null, ip = null,
        )

        // 🔴 Most important privacy test in the plan: a second login for the same credential on
        // a different device must deactivate the OLD device's token so it stops receiving push
        // meant for whoever is now signed in there.
        service.createSession(
            credentialId = authId, scope = SessionScope.FULL, userId = userId,
            deviceId = "device-new", deviceName = null, userAgent = null, ip = null,
        )

        val oldDevice = userDeviceDao.findByUserAndDevice(userId, "device-old")
        val newDevice = userDeviceDao.findByUserAndDevice(userId, "device-new")
        assertEquals(false, oldDevice?.isActive)
        assertNull(oldDevice?.fcmToken)
        assertTrue(newDevice?.isActive == true)
    }

    @Test
    fun `re-logging in on the same device does not deactivate that device's token`() = runTest {
        val (authId, userId) = seedUser()
        registerDevice(userId, "device-1")

        service.createSession(
            credentialId = authId, scope = SessionScope.FULL, userId = userId,
            deviceId = "device-1", deviceName = null, userAgent = null, ip = null,
        )
        // A second login (e.g. after a normal logout+login, or a fresh createSession call) from
        // the SAME device must not deactivate the device that is logging back in.
        service.createSession(
            credentialId = authId, scope = SessionScope.FULL, userId = userId,
            deviceId = "device-1", deviceName = null, userAgent = null, ip = null,
        )

        val device = userDeviceDao.findByUserAndDevice(userId, "device-1")
        assertTrue(device?.isActive == true)
    }

    @Test
    fun `revoking a session with no matching device row does not throw`() = runTest {
        val (authId, userId) = seedUser()
        // No registerDevice call — the device was never registered.
        val (session) = service.createSession(
            credentialId = authId, scope = SessionScope.FULL, userId = userId,
            deviceId = "device-never-registered", deviceName = null, userAgent = null, ip = null,
        )

        service.revokeSession(session.id, RevokeReason.LOGOUT)

        assertNull(userDeviceDao.findByUserAndDevice(userId, "device-never-registered"))
    }

    @Test
    fun `revoking an ONBOARDING session with no userId does not throw`() = runTest {
        val cred = UserTestSeed.seedAuthCredential("onboarding@example.com")
        val (session) = service.createSession(
            credentialId = cred.authCredentialId, scope = SessionScope.ONBOARDING, userId = null,
            deviceId = "device-1", deviceName = null, userAgent = null, ip = null,
        )

        service.revokeSession(session.id, RevokeReason.LOGOUT)
    }
}
