package dao

import com.revio.server.features.waitlist.ISupabaseWaitlistClient
import com.revio.server.features.waitlist.WaitlistDAO
import com.revio.server.features.waitlist.WaitlistLookupService
import com.revio.server.features.waitlist.WaitlistTable
import com.revio.server.features.waitlist.WaitlistUpsertRow
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import testutils.TestDatabaseFactory
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * waitlist_signups has no FK to any other table (V31), so [TestDatabaseFactory.cleanDatabase]'s
 * TRUNCATE list doesn't reach it — cleared manually in [clean] instead. The DAO side is real
 * (Testcontainers); only the Supabase client is mocked — same approach as WaitlistSyncServiceTest.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WaitlistLookupServiceTest {

    private val waitlistDao = WaitlistDAO()

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
        transaction { WaitlistTable.deleteAll() }
    }

    private fun row(
        id: UUID = UUID.randomUUID(),
        email: String,
        sourceUpdatedAt: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC),
    ) = WaitlistUpsertRow(
        id = id,
        email = email,
        username = "user-${id.toString().take(8)}",
        platform = "ios",
        country = "RO",
        sourceCreatedAt = sourceUpdatedAt,
        sourceUpdatedAt = sourceUpdatedAt,
    )

    /** Pushes every row's synced_at back so the local copy looks like it hasn't synced in a while. */
    private fun backdateAllSyncedAt(minutesAgo: Long) {
        transaction {
            WaitlistTable.update {
                it[syncedAt] = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(minutesAgo)
            }
        }
    }

    private fun rowExistsById(id: UUID): Boolean = transaction {
        WaitlistTable.selectAll().where { WaitlistTable.id eq id }.any()
    }

    // --- Test 1: gasit local -> nu atinge Supabase ---

    @Test
    fun `an email found locally is returned without contacting Supabase`() = runTest {
        waitlistDao.upsertBatch(listOf(row(email = "found@example.com")))
        val client = mockk<ISupabaseWaitlistClient>()

        val service = WaitlistLookupService(waitlistDao, client)
        val result = service.lookup("found@example.com")

        assertNotNull(result)
        assertEquals("found@example.com", result!!.emailNormalized)
        coVerify(exactly = 0) { client.fetchByEmail(any()) }
    }

    // --- Test 2: negasit + sincronizare recenta -> nu atinge Supabase ---

    @Test
    fun `an email missing locally with a recent sync does not contact Supabase`() = runTest {
        waitlistDao.upsertBatch(listOf(row(email = "other@example.com")))
        val client = mockk<ISupabaseWaitlistClient>()

        val service = WaitlistLookupService(waitlistDao, client)
        val result = service.lookup("missing@example.com")

        assertNull(result)
        coVerify(exactly = 0) { client.fetchByEmail(any()) }
    }

    // --- Test 3: negasit + sincronizare veche -> lookup live, rezultatul e persistat local ---

    @Test
    fun `an email missing locally with a stale sync triggers a live lookup and persists the result`() = runTest {
        waitlistDao.upsertBatch(listOf(row(email = "other@example.com")))
        backdateAllSyncedAt(minutesAgo = 5)

        val client = mockk<ISupabaseWaitlistClient>()
        val liveId = UUID.randomUUID()
        coEvery { client.fetchByEmail("live@example.com") } returns row(id = liveId, email = "live@example.com")

        val service = WaitlistLookupService(waitlistDao, client)
        val result = service.lookup("live@example.com")

        assertNotNull(result)
        assertEquals("live@example.com", result!!.emailNormalized)
        coVerify(exactly = 1) { client.fetchByEmail("live@example.com") }
        assertNotNull(waitlistDao.findByNormalizedEmail("live@example.com"))
        assertEquals(true, rowExistsById(liveId))
    }

    // --- Test 4a: Supabase arunca -> "nu e in waitlist", nicio exceptie propagata ---

    @Test
    fun `Supabase throwing during a live lookup returns null without propagating`() = runTest {
        waitlistDao.upsertBatch(listOf(row(email = "seed@example.com")))
        backdateAllSyncedAt(minutesAgo = 5)

        val client = mockk<ISupabaseWaitlistClient>()
        coEvery { client.fetchByEmail("boom@example.com") } throws RuntimeException("Supabase unreachable")

        val service = WaitlistLookupService(waitlistDao, client)
        val result = service.lookup("boom@example.com")

        assertNull(result)
    }

    // --- Test 4b: Supabase expira (timeout) -> "nu e in waitlist", nicio exceptie propagata ---

    @Test
    fun `Supabase timing out during a live lookup returns null without propagating`() = runTest {
        waitlistDao.upsertBatch(listOf(row(email = "seed2@example.com")))
        backdateAllSyncedAt(minutesAgo = 5)

        val client = mockk<ISupabaseWaitlistClient>()
        coEvery { client.fetchByEmail("slow@example.com") } coAnswers {
            delay(5_000)
            row(email = "slow@example.com")
        }

        val service = WaitlistLookupService(waitlistDao, client)
        val result = service.lookup("slow@example.com")

        assertNull(result)
    }

    // --- Test 5: circuit breaker dupa 3 esecuri consecutive ---

    @Test
    fun `the circuit breaker opens after 3 consecutive failures and skips further live lookups`() = runTest {
        waitlistDao.upsertBatch(listOf(row(email = "seed3@example.com")))
        backdateAllSyncedAt(minutesAgo = 5)

        val client = mockk<ISupabaseWaitlistClient>()
        coEvery { client.fetchByEmail(any()) } throws RuntimeException("Supabase unreachable")

        val service = WaitlistLookupService(waitlistDao, client)

        assertNull(service.lookup("fail1@example.com"))
        assertNull(service.lookup("fail2@example.com"))
        assertNull(service.lookup("fail3@example.com"))
        // Breaker should now be open — a 4th miss must not reach the client at all.
        assertNull(service.lookup("fail4@example.com"))

        coVerify(exactly = 3) { client.fetchByEmail(any()) }
    }

    // --- Test 6: recunoastere indiferent de majuscule, cu rezultat deja local (sincronizat din Supabase) ---

    @Test
    fun `an email stored locally with different casing is recognized regardless of the lookup argument's case`() = runTest {
        waitlistDao.upsertBatch(listOf(row(email = "Foo@Bar.com")))
        val client = mockk<ISupabaseWaitlistClient>()

        val service = WaitlistLookupService(waitlistDao, client)
        val result = service.lookup("foo@bar.com")

        assertNotNull(result)
        assertEquals("foo@bar.com", result!!.emailNormalized)
        coVerify(exactly = 0) { client.fetchByEmail(any()) }
    }

    // --- Test 6b: acelasi caz, dar prin lookup live (nu era inca sincronizat local) ---

    @Test
    fun `a mixed-case email returned live from Supabase is recognized when looked up in lowercase`() = runTest {
        waitlistDao.upsertBatch(listOf(row(email = "seed4@example.com")))
        backdateAllSyncedAt(minutesAgo = 5)

        val client = mockk<ISupabaseWaitlistClient>()
        coEvery { client.fetchByEmail("foo@bar.com") } returns row(email = "Foo@Bar.com")

        val service = WaitlistLookupService(waitlistDao, client)
        val result = service.lookup("foo@bar.com")

        assertNotNull(result)
        assertEquals("foo@bar.com", result!!.emailNormalized)
    }
}
