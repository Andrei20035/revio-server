package dao

import com.revio.server.features.user.UserCreationException
import com.revio.server.features.user.UserDao
import com.revio.server.features.user.UserTable
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import testutils.TestDatabaseFactory
import testutils.UserTestSeed
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserDaoTest {

    private val dao = UserDao()

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

    @Test
    fun `createUser inserts and returns id`() = runTest {
        val credential = UserTestSeed.seedAuthCredential("alice@example.com")
        val user = UserTestSeed.buildUser(credential.authCredentialId, username = "alice")

        val userId = dao.createUser(user).userId

        assertNotNull(userId)
        val stored = dao.getUserById(userId)
        assertEquals("alice", stored!!.username)
    }

    @Test
    fun `getUserById returns user when it exists`() = runTest {
        val credential = UserTestSeed.seedAuthCredential("alice@example.com")
        val userId = UserTestSeed.seedUser(credential.authCredentialId, username = "alice")

        val user = dao.getUserById(userId)

        assertNotNull(user)
        assertEquals(userId, user!!.id)
        assertEquals(credential.authCredentialId, user.authCredentialId)
    }

    @Test
    fun `getUserById returns null for unknown id`() = runTest {
        assertNull(dao.getUserById(UUID.randomUUID()))
    }

    @Test
    fun `getUserByAuthCredentialId returns user when it exists`() = runTest {
        val credential = UserTestSeed.seedAuthCredential("alice@example.com")
        UserTestSeed.seedUser(credential.authCredentialId, username = "alice")

        val user = dao.getUserByAuthCredentialId(credential.authCredentialId)

        assertNotNull(user)
        assertEquals("alice", user!!.username)
    }

    @Test
    fun `getUserByAuthCredentialId returns null when profile does not exist`() = runTest {
        val credential = UserTestSeed.seedAuthCredential("alice@example.com")
        assertNull(dao.getUserByAuthCredentialId(credential.authCredentialId))
    }

    @Test
    fun `duplicate username is blocked`() = runTest {
        val firstCredential = UserTestSeed.seedAuthCredential("alice@example.com")
        val secondCredential = UserTestSeed.seedAuthCredential("bob@example.com")
        dao.createUser(UserTestSeed.buildUser(firstCredential.authCredentialId, username = "alice"))

        assertThrows(ExposedSQLException::class.java) {
            kotlinx.coroutines.runBlocking {
                dao.createUser(UserTestSeed.buildUser(secondCredential.authCredentialId, username = "alice"))
            }
        }
    }

    @Test
    fun `duplicate username is blocked case-insensitive`() = runTest {
        val firstCredential = UserTestSeed.seedAuthCredential("alice@example.com")
        val secondCredential = UserTestSeed.seedAuthCredential("bob@example.com")
        dao.createUser(UserTestSeed.buildUser(firstCredential.authCredentialId, username = "alice"))

        assertThrows(ExposedSQLException::class.java) {
            kotlinx.coroutines.runBlocking {
                dao.createUser(UserTestSeed.buildUser(secondCredential.authCredentialId, username = "ALICE"))
            }
        }
    }

    @Test
    fun `auth_credential_id is unique`() = runTest {
        val credential = UserTestSeed.seedAuthCredential("alice@example.com")
        dao.createUser(UserTestSeed.buildUser(credential.authCredentialId, username = "alice"))

        assertThrows(ExposedSQLException::class.java) {
            kotlinx.coroutines.runBlocking {
                dao.createUser(UserTestSeed.buildUser(credential.authCredentialId, username = "alice.second"))
            }
        }
    }

    @Test
    fun `usernameExistsIgnoreCase returns true only for matching usernames`() = runTest {
        val credential = UserTestSeed.seedAuthCredential("alice@example.com")
        dao.createUser(UserTestSeed.buildUser(credential.authCredentialId, username = "alice"))

        assertTrue(dao.usernameExistsIgnoreCase("ALICE"))
        assertEquals(false, dao.usernameExistsIgnoreCase("bob"))
    }

    @Test
    fun `updateProfilePicture updates row and returns 1`() = runTest {
        val credential = UserTestSeed.seedAuthCredential("alice@example.com")
        val userId = UserTestSeed.seedUser(credential.authCredentialId, username = "alice")

        val rows = dao.updateProfilePicture(userId, "/uploads/alice.jpg")

        assertEquals(1, rows)
        val stored = dao.getUserById(userId)
        assertEquals("/uploads/alice.jpg", stored!!.profilePicturePath)
    }

    @Test
    fun `updateProfilePicture returns 0 for non-existent user`() = runTest {
        val rows = dao.updateProfilePicture(UUID.randomUUID(), "/uploads/ghost.jpg")
        assertEquals(0, rows)
    }

    @Test
    fun `username blank is blocked by DB constraint`() = runTest {
        val credential = UserTestSeed.seedAuthCredential("alice@example.com")

        assertThrows(ExposedSQLException::class.java) {
            kotlinx.coroutines.runBlocking {
                dao.createUser(UserTestSeed.buildUser(credential.authCredentialId, username = "   "))
            }
        }
    }

    @Test
    fun `case-insensitive username unique index exists`() = runTest {
        val indexes = transaction {
            exec(
                """
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename = 'users'
                  AND indexname = 'idx_users_username_lower'
                """.trimIndent()
            ) { rs ->
                buildList {
                    while (rs.next()) add(rs.getString("indexname"))
                }
            } ?: emptyList()
        }
        assertEquals(listOf("idx_users_username_lower"), indexes)
    }

    @Test
    fun `usernameExistsIgnoreSelf returns false for own username and true for another user`() = runTest {
        val aliceCredential = UserTestSeed.seedAuthCredential("alice@example.com")
        val aliceId = UserTestSeed.seedUser(aliceCredential.authCredentialId, username = "alice")
        val bobCredential = UserTestSeed.seedAuthCredential("bob@example.com")
        val bobId = UserTestSeed.seedUser(bobCredential.authCredentialId, username = "bob")

        assertEquals(false, dao.usernameExistsIgnoreSelf("alice", aliceId))
        assertTrue(dao.usernameExistsIgnoreSelf("ALICE", bobId))
    }

    @Test
    fun `phoneNumberExistsIgnoreSelf returns false for own phone and true for another user`() = runTest {
        val aliceCredential = UserTestSeed.seedAuthCredential("alice@example.com")
        val aliceId = UserTestSeed.seedUser(aliceCredential.authCredentialId, username = "alice")
        dao.updateUserProfile(aliceId, phoneNumber = "+40700000000")
        val bobCredential = UserTestSeed.seedAuthCredential("bob@example.com")
        val bobId = UserTestSeed.seedUser(bobCredential.authCredentialId, username = "bob")

        assertEquals(false, dao.phoneNumberExistsIgnoreSelf("+40700000000", aliceId))
        assertTrue(dao.phoneNumberExistsIgnoreSelf("+40700000000", bobId))
    }

    @Test
    fun `updateUserProfile updates only provided fields`() = runTest {
        val credential = UserTestSeed.seedAuthCredential("alice@example.com")
        val userId = UserTestSeed.seedUser(credential.authCredentialId, username = "alice", fullName = "Alice", country = "RO")

        val rows = dao.updateUserProfile(userId, fullName = "Alice New")

        assertEquals(1, rows)
        val stored = dao.getUserById(userId)!!
        assertEquals("Alice New", stored.fullName)
        assertEquals("alice", stored.username)
        assertEquals("RO", stored.country)
    }

    @Test
    fun `updateUserProfile with setPhoneNull clears phone number`() = runTest {
        val credential = UserTestSeed.seedAuthCredential("alice@example.com")
        val userId = UserTestSeed.seedUser(credential.authCredentialId, username = "alice")
        dao.updateUserProfile(userId, phoneNumber = "+40700000000")

        val rows = dao.updateUserProfile(userId, setPhoneNull = true)

        assertEquals(1, rows)
        assertNull(dao.getUserById(userId)!!.phoneNumber)
    }

    @Test
    fun `updateUserProfile returns 0 for non-existent user`() = runTest {
        val rows = dao.updateUserProfile(UUID.randomUUID(), fullName = "Ghost")
        assertEquals(0, rows)
    }

    @Test
    fun `duplicate phone number is blocked`() = runTest {
        val firstCredential = UserTestSeed.seedAuthCredential("alice@example.com")
        val firstUserId = UserTestSeed.seedUser(firstCredential.authCredentialId, username = "alice")
        dao.updateUserProfile(firstUserId, phoneNumber = "+40700000000")

        val secondCredential = UserTestSeed.seedAuthCredential("bob@example.com")
        val secondUserId = UserTestSeed.seedUser(secondCredential.authCredentialId, username = "bob")

        assertThrows(ExposedSQLException::class.java) {
            kotlinx.coroutines.runBlocking {
                dao.updateUserProfile(secondUserId, phoneNumber = "+40700000000")
            }
        }
    }

    @Test
    fun `multiple users can have null phone number`() = runTest {
        val firstCredential = UserTestSeed.seedAuthCredential("alice@example.com")
        val secondCredential = UserTestSeed.seedAuthCredential("bob@example.com")

        UserTestSeed.seedUser(firstCredential.authCredentialId, username = "alice")
        UserTestSeed.seedUser(secondCredential.authCredentialId, username = "bob")

        assertEquals(2, transaction { UserTable.selectAll().count() })
    }

    @Test
    fun `updateUserProfile stamps changedAt timestamps for the fields it writes`() = runTest {
        val credential = UserTestSeed.seedAuthCredential("stamped@example.com")
        val userId = UserTestSeed.seedUser(credential.authCredentialId, username = "stamped", fullName = "Stamped", country = "RO")
        val before = java.time.Instant.now().minusSeconds(1)

        dao.updateUserProfile(
            userId,
            fullName = "Stamped New",
            country = "US",
            birthDate = java.time.LocalDate.of(1990, 1, 1),
        )

        val stored = dao.getUserById(userId)!!
        assertNotNull(stored.fullNameChangedAt)
        assertNotNull(stored.countryChangedAt)
        assertNotNull(stored.birthDateChangedAt)
        assertNull(stored.usernameChangedAt)
        assertNull(stored.phoneNumberChangedAt)
        assertTrue(stored.fullNameChangedAt!!.isAfter(before))
    }

    @Test
    fun `updateUserProfile stamps usernameChangedAt and phoneNumberChangedAt when those fields are provided`() = runTest {
        val credential = UserTestSeed.seedAuthCredential("stampedcontact@example.com")
        val userId = UserTestSeed.seedUser(credential.authCredentialId, username = "stampedcontact")

        dao.updateUserProfile(userId, username = "stampedcontact2", phoneNumber = "+40700000010")

        val stored = dao.getUserById(userId)!!
        assertNotNull(stored.usernameChangedAt)
        assertNotNull(stored.phoneNumberChangedAt)
    }

    @Test
    fun `updateUserProfile stamps phoneNumberChangedAt when clearing phone with setPhoneNull`() = runTest {
        val credential = UserTestSeed.seedAuthCredential("stampedclear@example.com")
        val userId = UserTestSeed.seedUser(credential.authCredentialId, username = "stampedclear")
        dao.updateUserProfile(userId, phoneNumber = "+40700000011")

        dao.updateUserProfile(userId, setPhoneNull = true)

        val stored = dao.getUserById(userId)!!
        assertNull(stored.phoneNumber)
        assertNotNull(stored.phoneNumberChangedAt)
    }

    @Test
    fun `partial unique phone index exists`() = runTest {
        val indexes = transaction {
            exec(
                """
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename = 'users'
                  AND indexname = 'idx_users_phone_number'
                """.trimIndent()
            ) { rs ->
                buildList {
                    while (rs.next()) add(rs.getString("indexname"))
                }
            } ?: emptyList()
        }
        assertEquals(listOf("idx_users_phone_number"), indexes)
    }
}
