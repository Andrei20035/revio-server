package service

import at.favre.lib.crypto.bcrypt.BCrypt
import com.revio.server.features.auth.AuthCredential
import com.revio.server.features.auth.AuthProvider
import com.revio.server.features.auth.AuthService
import com.revio.server.features.auth.CredentialCreationException
import com.revio.server.features.auth.GoogleTokenVerifier
import com.revio.server.features.auth.GoogleUser
import com.revio.server.features.auth.IAuthDAO
import com.revio.server.features.user.IUserService
import com.revio.server.features.user.UsernameAvailabilityResult
import com.revio.server.features.user.dto.UserDTO
import com.revio.server.features.waitlist.IWaitlistLookupService
import com.revio.server.features.waitlist.WaitlistEntry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

class AuthServiceTest {

    init {
        // hashEmailForLogging (pas 5.5) is keyed by JWT_SECRET — googleLogin's debug log line
        // reaches it on every call, not just failures.
        System.setProperty("JWT_SECRET", "test-jwt-secret-for-auth-service-test")
    }

    private fun newService(
        dao: IAuthDAO = mockk(relaxed = true),
        userService: IUserService = mockk(relaxed = true),
        verifier: GoogleTokenVerifier = mockk(relaxed = true),
        waitlistLookupService: IWaitlistLookupService = mockk(relaxed = true),
    ): AuthService = AuthService(dao, userService, verifier, waitlistLookupService)

    // ---------- createCredentials ----------

    @Test
    fun `createCredentials REGULAR hashes password and normalizes email`() = runTest {
        val dao = mockk<IAuthDAO>()
        val expectedId = UUID.randomUUID()
        coEvery { dao.getCredentialsForLogin(any()) } returns null
        val saved = slot<AuthCredential>()
        coEvery { dao.createCredentials(capture(saved)) } returns expectedId

        val service = newService(dao = dao)

        val input = AuthCredential(
            email = "  Alice@Example.COM  ",
            password = "Passw0rd!",
            provider = AuthProvider.REGULAR,
            googleId = null
        )

        val id = service.createCredentials(input)

        assertEquals(expectedId, id)
        assertEquals("alice@example.com", saved.captured.email)
        assertNotNull(saved.captured.password)
        assertTrue(saved.captured.password != "Passw0rd!", "password should not be plain text")
        // BCrypt format starts with $2a$, $2b$ or $2y$
        assertTrue(saved.captured.password!!.startsWith("$2"))
        assertNull(saved.captured.googleId)
    }

    @Test
    fun `createCredentials REGULAR without password throws`() = runTest {
        val dao = mockk<IAuthDAO>()
        coEvery { dao.getCredentialsForLogin(any()) } returns null

        val service = newService(dao = dao)

        val input = AuthCredential(
            email = "alice@example.com",
            password = null,
            provider = AuthProvider.REGULAR,
            googleId = null
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { service.createCredentials(input) }
        }
    }

    @Test
    fun `createCredentials GOOGLE without googleId throws`() = runTest {
        val dao = mockk<IAuthDAO>()
        coEvery { dao.getCredentialsForLogin(any()) } returns null

        val service = newService(dao = dao)

        val input = AuthCredential(
            email = "bob@example.com",
            password = null,
            provider = AuthProvider.GOOGLE,
            googleId = null
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { service.createCredentials(input) }
        }
    }

    @Test
    fun `createCredentials fails when email is already registered`() = runTest {
        val dao = mockk<IAuthDAO>()
        coEvery { dao.getCredentialsForLogin("alice@example.com") } returns AuthCredential(
            id = UUID.randomUUID(),
            email = "alice@example.com",
            password = "whatever",
            provider = AuthProvider.REGULAR,
            googleId = null
        )

        val service = newService(dao = dao)

        val input = AuthCredential(
            email = "alice@example.com",
            password = "Passw0rd!",
            provider = AuthProvider.REGULAR,
            googleId = null
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { service.createCredentials(input) }
        }
    }

    @Test
    fun `createCredentials wraps DAO IllegalStateException in CredentialCreationException`() = runTest {
        val dao = mockk<IAuthDAO>()
        coEvery { dao.getCredentialsForLogin(any()) } returns null
        coEvery { dao.createCredentials(any()) } throws IllegalStateException("boom")

        val service = newService(dao = dao)

        val input = AuthCredential(
            email = "alice@example.com",
            password = "Passw0rd!",
            provider = AuthProvider.REGULAR,
            googleId = null
        )

        assertThrows(CredentialCreationException::class.java) {
            runBlocking { service.createCredentials(input) }
        }
    }

