package dao

import com.revio.server.features.waitlist.ISupabaseWaitlistClient
import com.revio.server.features.waitlist.WaitlistDAO
import com.revio.server.features.waitlist.WaitlistSyncService
import com.revio.server.features.waitlist.WaitlistTable
import com.revio.server.features.waitlist.WaitlistUpsertRow
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import testutils.TestDatabaseFactory
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * WaitlistSyncService is tested against the real WaitlistDAO (Testcontainers PostgreSQL) with
 * only the Supabase client mocked — see the plan's "Teste (client Supabase mock-uit)". waitlist_signups
 * has no FK to any other table (V31), so [TestDatabaseFactory.cleanDatabase]'s TRUNCATE list — which
 * only reaches tables connected to it — doesn't clear it; this class clears it itself in [clean].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WaitlistSyncServiceTest {

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

    // --- Test 1: reconcilierea nu sterge niciodata randuri ---

    @Test
    fun `reconcile never deletes rows even when Supabase returns nothing new`() = runTest {
        waitlistDao.upsertBatch(
            listOf(
                row(email = "a@example.com"),
                row(email = "b@example.com"),
                row(email = "c@example.com"),
            )
        )
        assertEquals(3L, waitlistDao.countAll())

        val client = mockk<ISupabaseWaitlistClient>()
        coEvery { client.fetchPage(any(), any(), any()) } returns emptyList()

        val report = WaitlistSyncService(client, waitlistDao).reconcile()

        assertTrue(report.success)
        assertEquals(3L, waitlistDao.countAll())
    }

    // --- Test 2: un rand absent din raspunsul Supabase lasa copia locala intacta ---

    @Test
    fun `a row missing from the fetched page leaves the local copy of that row intact`() = runTest {
        val keptViaFetch = row(email = "kept@example.com")
        val absentFromSource = row(email = "absent@example.com")
        waitlistDao.upsertBatch(listOf(keptViaFetch, absentFromSource))
        assertEquals(2L, waitlistDao.countAll())

        val client = mockk<ISupabaseWaitlistClient>()
        // Simulates Supabase reporting only one of the two rows on this page (e.g. the other
        // wasn't touched since the watermark, or was deleted upstream) — the DAO's upsertBatch
        // never deletes, so the row absent from this page must still be there afterwards.
        coEvery { client.fetchPage(any(), any(), any()) } returns listOf(keptViaFetch)

        val report = WaitlistSyncService(client, waitlistDao).reconcile()

        assertTrue(report.success)
        assertEquals(2L, waitlistDao.countAll())
        assertNotNull(waitlistDao.findByNormalizedEmail("kept@example.com"))
        assertNotNull(waitlistDao.findByNormalizedEmail("absent@example.com"))
    }

    // --- Test 3: sursa arunca -> watermark neschimbat, exceptia nu iese din serviciu ca eroare fatala ---

    @Test
    fun `when Supabase keeps failing, reconcile reports failure without throwing and leaves the watermark unchanged`() = runTest {
        val seededAt = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1)
        waitlistDao.upsertBatch(listOf(row(email = "existing@example.com", sourceUpdatedAt = seededAt)))
        val watermarkBefore = waitlistDao.maxSourceUpdatedAt()
        assertNotNull(watermarkBefore)

        val client = mockk<ISupabaseWaitlistClient>()
        coEvery { client.fetchPage(any(), any(), any()) } throws RuntimeException("Supabase unreachable")

        // No exception should escape reconcile() — if it does, this test fails with that
        // exception rather than an assertion failure, which is exactly the property being tested.
        val report = WaitlistSyncService(client, waitlistDao).reconcile()

        assertFalse(report.success)
        assertEquals(watermarkBefore, waitlistDao.maxSourceUpdatedAt())
    }

    // --- Test 4: paginarea proceseaza toate paginile ---

    @Test
    fun `pagination walks every page until a short page ends it`() = runTest {
        val page1 = listOf(row(email = "p1a@example.com"), row(email = "p1b@example.com"))
        val page2 = listOf(row(email = "p2a@example.com"))

        val client = mockk<ISupabaseWaitlistClient>()
        coEvery { client.fetchPage(since = null, offset = 0, limit = 2) } returns page1
        coEvery { client.fetchPage(since = null, offset = 2, limit = 2) } returns page2

        val report = WaitlistSyncService(client, waitlistDao, pageSize = 2).reconcile()

        assertTrue(report.success)
        assertEquals(3, report.rowsFetched)
        assertEquals(3, report.inserted)
        assertEquals(3L, waitlistDao.countAll())
        assertNotNull(waitlistDao.findByNormalizedEmail("p1a@example.com"))
        assertNotNull(waitlistDao.findByNormalizedEmail("p1b@example.com"))
        assertNotNull(waitlistDao.findByNormalizedEmail("p2a@example.com"))
    }
}
