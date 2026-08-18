package dao

import com.revio.server.core.storage.IStorageService
import com.revio.server.features.user.UserDao
import com.revio.server.features.user.UserService
import com.revio.server.features.user.UsernameAlreadyExistsException
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import testutils.TestDatabaseFactory
import testutils.UserTestSeed

/**
 * Guards Risc #3 from the plan: UserService.createUserProfile checks usernameExistsIgnoreCase in
 * its own short transaction, separate from UserDao.createUser's insert. Two concurrent callers
 * can both pass that pre-check before either commits, so the loser's insert can hit
 * idx_users_username_lower's DB-level unique constraint directly. The service must translate that
 * into UsernameAlreadyExistsException (-> 409 at the route layer), not let a raw DB exception
 * leak out as a 500 — this matters more now that waitlist prefill makes username collisions more
 * likely (popular suggested usernames, several users racing to claim the same one).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserServiceConcurrencyTest {

    private val service = UserService(UserDao(), mockk<IStorageService>(relaxed = true))

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
    fun `concurrent createUserProfile calls with the same username - exactly one succeeds, the rest get UsernameAlreadyExistsException not a raw DB error`() {
        val n = 5
        val credentials = (1..n).map { i -> UserTestSeed.seedAuthCredential("racer$i@example.com") }

        val results = runBlocking(Dispatchers.IO) {
            credentials.map { cred ->
                async {
                    runCatching {
                        service.createUserProfile(
                            cred.authCredentialId,
                            UserTestSeed.buildUser(cred.authCredentialId, username = "raceduser"),
                        )
                    }
                }
            }.awaitAll()
        }

        val successes = results.count { it.isSuccess }
        val failures = results.mapNotNull { it.exceptionOrNull() }

        assertEquals(1, successes, "exactly one concurrent create should succeed")
        assertEquals(n - 1, failures.size)
        failures.forEachIndexed { i, e ->
            assertTrue(
                e is UsernameAlreadyExistsException,
                "loser #$i must get UsernameAlreadyExistsException (-> 409), not a raw DB error (-> 500) — was: $e",
            )
        }
    }
}