    private fun fakeWaitlistEntry(username: String?) = WaitlistEntry(
        id = UUID.randomUUID(),
        email = "alice@example.com",
        emailNormalized = "alice@example.com",
        username = username,
        platform = "ios",
        country = "RO",
        sourceCreatedAt = OffsetDateTime.now(),
        sourceUpdatedAt = OffsetDateTime.now(),
        syncedAt = OffsetDateTime.now(),
    )

    // ---------- regularLogin ----------

    @Test
    fun `regularLogin returns DTO for correct password`() = runTest {
        val dao = mockk<IAuthDAO>()
        val userService = mockk<IUserService>()
        val hashed = BCrypt.withDefaults().hashToString(12, "Passw0rd!".toCharArray())
        val id = UUID.randomUUID()
        val userId = UUID.randomUUID()
        coEvery { dao.getCredentialsForLogin("alice@example.com") } returns AuthCredential(
            id = id,
            email = "alice@example.com",
            password = hashed,
            provider = AuthProvider.REGULAR,
            googleId = null
        )
        coEvery { userService.getUserByAuthCredentialId(id) } returns UserDTO(
            id = userId,
            fullName = "Alice Example",
            username = "alice",
            country = "Romania"
        )

        val service = newService(dao = dao, userService = userService)
        val dto = service.regularLogin("  Alice@Example.COM  ", "Passw0rd!")

        assertNotNull(dto)
        assertEquals(id, dto!!.id)
        assertEquals(userId, dto.userId)
        assertEquals("alice@example.com", dto.email)
    }

    @Test
    fun `regularLogin returns DTO without userId when profile is missing`() = runTest {
        val dao = mockk<IAuthDAO>()
        val userService = mockk<IUserService>()
        val waitlistLookupService = mockk<IWaitlistLookupService>()
        val hashed = BCrypt.withDefaults().hashToString(12, "Passw0rd!".toCharArray())
        val id = UUID.randomUUID()
        coEvery { dao.getCredentialsForLogin("alice@example.com") } returns AuthCredential(
            id = id,
            email = "alice@example.com",
            password = hashed,
            provider = AuthProvider.REGULAR,
            googleId = null
        )
        coEvery { userService.getUserByAuthCredentialId(id) } returns null
        coEvery { waitlistLookupService.lookup("alice@example.com") } returns null

        val service = newService(dao = dao, userService = userService, waitlistLookupService = waitlistLookupService)
        val dto = service.regularLogin("alice@example.com", "Passw0rd!")

        assertNotNull(dto)
        assertNull(dto!!.userId)
    }

    @Test
    fun `regularLogin with credential existing but no profile yet returns waitlist prefill`() = runTest {
        val dao = mockk<IAuthDAO>()
        val userService = mockk<IUserService>()
        val waitlistLookupService = mockk<IWaitlistLookupService>()
        val hashed = BCrypt.withDefaults().hashToString(12, "Passw0rd!".toCharArray())
        val id = UUID.randomUUID()
        coEvery { dao.getCredentialsForLogin("alice@example.com") } returns AuthCredential(
            id = id,
            email = "alice@example.com",
            password = hashed,
            provider = AuthProvider.REGULAR,
            googleId = null
        )
        coEvery { userService.getUserByAuthCredentialId(id) } returns null
        coEvery { waitlistLookupService.lookup("alice@example.com") } returns fakeWaitlistEntry("coolname")
        coEvery { userService.checkUsernameAvailabilityForNewUser("coolname") } returns UsernameAvailabilityResult(
            available = true,
            normalized = "coolname",
            reason = null,
        )

        val service = newService(dao = dao, userService = userService, waitlistLookupService = waitlistLookupService)
        val dto = service.regularLogin("alice@example.com", "Passw0rd!")

        assertNotNull(dto)
        assertNull(dto!!.userId)
        assertNotNull(dto.waitlist)
        assertEquals("coolname", dto.waitlist!!.suggestedUsername)
    }

