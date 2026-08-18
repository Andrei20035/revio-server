package dao

import com.revio.server.core.storage.IStorageService
import com.revio.server.features.user.EarlySpotterBonusLedgerTable
import com.revio.server.features.user.UserDao
import com.revio.server.features.user.UserService
import com.revio.server.features.user.UsernameAlreadyExistsException
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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

    /**
     * Guards the Early Spotter idempotency ledger under the same kind of race as above, but on
     * [com.revio.server.features.user.UserTable.authCredentialId]'s own unique index rather than
     * the username one: two concurrent createUserProfile calls for the SAME credential (distinct
     * usernames, so the username race above can't also trigger) must still leave exactly one
     * user row, exactly one early_spotter_bonus_ledger row, and the 300-point bonus applied once
     * — the ledger's idempotency key is derived from the winning userId, so a double win here
     * would double the bonus.
     */
    @Test
    fun `concurrent createUserProfile calls with the same credential - exactly one ledger row and the bonus applied once`() {
        val n = 5
        val credential = UserTestSeed.seedAuthCredential("samecred@example.com")

        val results = runBlocking(Dispatchers.IO) {
            (1..n).map { i ->
                async {
                    runCatching {
                        service.createUserProfile(
                            credential.authCredentialId,
                            UserTestSeed.buildUser(credential.authCredentialId, username = "samecreduser$i"),
                        )
                    }
                }
            }.awaitAll()
        }

        val successes = results.filter { it.isSuccess }
        assertEquals(1, successes.size, "exactly one concurrent create for the same credential should succeed")

        val winnerUserId = successes.single().getOrThrow().userId

        val ledgerRows = transaction {
            EarlySpotterBonusLedgerTable
                .selectAll()
                .where { EarlySpotterBonusLedgerTable.userId eq winnerUserId }
                .count()
        }
        assertEquals(1, ledgerRows.toInt(), "exactly one early_spotter_bonus_ledger row for the winning user")

        val winner = runBlocking { UserDao().getUserById(winnerUserId) }
        assertNotNull(winner)
        assertEquals(300, winner!!.spotScore, "the 300-point bonus must be applied exactly once")
    }
}
