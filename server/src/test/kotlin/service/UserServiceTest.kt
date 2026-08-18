package service

import com.revio.server.core.storage.LocalImageStorageService
import com.revio.server.features.user.BirthDateAlreadyChangedException
import com.revio.server.features.user.CountryAlreadyChangedException
import com.revio.server.features.user.FullNameAlreadyChangedException
import com.revio.server.features.user.IUserDAO
import com.revio.server.features.user.PhoneNumberAlreadyExistsException
import com.revio.server.features.user.PhoneNumberChangeTooSoonException
import com.revio.server.features.user.UserNotFoundException
import com.revio.server.features.user.UserProfileAlreadyExistsException
import com.revio.server.features.user.UserService
import com.revio.server.features.user.UsernameAlreadyExistsException
import com.revio.server.features.user.UsernameChangeTooSoonException
import com.revio.server.features.user.dto.UpdateUserRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import testutils.UserTestSeed
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

class UserServiceTest {

    private fun newService(dao: IUserDAO = mockk(relaxed = true)) = UserService(
        dao,
        LocalImageStorageService(Path.of("/tmp/user-service-test-uploads"), "http://localhost:8080"),
    )

    @Test
    fun `createUserProfile rejects blank username`() = runTest {
        val dao = mockk<IUserDAO>(relaxed = true)
        coEvery { dao.getUserByAuthCredentialId(any()) } returns null
        coEvery { dao.usernameExistsIgnoreCase(any()) } returns false
        val authCredentialId = UUID.randomUUID()
        val service = newService(dao)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                service.createUserProfile(authCredentialId, UserTestSeed.buildUser(authCredentialId, username = "   "))
            }
        }
    }

    @Test
    fun `createUserProfile rejects username that is too short`() = runTest {
        val dao = mockk<IUserDAO>(relaxed = true)
        coEvery { dao.getUserByAuthCredentialId(any()) } returns null
        coEvery { dao.usernameExistsIgnoreCase(any()) } returns false
        val authCredentialId = UUID.randomUUID()
        val service = newService(dao)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                service.createUserProfile(authCredentialId, UserTestSeed.buildUser(authCredentialId, username = "ab"))
            }
        }
    }

    @Test
    fun `createUserProfile rejects invalid username characters`() = runTest {
        val dao = mockk<IUserDAO>(relaxed = true)
        coEvery { dao.getUserByAuthCredentialId(any()) } returns null
        coEvery { dao.usernameExistsIgnoreCase(any()) } returns false
        val authCredentialId = UUID.randomUUID()
        val service = newService(dao)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                service.createUserProfile(authCredentialId, UserTestSeed.buildUser(authCredentialId, username = "bad-name"))
            }
        }
    }

    @Test
    fun `createUserProfile normalizes username to lowercase trimmed before DAO insert`() = runTest {
        val dao = mockk<IUserDAO>()
        val authCredentialId = UUID.randomUUID()
        val capturedUser = slot<com.revio.server.features.user.User>()
        coEvery { dao.getUserByAuthCredentialId(authCredentialId) } returns null
        coEvery { dao.usernameExistsIgnoreCase("alice_1") } returns false
        coEvery { dao.createUser(capture(capturedUser)) } returns com.revio.server.features.user.CreateUserProfileResult(
            userId = UUID.randomUUID(),
            isEarlySpotter = false,
            earlySpotterNumber = null,
            bonusGrantedNow = false,
        )

        val service = newService(dao)
        service.createUserProfile(authCredentialId, UserTestSeed.buildUser(authCredentialId, username = "  Alice_1 "))

        assertEquals("alice_1", capturedUser.captured.username)
    }

    @Test
    fun `createUserProfile blocks duplicate username case-insensitive`() = runTest {
        val dao = mockk<IUserDAO>()
        val authCredentialId = UUID.randomUUID()
        coEvery { dao.getUserByAuthCredentialId(authCredentialId) } returns null
        coEvery { dao.usernameExistsIgnoreCase("alice") } returns true

        val service = newService(dao)
        assertThrows(UsernameAlreadyExistsException::class.java) {
            runBlocking {
                service.createUserProfile(authCredentialId, UserTestSeed.buildUser(authCredentialId, username = "Alice"))
            }
        }
    }

    @Test
    fun `createUserProfile prevents creating a second profile for same authCredentialId`() = runTest {
        val dao = mockk<IUserDAO>()
        val authCredentialId = UUID.randomUUID()
        coEvery { dao.getUserByAuthCredentialId(authCredentialId) } returns UserTestSeed.buildUser(authCredentialId, username = "alice")

        val service = newService(dao)
        assertThrows(UserProfileAlreadyExistsException::class.java) {
            runBlocking {
                service.createUserProfile(authCredentialId, UserTestSeed.buildUser(authCredentialId, username = "bob"))
            }
        }
    }

    @Test
    fun `updateProfilePicture rejects blank image path`() = runTest {
        val service = newService()

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { service.updateProfilePicture(UUID.randomUUID(), "   ") }
        }
    }

    @Test
    fun `updateProfilePicture throws UserNotFoundException when user does not exist`() = runTest {
        val dao = mockk<IUserDAO>()
        val userId = UUID.randomUUID()
        coEvery { dao.updateProfilePicture(userId, "a.jpg") } returns 0

        val service = newService(dao)
        assertThrows(UserNotFoundException::class.java) {
            runBlocking { service.updateProfilePicture(userId, "/uploads/a.jpg") }
        }
    }

    @Test
    fun `updateProfilePicture trims path and reloads updated user`() = runTest {
        val dao = mockk<IUserDAO>()
        val userId = UUID.randomUUID()
        val authCredentialId = UUID.randomUUID()
        val updatedPath = slot<String>()
        val updatedUser = UserTestSeed.buildUser(authCredentialId, username = "alice", profilePicturePath = "a.jpg").copy(id = userId)

        coEvery { dao.updateProfilePicture(userId, capture(updatedPath)) } returns 1
        coEvery { dao.getUserById(userId) } returns updatedUser
        coEvery { dao.countPostsByUser(userId) } returns 0L

        val service = newService(dao)
        val dto = service.updateProfilePicture(userId, "  /uploads/a.jpg  ")

        assertEquals("a.jpg", updatedPath.captured)
        assertEquals("http://localhost:8080/uploads/a.jpg", dto.profilePicturePath)
    }

    @Test
    fun `getUserById returns active streakDays when lastStreakDate is today`() = runTest {
        val dao = mockk<IUserDAO>()
        val userId = UUID.randomUUID()
        val authCredentialId = UUID.randomUUID()
        val user = UserTestSeed.buildUser(authCredentialId, username = "alice").copy(
            id = userId,
            currentStreak = 7,
            lastStreakDate = LocalDate.now(),
            lastStreakTimezone = "UTC",
        )
        coEvery { dao.getUserById(userId) } returns user
        coEvery { dao.countPostsByUser(userId) } returns 0L

        val dto = newService(dao).getUserById(userId)

        assertEquals(7, dto?.streakDays)
    }

    @Test
    fun `getUserById returns 0 streakDays when streak is expired`() = runTest {
        val dao = mockk<IUserDAO>()
        val userId = UUID.randomUUID()
        val authCredentialId = UUID.randomUUID()
        val user = UserTestSeed.buildUser(authCredentialId, username = "alice").copy(
            id = userId,
            currentStreak = 5,
            lastStreakDate = LocalDate.now().minusDays(2),
            lastStreakTimezone = "UTC",
        )
        coEvery { dao.getUserById(userId) } returns user
        coEvery { dao.countPostsByUser(userId) } returns 0L

        val dto = newService(dao).getUserById(userId)

        assertEquals(0, dto?.streakDays)
    }

    @Test
    fun `getUserById returns 0 streakDays when lastStreakDate is null`() = runTest {
        val dao = mockk<IUserDAO>()
        val userId = UUID.randomUUID()
        val authCredentialId = UUID.randomUUID()
        val user = UserTestSeed.buildUser(authCredentialId, username = "alice").copy(
            id = userId,
            currentStreak = 3,
            lastStreakDate = null,
            lastStreakTimezone = null,
        )
        coEvery { dao.getUserById(userId) } returns user
        coEvery { dao.countPostsByUser(userId) } returns 0L

        val dto = newService(dao).getUserById(userId)

        assertEquals(0, dto?.streakDays)
    }

    @Test
    fun `updateProfilePicture normalizes absolute uploads URL before storing`() = runTest {
        val dao = mockk<IUserDAO>()
        val userId = UUID.randomUUID()
        val authCredentialId = UUID.randomUUID()
        val updatedPath = slot<String>()
        val updatedUser = UserTestSeed.buildUser(
            authCredentialId,
            username = "alice",
            profilePicturePath = "profile-pictures/a.jpg",
        ).copy(id = userId)

        coEvery { dao.updateProfilePicture(userId, capture(updatedPath)) } returns 1
        coEvery { dao.getUserById(userId) } returns updatedUser
        coEvery { dao.countPostsByUser(userId) } returns 0L

        val service = newService(dao)
        val dto = service.updateProfilePicture(userId, "http://10.0.2.2:8080/uploads/profile-pictures/a.jpg")

        assertEquals("profile-pictures/a.jpg", updatedPath.captured)
        assertEquals("http://localhost:8080/uploads/profile-pictures/a.jpg", dto.profilePicturePath)
    }

    @Test
    fun `updateUserProfile updates fullName`() = runTest {
        val dao = mockk<IUserDAO>()
        val userId = UUID.randomUUID()
        val authCredentialId = UUID.randomUUID()
        val currentUser = UserTestSeed.buildUser(authCredentialId, username = "alice", fullName = "Alice Old").copy(id = userId)
        val updatedUser = currentUser.copy(fullName = "Alice New")

        coEvery { dao.usernameExistsIgnoreSelf(any(), any()) } returns false
        coEvery { dao.phoneNumberExistsIgnoreSelf(any(), any()) } returns false
        coEvery {
            dao.updateUserProfile(userId, "Alice New", null, null, null, false, null)
        } returns 1
        coEvery { dao.getUserById(userId) } returnsMany listOf(currentUser, updatedUser)
        coEvery { dao.countPostsByUser(userId) } returns 0L

        val dto = newService(dao).updateUserProfile(userId, UpdateUserRequest(fullName = "Alice New"))

        assertEquals("Alice New", dto.fullName)
    }

    @Test
    fun `updateUserProfile updates country`() = runTest {
        val dao = mockk<IUserDAO>()
        val userId = UUID.randomUUID()
        val authCredentialId = UUID.randomUUID()
        val currentUser = UserTestSeed.buildUser(authCredentialId, username = "alice", country = "RO").copy(id = userId)
        val updatedUser = currentUser.copy(country = "US")

        coEvery { dao.usernameExistsIgnoreSelf(any(), any()) } returns false
        coEvery { dao.phoneNumberExistsIgnoreSelf(any(), any()) } returns false
        coEvery {
            dao.updateUserProfile(userId, null, null, "US", null, false, null)
        } returns 1
        coEvery { dao.getUserById(userId) } returnsMany listOf(currentUser, updatedUser)
        coEvery { dao.countPostsByUser(userId) } returns 0L

        val dto = newService(dao).updateUserProfile(userId, UpdateUserRequest(country = "US"))

        assertEquals("US", dto.country)
    }

    @Test
    fun `updateUserProfile updates birthDate`() = runTest {
        val dao = mockk<IUserDAO>()
        val userId = UUID.randomUUID()
        val authCredentialId = UUID.randomUUID()
        val newBirthDate = LocalDate.of(1990, 5, 5)
        val currentUser = UserTestSeed.buildUser(authCredentialId, username = "alice").copy(id = userId)
        val updatedUser = currentUser.copy(birthDate = newBirthDate)

        coEvery { dao.usernameExistsIgnoreSelf(any(), any()) } returns false
        coEvery { dao.phoneNumberExistsIgnoreSelf(any(), any()) } returns false
        coEvery {
            dao.updateUserProfile(userId, null, null, null, null, false, newBirthDate)
        } returns 1
        coEvery { dao.getUserById(userId) } returnsMany listOf(currentUser, updatedUser)
        coEvery { dao.countPostsByUser(userId) } returns 0L

        val dto = newService(dao).updateUserProfile(userId, UpdateUserRequest(birthDate = newBirthDate))

        assertEquals(newBirthDate, dto.birthDate)
    }

    @Test
    fun `updateUserProfile updates phoneNumber`() = runTest {
        val dao = mockk<IUserDAO>()
        val userId = UUID.randomUUID()
        val authCredentialId = UUID.randomUUID()
        val currentUser = UserTestSeed.buildUser(authCredentialId, username = "alice", phoneNumber = null).copy(id = userId)
        val updatedUser = currentUser.copy(phoneNumber = "+40700000000")

        coEvery { dao.usernameExistsIgnoreSelf(any(), any()) } returns false
        coEvery { dao.phoneNumberExistsIgnoreSelf("+40700000000", userId) } returns false
        coEvery {
            dao.updateUserProfile(userId, null, null, null, "+40700000000", false, null)
        } returns 1
        coEvery { dao.getUserById(userId) } returnsMany listOf(currentUser, updatedUser)
        coEvery { dao.countPostsByUser(userId) } returns 0L

        val dto = newService(dao).updateUserProfile(userId, UpdateUserRequest(phoneNumber = "+40700000000"))

        assertEquals("+40700000000", dto.phoneNumber)
    }

    @Test
    fun `updateUserProfile clears phoneNumber when blank is provided`() = runTest {
        val dao = mockk<IUserDAO>()
        val userId = UUID.randomUUID()
        val authCredentialId = UUID.randomUUID()
        val currentUser = UserTestSeed.buildUser(authCredentialId, username = "alice", phoneNumber = "+40700000000").copy(id = userId)
        val updatedUser = currentUser.copy(phoneNumber = null)

        coEvery { dao.usernameExistsIgnoreSelf(any(), any()) } returns false
        coEvery {
            dao.updateUserProfile(userId, null, null, null, null, true, null)
        } returns 1
        coEvery { dao.getUserById(userId) } returnsMany listOf(currentUser, updatedUser)
        coEvery { dao.countPostsByUser(userId) } returns 0L

        val dto = newService(dao).updateUserProfile(userId, UpdateUserRequest(phoneNumber = "   "))

        assertNull(dto.phoneNumber)
        coVerify(exactly = 0) { dao.phoneNumberExistsIgnoreSelf(any(), any()) }
    }

    @Test
    fun `updateUserProfile updates and normalizes username excluding self`() = runTest {
        val dao = mockk<IUserDAO>()
        val userId = UUID.randomUUID()
        val authCredentialId = UUID.randomUUID()
        val currentUser = UserTestSeed.buildUser(authCredentialId, username = "alice").copy(id = userId)
        val updatedUser = currentUser.copy(username = "alice_2")

        coEvery { dao.usernameExistsIgnoreSelf("alice_2", userId) } returns false
        coEvery { dao.phoneNumberExistsIgnoreSelf(any(), any()) } returns false
        coEvery {
            dao.updateUserProfile(userId, null, "alice_2", null, null, false, null)
        } returns 1
        coEvery { dao.getUserById(userId) } returnsMany listOf(currentUser, updatedUser)
        coEvery { dao.countPostsByUser(userId) } returns 0L

        val dto = newService(dao).updateUserProfile(userId, UpdateUserRequest(username = "  Alice_2 "))

        assertEquals("alice_2", dto.username)
    }

    @Test
    fun `updateUserProfile rejects username already taken by another user`() = runTest {
        val dao = mockk<IUserDAO>()
        val userId = UUID.randomUUID()
        coEvery { dao.usernameExistsIgnoreSelf("bob", userId) } returns true

        val service = newService(dao)
        assertThrows(UsernameAlreadyExistsException::class.java) {
            runBlocking { service.updateUserProfile(userId, UpdateUserRequest(username = "bob")) }
        }
    }

    @Test
    fun `updateUserProfile rejects invalid username`() = runTest {
        val dao = mockk<IUserDAO>(relaxed = true)
        val userId = UUID.randomUUID()

        val service = newService(dao)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { service.updateUserProfile(userId, UpdateUserRequest(username = "bad-name")) }
        }
    }

    @Test
    fun `updateUserProfile rejects phoneNumber already taken by another user`() = runTest {
        val dao = mockk<IUserDAO>()
        val userId = UUID.randomUUID()
        coEvery { dao.phoneNumberExistsIgnoreSelf("+40700000000", userId) } returns true

        val service = newService(dao)
        assertThrows(PhoneNumberAlreadyExistsException::class.java) {
            runBlocking { service.updateUserProfile(userId, UpdateUserRequest(phoneNumber = "+40700000000")) }
        }
    }

    @Test
    fun `updateUserProfile rejects blank fullName`() = runTest {
        val dao = mockk<IUserDAO>(relaxed = true)
        val userId = UUID.randomUUID()

        val service = newService(dao)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { service.updateUserProfile(userId, UpdateUserRequest(fullName = "   ")) }
        }
    }

    @Test
    fun `updateUserProfile rejects birthDate in the future`() = runTest {
        val dao = mockk<IUserDAO>(relaxed = true)
        val userId = UUID.randomUUID()

        val service = newService(dao)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                service.updateUserProfile(userId, UpdateUserRequest(birthDate = LocalDate.now().plusDays(1)))
            }
        }
    }

    @Test
    fun `updateUserProfile throws UserNotFoundException when user does not exist`() = runTest {
        val dao = mockk<IUserDAO>()
        val userId = UUID.randomUUID()
        coEvery { dao.usernameExistsIgnoreSelf(any(), any()) } returns false
        coEvery { dao.phoneNumberExistsIgnoreSelf(any(), any()) } returns false
        coEvery { dao.getUserById(userId) } returns null

        val service = newService(dao)
        assertThrows(UserNotFoundException::class.java) {
            runBlocking { service.updateUserProfile(userId, UpdateUserRequest(fullName = "Alice")) }
        }
    }

    @Test
    fun `updateUserProfile rejects fullName change when already changed once`() = runTest {
        val dao = mockk<IUserDAO>()
        val userId = UUID.randomUUID()
        val authCredentialId = UUID.randomUUID()
        val currentUser = UserTestSeed.buildUser(authCredentialId, username = "alice")
            .copy(id = userId, fullNameChangedAt = Instant.now())

        coEvery { dao.getUserById(userId) } returns currentUser

        val service = newService(dao)
        assertThrows(FullNameAlreadyChangedException::class.java) {
            runBlocking { service.updateUserProfile(userId, UpdateUserRequest(fullName = "Alice New")) }
        }
    }

    @Test
    fun `updateUserProfile rejects country change when already changed once`() = runTest {
        val dao = mockk<IUserDAO>()
        val userId = UUID.randomUUID()
        val authCredentialId = UUID.randomUUID()
        val currentUser = UserTestSeed.buildUser(authCredentialId, username = "alice")
            .copy(id = userId, countryChangedAt = Instant.now())

        coEvery { dao.getUserById(userId) } returns currentUser

        val service = newService(dao)
        assertThrows(CountryAlreadyChangedException::class.java) {
            runBlocking { service.updateUserProfile(userId, UpdateUserRequest(country = "US")) }
        }
    }

    @Test
    fun `updateUserProfile rejects birthDate change when already changed once`() = runTest {
        val dao = mockk<IUserDAO>()
        val userId = UUID.randomUUID()
        val authCredentialId = UUID.randomUUID()
        val currentUser = UserTestSeed.buildUser(authCredentialId, username = "alice")
            .copy(id = userId, birthDateChangedAt = Instant.now())

        coEvery { dao.getUserById(userId) } returns currentUser

        val service = newService(dao)
        assertThrows(BirthDateAlreadyChangedException::class.java) {
            runBlocking {
                service.updateUserProfile(userId, UpdateUserRequest(birthDate = LocalDate.of(1990, 5, 5)))
            }
        }
    }

    @Test
    fun `updateUserProfile rejects username change within the monthly cooldown`() = runTest {
        val dao = mockk<IUserDAO>()
        val userId = UUID.randomUUID()
        val authCredentialId = UUID.randomUUID()
        val currentUser = UserTestSeed.buildUser(authCredentialId, username = "alice")
            .copy(id = userId, usernameChangedAt = Instant.now().minus(10, ChronoUnit.DAYS))

        coEvery { dao.usernameExistsIgnoreSelf("bob", userId) } returns false
        coEvery { dao.getUserById(userId) } returns currentUser

        val service = newService(dao)
        assertThrows(UsernameChangeTooSoonException::class.java) {
            runBlocking { service.updateUserProfile(userId, UpdateUserRequest(username = "bob")) }
        }
    }

    @Test
    fun `updateUserProfile allows username change after the monthly cooldown has elapsed`() = runTest {
        val dao = mockk<IUserDAO>()
        val userId = UUID.randomUUID()
        val authCredentialId = UUID.randomUUID()
        val currentUser = UserTestSeed.buildUser(authCredentialId, username = "alice")
            .copy(id = userId, usernameChangedAt = Instant.now().minus(31, ChronoUnit.DAYS))
        val updatedUser = currentUser.copy(username = "bob", usernameChangedAt = Instant.now())

        coEvery { dao.usernameExistsIgnoreSelf("bob", userId) } returns false
        coEvery { dao.getUserById(userId) } returnsMany listOf(currentUser, updatedUser)
        coEvery { dao.updateUserProfile(userId, null, "bob", null, null, false, null) } returns 1
        coEvery { dao.countPostsByUser(userId) } returns 0L

        val dto = newService(dao).updateUserProfile(userId, UpdateUserRequest(username = "bob"))

        assertEquals("bob", dto.username)
    }

    @Test
    fun `updateUserProfile rejects phoneNumber change within the monthly cooldown`() = runTest {
        val dao = mockk<IUserDAO>()
        val userId = UUID.randomUUID()
        val authCredentialId = UUID.randomUUID()
        val currentUser = UserTestSeed.buildUser(authCredentialId, username = "alice", phoneNumber = "+40700000000")
            .copy(id = userId, phoneNumberChangedAt = Instant.now().minus(5, ChronoUnit.DAYS))

        coEvery { dao.phoneNumberExistsIgnoreSelf("+40711111111", userId) } returns false
        coEvery { dao.getUserById(userId) } returns currentUser

        val service = newService(dao)
        assertThrows(PhoneNumberChangeTooSoonException::class.java) {
            runBlocking { service.updateUserProfile(userId, UpdateUserRequest(phoneNumber = "+40711111111")) }
        }
    }

    @Test
    fun `updateUserProfile still rejects duplicate username before checking cooldown`() = runTest {
        val dao = mockk<IUserDAO>()
        val userId = UUID.randomUUID()
        coEvery { dao.usernameExistsIgnoreSelf("bob", userId) } returns true

        val service = newService(dao)
        assertThrows(UsernameAlreadyExistsException::class.java) {
            runBlocking { service.updateUserProfile(userId, UpdateUserRequest(username = "bob")) }
        }
        coVerify(exactly = 0) { dao.getUserById(any()) }
    }

    @Test
    fun `updateUserProfile does not stamp fullName when the resent value is unchanged`() = runTest {
        val dao = mockk<IUserDAO>()
        val userId = UUID.randomUUID()
        val authCredentialId = UUID.randomUUID()
        val currentUser = UserTestSeed.buildUser(authCredentialId, username = "alice", fullName = "Alice").copy(id = userId)

        coEvery { dao.usernameExistsIgnoreSelf(any(), any()) } returns false
        coEvery { dao.phoneNumberExistsIgnoreSelf(any(), any()) } returns false
        coEvery { dao.updateUserProfile(userId, null, null, null, null, false, null) } returns 1
        coEvery { dao.getUserById(userId) } returns currentUser
        coEvery { dao.countPostsByUser(userId) } returns 0L

        newService(dao).updateUserProfile(userId, UpdateUserRequest(fullName = "Alice"))

        coVerify(exactly = 1) { dao.updateUserProfile(userId, null, null, null, null, false, null) }
    }

    @Test
    fun `updateUserProfile does not stamp phoneNumberChangedAt when clearing an already-null phone`() = runTest {
        val dao = mockk<IUserDAO>()
        val userId = UUID.randomUUID()
        val authCredentialId = UUID.randomUUID()
        val currentUser = UserTestSeed.buildUser(authCredentialId, username = "alice", phoneNumber = null).copy(id = userId)

        coEvery { dao.usernameExistsIgnoreSelf(any(), any()) } returns false
        coEvery { dao.updateUserProfile(userId, null, null, null, null, false, null) } returns 1
        coEvery { dao.getUserById(userId) } returns currentUser
        coEvery { dao.countPostsByUser(userId) } returns 0L

        newService(dao).updateUserProfile(userId, UpdateUserRequest(phoneNumber = "   "))

        coVerify(exactly = 1) { dao.updateUserProfile(userId, null, null, null, null, false, null) }
        coVerify(exactly = 0) { dao.phoneNumberExistsIgnoreSelf(any(), any()) }
    }

    @Test
    fun `checkUsernameAvailability returns available for a free username`() = runTest {
        val dao = mockk<IUserDAO>()
        val userId = UUID.randomUUID()
        val authCredentialId = UUID.randomUUID()
        val currentUser = UserTestSeed.buildUser(authCredentialId, username = "alice").copy(id = userId)

        coEvery { dao.getUserById(userId) } returns currentUser
        coEvery { dao.usernameExistsIgnoreSelf("bob", userId) } returns false

        val result = newService(dao).checkUsernameAvailability(userId, "  Bob ")

        assertEquals(true, result.available)
        assertEquals("bob", result.normalized)
        assertNull(result.reason)
    }

    @Test
    fun `checkUsernameAvailability returns TAKEN for a username used by another user`() = runTest {
        val dao = mockk<IUserDAO>()
        val userId = UUID.randomUUID()
        val authCredentialId = UUID.randomUUID()
        val currentUser = UserTestSeed.buildUser(authCredentialId, username = "alice").copy(id = userId)

        coEvery { dao.getUserById(userId) } returns currentUser
        coEvery { dao.usernameExistsIgnoreSelf("bob", userId) } returns true

        val result = newService(dao).checkUsernameAvailability(userId, "bob")

        assertEquals(false, result.available)
        assertEquals("TAKEN", result.reason)
    }

    @Test
    fun `checkUsernameAvailability returns available for the caller's own current username`() = runTest {
        val dao = mockk<IUserDAO>()
        val userId = UUID.randomUUID()
        val authCredentialId = UUID.randomUUID()
        val currentUser = UserTestSeed.buildUser(authCredentialId, username = "alice").copy(id = userId)

        coEvery { dao.getUserById(userId) } returns currentUser

        val result = newService(dao).checkUsernameAvailability(userId, "ALICE")

        assertEquals(true, result.available)
        assertEquals("alice", result.normalized)
        coVerify(exactly = 0) { dao.usernameExistsIgnoreSelf(any(), any()) }
    }

    @Test
    fun `checkUsernameAvailability flags invalid characters without querying the DAO`() = runTest {
        val dao = mockk<IUserDAO>(relaxed = true)
        val userId = UUID.randomUUID()

        val result = newService(dao).checkUsernameAvailability(userId, "bad-name")

        assertEquals(false, result.available)
        assertEquals("INVALID_FORMAT", result.reason)
        coVerify(exactly = 0) { dao.getUserById(any()) }
    }

    @Test
    fun `checkUsernameAvailability flags a too-short username without querying the DAO`() = runTest {
        val dao = mockk<IUserDAO>(relaxed = true)
        val userId = UUID.randomUUID()

        val result = newService(dao).checkUsernameAvailability(userId, "ab")

        assertEquals(false, result.available)
        assertEquals("TOO_SHORT", result.reason)
        coVerify(exactly = 0) { dao.getUserById(any()) }
    }

    // ---------- checkUsernameAvailabilityForNewUser ----------

    @Test
    fun `checkUsernameAvailabilityForNewUser returns available for a free username`() = runTest {
        val dao = mockk<IUserDAO>()
        coEvery { dao.usernameExistsIgnoreCase("newuser") } returns false

        val result = newService(dao).checkUsernameAvailabilityForNewUser("  NewUser ")

        assertEquals(true, result.available)
        assertEquals("newuser", result.normalized)
        assertNull(result.reason)
    }

    @Test
    fun `checkUsernameAvailabilityForNewUser returns TAKEN for a username already in use`() = runTest {
        val dao = mockk<IUserDAO>()
        coEvery { dao.usernameExistsIgnoreCase("bob") } returns true

        val result = newService(dao).checkUsernameAvailabilityForNewUser("bob")

        assertEquals(false, result.available)
        assertEquals("TAKEN", result.reason)
    }

    @Test
    fun `checkUsernameAvailabilityForNewUser flags invalid characters without querying the DAO`() = runTest {
        val dao = mockk<IUserDAO>(relaxed = true)

        val result = newService(dao).checkUsernameAvailabilityForNewUser("bad-name")

        assertEquals(false, result.available)
        assertEquals("INVALID_FORMAT", result.reason)
        coVerify(exactly = 0) { dao.usernameExistsIgnoreCase(any()) }
    }

    @Test
    fun `checkUsernameAvailabilityForNewUser flags a too-short username without querying the DAO`() = runTest {
        val dao = mockk<IUserDAO>(relaxed = true)

        val result = newService(dao).checkUsernameAvailabilityForNewUser("ab")

        assertEquals(false, result.available)
        assertEquals("TOO_SHORT", result.reason)
        coVerify(exactly = 0) { dao.usernameExistsIgnoreCase(any()) }
    }

    @Test
    fun `checkUsernameAvailabilityForNewUser flags a blank username without querying the DAO`() = runTest {
        val dao = mockk<IUserDAO>(relaxed = true)

        val result = newService(dao).checkUsernameAvailabilityForNewUser("   ")

        assertEquals(false, result.available)
        assertEquals("INVALID_FORMAT", result.reason)
        coVerify(exactly = 0) { dao.usernameExistsIgnoreCase(any()) }
    }
}