    @Test
    fun `regularLogin with an existing profile does not look up the waitlist`() = runTest {
        val dao = mockk<IAuthDAO>()
        val userService = mockk<IUserService>()
        val waitlistLookupService = mockk<IWaitlistLookupService>(relaxed = true)
        val hashed = BCrypt.withDefaults().hashToString(12, "Passw0rd!".toCharArray())
        val id = UUID.randomUUID()
        val userId = UUID.randomUUID()
        coEvery { dao.getCredentialsForLogin("alice@example.com") } returns AuthCredential(
            id = id,
            email = "alice@example.com",
            password = hashed,
            provider = AuthProvider.REGULAR,
            googleId = null
        )
        coEvery { userService.getUserByAuthCredentialId(id) } returns UserDTO(
            id = userId,
            fullName = "Alice Example",
            username = "alice",
            country = "Romania"
        )

        val service = newService(dao = dao, userService = userService, waitlistLookupService = waitlistLookupService)
        val dto = service.regularLogin("alice@example.com", "Passw0rd!")

        assertNotNull(dto)
        assertEquals(userId, dto!!.userId)
        assertNull(dto.waitlist)
        coVerify(exactly = 0) { waitlistLookupService.lookup(any()) }
    }

    @Test
    fun `regularLogin returns null for wrong password`() = runTest {
        val dao = mockk<IAuthDAO>()
        val hashed = BCrypt.withDefaults().hashToString(12, "Passw0rd!".toCharArray())
        coEvery { dao.getCredentialsForLogin("alice@example.com") } returns AuthCredential(
            id = UUID.randomUUID(),
            email = "alice@example.com",
            password = hashed,
            provider = AuthProvider.REGULAR,
            googleId = null
        )

        val service = newService(dao = dao)
        val dto = service.regularLogin("alice@example.com", "WrongPassword!")
        assertNull(dto)
    }

    @Test
    fun `regularLogin returns null for GOOGLE provider`() = runTest {
        val dao = mockk<IAuthDAO>()
        coEvery { dao.getCredentialsForLogin("bob@example.com") } returns AuthCredential(
            id = UUID.randomUUID(),
            email = "bob@example.com",
            password = null,
            provider = AuthProvider.GOOGLE,
            googleId = "gid"
        )

        val service = newService(dao = dao)
        val dto = service.regularLogin("bob@example.com", "anything")
        assertNull(dto)
    }

    @Test
    fun `regularLogin returns null for unknown email`() = runTest {
        val dao = mockk<IAuthDAO>()
        coEvery { dao.getCredentialsForLogin(any()) } returns null

        val service = newService(dao = dao)
        val dto = service.regularLogin("nobody@example.com", "whatever")
        assertNull(dto)
    }

    // ---------- googleLogin ----------

    @Test
    fun `googleLogin with invalid token returns null`() = runTest {
        val dao = mockk<IAuthDAO>(relaxed = true)
        val verifier = mockk<GoogleTokenVerifier>()
        coEvery { verifier.verify(any()) } returns null

        val service = newService(dao = dao, verifier = verifier)
        val dto = service.googleLogin("bad-token")
        assertNull(dto)
    }

    @Test
    fun `googleLogin with valid token and new account creates GOOGLE credential`() = runTest {
        val dao = mockk<IAuthDAO>()
        val verifier = mockk<GoogleTokenVerifier>()
        val newId = UUID.randomUUID()

        coEvery { verifier.verify("valid-token") } returns GoogleUser(
            email = "bob@example.com",
            googleId = "google-sub-123"
        )
        coEvery { dao.getCredentialsForLogin("bob@example.com") } returns null
        val saved = slot<AuthCredential>()
        coEvery { dao.createCredentials(capture(saved)) } returns newId

        val service = newService(dao = dao, verifier = verifier)
        val dto = service.googleLogin("valid-token")

        assertNotNull(dto)
        assertEquals("bob@example.com", dto!!.email)
        assertEquals(AuthProvider.GOOGLE, dto.provider)
        assertEquals(newId, dto.id)
        assertEquals("google-sub-123", saved.captured.googleId)
        assertNull(saved.captured.password)
    }

