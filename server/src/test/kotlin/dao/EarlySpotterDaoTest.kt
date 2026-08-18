package dao

import com.revio.server.features.user.EarlySpotterBonusLedgerTable
import com.revio.server.features.user.EarlySpotterBonusReason
import com.revio.server.features.user.UserDao
import com.revio.server.features.user.UserTable
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.selectAll
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertReturning
import org.jetbrains.exposed.sql.statements.StatementType
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.Connection
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import testutils.TestDatabaseFactory
import testutils.UserTestSeed

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EarlySpotterDaoTest {

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

    // --- Test 1: userii seed-uiti direct (ca userii existenti) raman non-early-spotters ---

    @Test
    fun `existing users seeded directly remain non-early-spotter`() = runTest {
        repeat(5) { i ->
            val cred = UserTestSeed.seedAuthCredential("existing$i@example.com")
            UserTestSeed.seedUser(cred.authCredentialId, username = "existing$i")
        }

        val allUsers: List<Pair<Boolean, Int?>> = transaction {
            UserTable.selectAll().map { row ->
                row[UserTable.isEarlySpotter] to row[UserTable.earlySpotterNumber]
            }
        }

        assertEquals(5, allUsers.size)
        allUsers.forEach { pair ->
            assertFalse(pair.first, "User seeded directly should not be early spotter")
            assertNull(pair.second, "User seeded directly should have null early_spotter_number")
        }
    }

    // --- Test 2: primul createUser dupa migration primeste numarul 1 ---

    @Test
    fun `first createUser after migration receives earlySpotterNumber 1`() = runTest {
        val cred = UserTestSeed.seedAuthCredential("first@example.com")
        val userId = dao.createUser(UserTestSeed.buildUser(cred.authCredentialId, username = "first")).userId

        val user = dao.getUserById(userId)

        assertNotNull(user)
        assertTrue(user!!.isEarlySpotter)
        assertEquals(1, user.earlySpotterNumber)
    }

    // --- Test 3: al doilea createUser primeste numarul 2 ---

    @Test
    fun `second createUser receives earlySpotterNumber 2`() = runTest {
        val cred1 = UserTestSeed.seedAuthCredential("first@example.com")
        dao.createUser(UserTestSeed.buildUser(cred1.authCredentialId, username = "first"))

        val cred2 = UserTestSeed.seedAuthCredential("second@example.com")
        val userId2 = dao.createUser(UserTestSeed.buildUser(cred2.authCredentialId, username = "second")).userId

        val user2 = dao.getUserById(userId2)

        assertNotNull(user2)
        assertTrue(user2!!.isEarlySpotter)
        assertEquals(2, user2.earlySpotterNumber)
    }

    // --- Test 4: primii 1000 useri noi primesc numere 1..1000 distincte ---

    @Test
    fun `first 1000 createUser calls receive numbers 1 to 1000 all distinct`() = runTest {
        val numbers = (1..1000).map { i ->
            val cred = UserTestSeed.seedAuthCredential("user$i@example.com")
            val userId = dao.createUser(UserTestSeed.buildUser(cred.authCredentialId, username = "user$i")).userId
            dao.getUserById(userId)!!.earlySpotterNumber
        }

        assertEquals(1000, numbers.size)
        assertEquals((1..1000).toSet(), numbers.toSet())
    }

    // --- Test 5: al 1001-lea createUser nu primeste badge ---

    @Test
    fun `user 1001 does not receive early spotter badge`() = runTest {
        repeat(1000) { i ->
            val cred = UserTestSeed.seedAuthCredential("slot$i@example.com")
            dao.createUser(UserTestSeed.buildUser(cred.authCredentialId, username = "slot$i"))
        }

        val lateCred = UserTestSeed.seedAuthCredential("late@example.com")
        val lateUserId = dao.createUser(UserTestSeed.buildUser(lateCred.authCredentialId, username = "late")).userId
        val lateUser = dao.getUserById(lateUserId)

        assertNotNull(lateUser)
        assertFalse(lateUser!!.isEarlySpotter)
        assertNull(lateUser.earlySpotterNumber)
    }

    // --- Test 6: N createUser concurente → numere unice, fara serialization error ---

    @Test
    fun `concurrent createUser calls produce unique early spotter numbers without errors`() {
        val n = 20
        val credentials = (1..n).map { i ->
            UserTestSeed.seedAuthCredential("concurrent$i@example.com")
        }

        val numbers = runBlocking(Dispatchers.IO) {
            credentials.mapIndexed { i, cred ->
                async {
                    dao.createUser(UserTestSeed.buildUser(cred.authCredentialId, username = "concurrent$i"))
                        .let { result -> dao.getUserById(result.userId)!!.earlySpotterNumber }
                }
            }.awaitAll()
        }

        val nonNullNumbers = numbers.filterNotNull()
        assertEquals(n, nonNullNumbers.size, "All $n concurrent users should be early spotters (counter starts at 0)")
        assertEquals(nonNullNumbers.toSet().size, nonNullNumbers.size, "All assigned numbers must be unique")
        assertEquals((1..n).toSet(), nonNullNumbers.toSet())
    }

    // --- Test 7: counter nu se initializeaza din numarul de useri existenti ---

    @Test
    fun `counter starts at 0 regardless of how many existing users are in DB`() = runTest {
        val existingCount = 50
        repeat(existingCount) { i ->
            val cred = UserTestSeed.seedAuthCredential("pre$i@example.com")
            UserTestSeed.seedUser(cred.authCredentialId, username = "pre$i")
        }

        val newCred = UserTestSeed.seedAuthCredential("newcomer@example.com")
        val newUserId = dao.createUser(UserTestSeed.buildUser(newCred.authCredentialId, username = "newcomer")).userId
        val newUser = dao.getUserById(newUserId)

        assertNotNull(newUser)
        assertTrue(newUser!!.isEarlySpotter)
        assertEquals(
            1,
            newUser.earlySpotterNumber,
            "First new user after feature launch should get number 1, not ${existingCount + 1}"
        )
    }

    // --- Test 8: insert invalid (boolean/number inconsistenti) esueaza pe constraint DB ---

    @Test
    fun `inserting true with null number violates consistency constraint`() {
        val cred = UserTestSeed.seedAuthCredential("bad@example.com")
        assertThrows<ExposedSQLException> {
            transaction {
                UserTable.insert {
                    it[UserTable.authCredentialId] = cred.authCredentialId
                    it[UserTable.fullName] = "Bad"
                    it[UserTable.username] = "baduser"
                    it[UserTable.country] = "RO"
                    it[UserTable.birthDate] = java.time.LocalDate.of(1995, 1, 1)
                    it[UserTable.isEarlySpotter] = true
                    it[UserTable.earlySpotterNumber] = null  // inconsistent cu true
                }
            }
        }
    }

    @Test
    fun `inserting false with a number violates consistency constraint`() {
        val cred = UserTestSeed.seedAuthCredential("bad2@example.com")
        assertThrows<ExposedSQLException> {
            transaction {
                UserTable.insert {
                    it[UserTable.authCredentialId] = cred.authCredentialId
                    it[UserTable.fullName] = "Bad2"
                    it[UserTable.username] = "baduser2"
                    it[UserTable.country] = "RO"
                    it[UserTable.birthDate] = java.time.LocalDate.of(1995, 1, 1)
                    it[UserTable.isEarlySpotter] = false
                    it[UserTable.earlySpotterNumber] = 42  // inconsistent cu false
                }
            }
        }
    }

    @Test
    fun `inserting number outside 1-1000 range violates range constraint`() {
        val cred = UserTestSeed.seedAuthCredential("bad3@example.com")
        assertThrows<ExposedSQLException> {
            transaction {
                UserTable.insert {
                    it[UserTable.authCredentialId] = cred.authCredentialId
                    it[UserTable.fullName] = "Bad3"
                    it[UserTable.username] = "baduser3"
                    it[UserTable.country] = "RO"
                    it[UserTable.birthDate] = java.time.LocalDate.of(1995, 1, 1)
                    it[UserTable.isEarlySpotter] = true
                    it[UserTable.earlySpotterNumber] = 1001  // depaseste limita de 1000
                }
            }
        }
    }

    // --- Test 9: slot disponibil → ledger scris, spot_score == 300 ---

    @Test
    fun `createUser with an available slot writes a ledger entry and grants the 300-point bonus`() = runTest {
        val cred = UserTestSeed.seedAuthCredential("bonus@example.com")
        val userId = dao.createUser(UserTestSeed.buildUser(cred.authCredentialId, username = "bonus")).userId

        val user = dao.getUserById(userId)
        assertNotNull(user)
        assertTrue(user!!.isEarlySpotter)
        assertEquals(300, user.spotScore)

        val ledgerRow = transaction {
            EarlySpotterBonusLedgerTable
                .selectAll()
                .where { EarlySpotterBonusLedgerTable.userId eq userId }
                .single()
        }
        assertEquals(user.earlySpotterNumber, ledgerRow[EarlySpotterBonusLedgerTable.earlySpotterNumber])
        assertEquals(300, ledgerRow[EarlySpotterBonusLedgerTable.nominalDelta])
        assertEquals(300, ledgerRow[EarlySpotterBonusLedgerTable.appliedDelta])
        assertEquals(EarlySpotterBonusReason.EARLY_SPOTTER_GRANTED, ledgerRow[EarlySpotterBonusLedgerTable.reason])
        assertEquals("early_spotter_bonus:$userId", ledgerRow[EarlySpotterBonusLedgerTable.idempotencyKey])
    }

    // --- Test 10: dupa epuizarea celor 1000 → fara ledger, spot_score == 0, earlySpotterNumber == null ---

    @Test
    fun `createUser after the 1000 slots are exhausted writes no ledger entry and grants no bonus`() = runTest {
        repeat(1000) { i ->
            val cred = UserTestSeed.seedAuthCredential("slotb$i@example.com")
            dao.createUser(UserTestSeed.buildUser(cred.authCredentialId, username = "slotb$i"))
        }

        val lateCred = UserTestSeed.seedAuthCredential("lateb@example.com")
        val lateUserId = dao.createUser(UserTestSeed.buildUser(lateCred.authCredentialId, username = "lateb")).userId
        val lateUser = dao.getUserById(lateUserId)

        assertNotNull(lateUser)
        assertFalse(lateUser!!.isEarlySpotter)
        assertNull(lateUser.earlySpotterNumber)
        assertEquals(0, lateUser.spotScore)

        val ledgerCount = transaction {
            EarlySpotterBonusLedgerTable
                .selectAll()
                .where { EarlySpotterBonusLedgerTable.userId eq lateUserId }
                .count()
        }
        assertEquals(0, ledgerCount.toInt())
    }

    // --- Test 11: N creari concurente in jurul limitei de 1000 → exact 1000 numere, exact 1000 intrari de ledger, fiecare 300 puncte ---

    @Test
    fun `concurrent createUser calls around the 1000 limit produce exactly 1000 ledger entries each with the bonus applied`() =
        runBlocking(Dispatchers.IO) {
            val preFill = 995
            repeat(preFill) { i ->
                val cred = UserTestSeed.seedAuthCredential("pref$i@example.com")
                dao.createUser(UserTestSeed.buildUser(cred.authCredentialId, username = "pref$i"))
            }

            val n = 10
            val credentials = (1..n).map { i -> UserTestSeed.seedAuthCredential("boundary$i@example.com") }

            val userIds = credentials.mapIndexed { i, cred ->
                async { dao.createUser(UserTestSeed.buildUser(cred.authCredentialId, username = "boundary$i")).userId }
            }.awaitAll()

            val users = userIds.map { dao.getUserById(it)!! }
            val earlySpotters = users.filter { it.isEarlySpotter }
            val nonSpotters = users.filter { !it.isEarlySpotter }

            assertEquals(5, earlySpotters.size, "Only 5 of the 1000 slots remain after prefilling 995")
            assertEquals(5, nonSpotters.size)
            earlySpotters.forEach { assertEquals(300, it.spotScore) }
            nonSpotters.forEach { assertEquals(0, it.spotScore) }

            val ledgerCount = transaction { EarlySpotterBonusLedgerTable.selectAll().count() }
            assertEquals(1000, ledgerCount.toInt())

            val earlySpotterNumbers = transaction {
                EarlySpotterBonusLedgerTable.selectAll().map { it[EarlySpotterBonusLedgerTable.earlySpotterNumber] }.toSet()
            }
            assertEquals((1..1000).toSet(), earlySpotterNumbers)
        }

    // --- Test 12: exceptie dupa insertul in ledger, inainte de commit → nimic nu persista ---

    /**
     * UserDao.createUser has no injectable dependency to mock a mid-transaction failure through,
     * so this reproduces its exact statement sequence (advisory lock, counter increment, user
     * insert, ledger insert) inside a test-owned transaction and forces a failure right after the
     * ledger insert. Proves the whole sequence lives in one atomic transaction — the same
     * guarantee createUser relies on for its rollback-on-failure behavior.
     */
    @Test
    fun `an exception after the ledger insert rolls back the user, the ledger row, and the counter increment`() {
        val cred = UserTestSeed.seedAuthCredential("rollback@example.com")

        assertThrows<IllegalStateException> {
            transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
                exec("SELECT pg_advisory_xact_lock(8123001)")

                val assignedNumber = exec(
                    """
                    UPDATE early_spotter_counter
                       SET last_assigned = last_assigned + 1
                     WHERE last_assigned < 1000
                    RETURNING last_assigned
                    """.trimIndent(),
                    explicitStatementType = StatementType.SELECT
                ) { rs -> if (rs.next()) rs.getInt("last_assigned") else null }

                val userId = UserTable.insertReturning(listOf(UserTable.id)) {
                    it[authCredentialId] = cred.authCredentialId
                    it[fullName] = "Rollback"
                    it[username] = "rollbackuser"
                    it[country] = "RO"
                    it[birthDate] = java.time.LocalDate.of(1995, 1, 1)
                    it[isEarlySpotter] = assignedNumber != null
                    it[earlySpotterNumber] = assignedNumber
                }.single()[UserTable.id].value

                EarlySpotterBonusLedgerTable.insert {
                    it[EarlySpotterBonusLedgerTable.userId] = userId
                    it[EarlySpotterBonusLedgerTable.earlySpotterNumber] = assignedNumber!!
                    it[EarlySpotterBonusLedgerTable.nominalDelta] = 300
                    it[EarlySpotterBonusLedgerTable.appliedDelta] = 300
                    it[EarlySpotterBonusLedgerTable.reason] = EarlySpotterBonusReason.EARLY_SPOTTER_GRANTED
                    it[EarlySpotterBonusLedgerTable.idempotencyKey] = "early_spotter_bonus:$userId"
                }

                error("forced failure after ledger insert, before commit")
            }
        }

        val userCount = transaction { UserTable.selectAll().count() }
        val ledgerCount = transaction { EarlySpotterBonusLedgerTable.selectAll().count() }
        val counterValue = transaction {
            exec("SELECT last_assigned FROM early_spotter_counter WHERE id = 1") { rs ->
                rs.next()
                rs.getInt("last_assigned")
            }
        }

        assertEquals(0, userCount.toInt())
        assertEquals(0, ledgerCount.toInt())
        assertEquals(0, counterValue)
    }
}
