package dao

import com.revio.server.features.waitlist.WaitlistDAO
import com.revio.server.features.waitlist.WaitlistTable
import com.revio.server.features.waitlist.WaitlistUpsertRow
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import testutils.TestDatabaseFactory
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * waitlist_signups has no FK to any other table (see V31), so [TestDatabaseFactory.cleanDatabase]'s
 * TRUNCATE list — which only reaches tables connected to it — doesn't clear it. This class clears
 * it itself in [clean] instead of touching that shared list.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WaitlistDaoTest {

    private val dao = WaitlistDAO()

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
        username: String? = "someuser",
        sourceCreatedAt: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC),
        sourceUpdatedAt: OffsetDateTime? = sourceCreatedAt,
    ) = WaitlistUpsertRow(
        id = id,
        email = email,
        username = username,
        platform = "ios",
        country = "RO",
        sourceCreatedAt = sourceCreatedAt,
        sourceUpdatedAt = sourceUpdatedAt,
    )

    // --- Test 1: email_normalized generat corect pentru " Foo@Bar.COM " ---

    @Test
    fun `email_normalized is lower(trim(email))`() = runTest {
        val id = UUID.randomUUID()
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        transaction {
            WaitlistTable.insert {
                it[WaitlistTable.id] = id
                it[WaitlistTable.email] = " Foo@Bar.COM "
                it[WaitlistTable.username] = "foo"
                it[WaitlistTable.sourceCreatedAt] = now
            }
        }

        val emailNormalized = transaction {
            WaitlistTable
                .selectAll()
                .where { WaitlistTable.id eq id }
                .single()[WaitlistTable.emailNormalized]
        }

        assertEquals("foo@bar.com", emailNormalized)
    }

    // --- Test 2: doua emailuri care difera doar prin majuscule/spatii sunt respinse de indexul unic ---

    @Test
    fun `two emails differing only by case or whitespace violate the unique index`() {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        transaction {
            WaitlistTable.insert {
                it[WaitlistTable.id] = UUID.randomUUID()
                it[WaitlistTable.email] = "dup@example.com"
                it[WaitlistTable.sourceCreatedAt] = now
            }
        }

        assertThrows<ExposedSQLException> {
            transaction {
                WaitlistTable.insert {
                    it[WaitlistTable.id] = UUID.randomUUID()
                    it[WaitlistTable.email] = " Dup@Example.COM "
                    it[WaitlistTable.sourceCreatedAt] = now
                }
            }
        }
    }

    // --- Test 3: upsertBatch repetat cu acelasi lot → unchanged, zero randuri noi ---

    @Test
    fun `upsertBatch repeated with the same batch reports unchanged and adds no new rows`() = runTest {
        val batch = listOf(
            row(email = "a@example.com"),
            row(email = "b@example.com"),
        )

        val first = dao.upsertBatch(batch)
        assertEquals(2, first.inserted)
        assertEquals(0, first.updated)
        assertEquals(0, first.unchanged)
        assertEquals(0, first.conflicted)

        val second = dao.upsertBatch(batch)
        assertEquals(0, second.inserted)
        assertEquals(0, second.updated)
        assertEquals(2, second.unchanged)
        assertEquals(0, second.conflicted)

        assertEquals(2L, dao.countAll())
    }

    // --- Test 4: rand cu source_updated_at mai vechi → nu suprascrie ---

    @Test
    fun `a row with an older source_updated_at does not overwrite the stored row`() = runTest {
        val id = UUID.randomUUID()
        val newer = OffsetDateTime.now(ZoneOffset.UTC)
        val older = newer.minusDays(1)

        val firstResult = dao.upsertBatch(listOf(row(id = id, email = "current@example.com", username = "current", sourceCreatedAt = newer, sourceUpdatedAt = newer)))
        assertEquals(1, firstResult.inserted)

        val staleResult = dao.upsertBatch(
            listOf(row(id = id, email = "stale@example.com", username = "stale", sourceCreatedAt = newer, sourceUpdatedAt = older))
        )
        assertEquals(1, staleResult.unchanged)
        assertEquals(0, staleResult.updated)

        val stored = dao.findByNormalizedEmail("current@example.com")
        assertNotNull(stored)
        assertEquals("current", stored!!.username)
        assertNull(dao.findByNormalizedEmail("stale@example.com"))
    }

    // --- Test 5: findByNormalizedEmail gaseste indiferent de majuscule/spatii in argument ---

    @Test
    fun `findByNormalizedEmail finds regardless of case or whitespace in the argument`() = runTest {
        dao.upsertBatch(listOf(row(email = "Foo@Bar.com", username = "foo")))

        val found = dao.findByNormalizedEmail("  foo@BAR.com  ")

        assertNotNull(found)
        assertEquals("foo", found!!.username)
        assertEquals("foo@bar.com", found.emailNormalized)
    }

    // --- Test 6: un rand in conflict nu abordeaza lotul ---

    @Test
    fun `a row in conflict does not abort the rest of the batch`() = runTest {
        dao.upsertBatch(listOf(row(email = "existing@example.com")))

        val batch = listOf(
            row(email = "fresh1@example.com"),
            row(email = " Existing@Example.com "), // colides with the pre-existing row's email_normalized, different id
            row(email = "fresh2@example.com"),
        )

        val result = dao.upsertBatch(batch)

        assertEquals(2, result.inserted)
        assertEquals(1, result.conflicted)
        assertNotNull(dao.findByNormalizedEmail("fresh1@example.com"))
        assertNotNull(dao.findByNormalizedEmail("fresh2@example.com"))
    }
}