    @Test
    fun `googleLogin with existing REGULAR email throws`() = runTest {
        val dao = mockk<IAuthDAO>()
        val verifier = mockk<GoogleTokenVerifier>()

        coEvery { verifier.verify(any()) } returns GoogleUser(
            email = "alice@example.com",
            googleId = "google-sub-999"
        )
        coEvery { dao.getCredentialsForLogin("alice@example.com") } returns AuthCredential(
            id = UUID.randomUUID(),
            email = "alice@example.com",
            password = "hashed",
            provider = AuthProvider.REGULAR,
            googleId = null
        )

        val service = newService(dao = dao, verifier = verifier)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { service.googleLogin("valid-token") }
        }
    }

    @Test
    fun `googleLogin with existing GOOGLE matching googleId returns DTO`() = runTest {
        val dao = mockk<IAuthDAO>()
        val userService = mockk<IUserService>()
        val verifier = mockk<GoogleTokenVerifier>()
        val existingId = UUID.randomUUID()
        val userId = UUID.randomUUID()

        coEvery { verifier.verify(any()) } returns GoogleUser(
            email = "bob@example.com",
            googleId = "gid-1"
        )
        coEvery { dao.getCredentialsForLogin("bob@example.com") } returns AuthCredential(
            id = existingId,
            email = "bob@example.com",
            password = null,
            provider = AuthProvider.GOOGLE,
            googleId = "gid-1"
        )
        coEvery { userService.getUserByAuthCredentialId(existingId) } returns UserDTO(
            id = userId,
            fullName = "Bob Example",
            username = "bob",
            country = "Romania"
        )

        val service = newService(dao = dao, userService = userService, verifier = verifier)
        val dto = service.googleLogin("valid-token")

        assertNotNull(dto)
        assertEquals(existingId, dto!!.id)
        assertEquals(userId, dto.userId)
    }

    @Test
    fun `googleLogin with existing GOOGLE matching googleId but no profile yet returns waitlist prefill`() = runTest {
        val dao = mockk<IAuthDAO>()
        val userService = mockk<IUserService>()
        val verifier = mockk<GoogleTokenVerifier>()
        val waitlistLookupService = mockk<IWaitlistLookupService>()
        val existingId = UUID.randomUUID()

        coEvery { verifier.verify(any()) } returns GoogleUser(
            email = "bob@example.com",
            googleId = "gid-1"
        )
        coEvery { dao.getCredentialsForLogin("bob@example.com") } returns AuthCredential(
            id = existingId,
            email = "bob@example.com",
            password = null,
            provider = AuthProvider.GOOGLE,
            googleId = "gid-1"
        )
        coEvery { userService.getUserByAuthCredentialId(existingId) } returns null
        coEvery { waitlistLookupService.lookup("bob@example.com") } returns fakeWaitlistEntry("bobbywait")
        coEvery { userService.checkUsernameAvailabilityForNewUser("bobbywait") } returns UsernameAvailabilityResult(
            available = true,
            normalized = "bobbywait",
            reason = null,
        )

        val service = newService(
            dao = dao,
            userService = userService,
            verifier = verifier,
            waitlistLookupService = waitlistLookupService,
        )
        val dto = service.googleLogin("valid-token")

        assertNotNull(dto)
        assertNull(dto!!.userId)
        assertNotNull(dto.waitlist)
        assertEquals("bobbywait", dto.waitlist!!.suggestedUsername)
    }

    @Test
    fun `googleLogin with existing GOOGLE but different googleId returns null`() = runTest {
        val dao = mockk<IAuthDAO>()
        val verifier = mockk<GoogleTokenVerifier>()

        coEvery { verifier.verify(any()) } returns GoogleUser(
            email = "bob@example.com",
            googleId = "gid-NEW"
        )
        coEvery { dao.getCredentialsForLogin("bob@example.com") } returns AuthCredential(
            id = UUID.randomUUID(),
            email = "bob@example.com",
            password = null,
            provider = AuthProvider.GOOGLE,
            googleId = "gid-OLD"
        )

        val service = newService(dao = dao, verifier = verifier)
        val dto = service.googleLogin("valid-token")
        assertNull(dto)
    }

    @Test
    fun `googleLogin on a new account looks up the waitlist with the normalized email`() = runTest {
        val dao = mockk<IAuthDAO>()
        val verifier = mockk<GoogleTokenVerifier>()
        val waitlistLookupService = mockk<IWaitlistLookupService>(relaxed = true)

        coEvery { verifier.verify(any()) } returns GoogleUser(
            email = "foo@bar.com",
            googleId = "gid-new"
        )
        coEvery { dao.getCredentialsForLogin("foo@bar.com") } returns null
        coEvery { dao.createCredentials(any()) } returns UUID.randomUUID()

        val service = newService(dao = dao, verifier = verifier, waitlistLookupService = waitlistLookupService)
        service.googleLogin("valid-token")

        coVerify(exactly = 1) { waitlistLookupService.lookup("foo@bar.com") }
    }

    @Test
    fun `googleLogin on an existing matching GOOGLE account does not look up the waitlist`() = runTest {
        val dao = mockk<IAuthDAO>()
        val userService = mockk<IUserService>(relaxed = true)
        val verifier = mockk<GoogleTokenVerifier>()
        val waitlistLookupService = mockk<IWaitlistLookupService>(relaxed = true)

        coEvery { verifier.verify(any()) } returns GoogleUser(email = "bob@example.com", googleId = "gid-1")
        coEvery { dao.getCredentialsForLogin("bob@example.com") } returns AuthCredential(
            id = UUID.randomUUID(),
            email = "bob@example.com",
            password = null,
            provider = AuthProvider.GOOGLE,
            googleId = "gid-1"
        )

        val service = newService(
            dao = dao,
            userService = userService,
            verifier = verifier,
            waitlistLookupService = waitlistLookupService,
        )
        service.googleLogin("valid-token")

        coVerify(exactly = 0) { waitlistLookupService.lookup(any()) }
    }

    @Test
    fun `googleLogin on an email already registered with password login does not look up the waitlist`() = runTest {
        val dao = mockk<IAuthDAO>()
        val verifier = mockk<GoogleTokenVerifier>()
        val waitlistLookupService = mockk<IWaitlistLookupService>(relaxed = true)

        coEvery { verifier.verify(any()) } returns GoogleUser(email = "alice@example.com", googleId = "gid-999")
        coEvery { dao.getCredentialsForLogin("alice@example.com") } returns AuthCredential(
            id = UUID.randomUUID(),
            email = "alice@example.com",
            password = "hashed",
            provider = AuthProvider.REGULAR,
            googleId = null
        )

        val service = newService(dao = dao, verifier = verifier, waitlistLookupService = waitlistLookupService)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { service.googleLogin("valid-token") }
        }

        coVerify(exactly = 0) { waitlistLookupService.lookup(any()) }
    }

    // ---------- updatePassword ----------

    @Test
    fun `updatePassword verifies old password, hashes new one, calls DAO`() = runTest {
        val dao = mockk<IAuthDAO>()
        val credentialId = UUID.randomUUID()
        val oldHash = BCrypt.withDefaults().hashToString(12, "OldPass!1".toCharArray())

        coEvery { dao.getCredentialsById(credentialId) } returns AuthCredential(
            id = credentialId,
            email = "alice@example.com",
            password = oldHash,
            provider = AuthProvider.REGULAR,
            googleId = null
        )
        val newHashSlot = slot<String>()
        coEvery { dao.updatePassword(credentialId, capture(newHashSlot)) } returns 1

        val service = newService(dao = dao)
        val rows = service.updatePassword(credentialId, "OldPass!1", "NewPass!2")

        assertEquals(1, rows)
        assertTrue(newHashSlot.captured.startsWith("$2"))
        // ensure the new hash verifies against the new plain password
        val verifies = BCrypt.verifyer().verify("NewPass!2".toCharArray(), newHashSlot.captured).verified
        assertTrue(verifies)
        coVerify(exactly = 1) { dao.updatePassword(credentialId, any()) }
    }

    @Test
    fun `updatePassword rejects wrong old password`() = runTest {
        val dao = mockk<IAuthDAO>()
        val credentialId = UUID.randomUUID()
        val oldHash = BCrypt.withDefaults().hashToString(12, "OldPass!1".toCharArray())

        coEvery { dao.getCredentialsById(credentialId) } returns AuthCredential(
            id = credentialId,
            email = "alice@example.com",
            password = oldHash,
            provider = AuthProvider.REGULAR,
            googleId = null
        )

        val service = newService(dao = dao)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                service.updatePassword(credentialId, "WRONG-OLD", "NewPass!2")
            }
        }
    }

    @Test
    fun `updatePassword rejects GOOGLE accounts`() = runTest {
        val dao = mockk<IAuthDAO>()
        val credentialId = UUID.randomUUID()
        coEvery { dao.getCredentialsById(credentialId) } returns AuthCredential(
            id = credentialId,
            email = "bob@example.com",
            password = null,
            provider = AuthProvider.GOOGLE,
            googleId = "gid"
        )

        val service = newService(dao = dao)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                service.updatePassword(credentialId, "anything", "NewPass!2")
            }
        }
    }

    @Test
    fun `updatePassword throws when credential not found`() = runTest {
        val dao = mockk<IAuthDAO>()
        coEvery { dao.getCredentialsById(any()) } returns null

        val service = newService(dao = dao)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                service.updatePassword(UUID.randomUUID(), "x", "NewPass!2")
            }
        }
    }
}
