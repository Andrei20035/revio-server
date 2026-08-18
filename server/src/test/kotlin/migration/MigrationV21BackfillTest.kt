package migration

import com.revio.server.features.auth.AuthTable
import com.revio.server.features.post.PostSource
import com.revio.server.features.post.PostTable
import com.revio.server.features.user.EarlySpotterBonusLedgerTable
import com.revio.server.features.user.User
import com.revio.server.features.user.UserDao
import com.revio.server.features.user.UserTable
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.test.runTest
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Verifică migrația V21 (backfill puncte + remapare early spotter) izolat de suita
 * partajată din [testutils.TestDatabaseFactory], care golește tabelele imediat după migrare
 * și ar face V21 imposibil de observat. Fiecare test pornește propriul container, migrează
 * doar până la V20, inserează un fixture, apoi aplică V21 și verifică rezultatul.
 */
class MigrationV21BackfillTest {

    private lateinit var container: PostgreSQLContainer<*>
    private lateinit var dataSource: HikariDataSource

    private fun startAtV20() {
        container = PostgreSQLContainer("postgres:16-alpine").apply {
            withDatabaseName("revio_migration_test")
            withUsername("test")
            withPassword("test")
            withReuse(false)
        }
        container.start()

        val hikariConfig = HikariConfig().apply {
            jdbcUrl = container.jdbcUrl
            driverClassName = "org.postgresql.Driver"
            username = container.username
            password = container.password
            maximumPoolSize = 5
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            validate()
        }
        dataSource = HikariDataSource(hikariConfig)

        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migrations")
            .target("20")
            .load()
            .migrate()

        Database.connect(dataSource)
    }

    private fun applyV21() {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migrations")
            .load()
            .migrate()
    }

    @AfterEach
    fun tearDown() {
        if (::dataSource.isInitialized) dataSource.close()
        if (::container.isInitialized) container.stop()
    }

    // ---------- helpers ----------

    private fun seedUser(email: String, username: String, createdAt: Instant): UUID = transaction {
        val credId = AuthTable.insert {
            it[AuthTable.email] = email
            it[AuthTable.provider] = "REGULAR"
            it[AuthTable.password] = "hash"
        }[AuthTable.id].value

        val userId = UserTable.insert {
            it[UserTable.authCredentialId] = credId
            it[UserTable.fullName] = "Test User"
            it[UserTable.username] = username
            it[UserTable.country] = "RO"
            it[UserTable.birthDate] = LocalDate.of(1995, 1, 1)
        }[UserTable.id].value

        UserTable.update({ UserTable.id eq userId }) {
            it[UserTable.createdAt] = createdAt
        }

        userId
    }

    private fun seedPost(userId: UUID, source: PostSource, points: Int): UUID = transaction {
        PostTable.insert {
            it[PostTable.userId] = userId
            it[PostTable.imageKey] = "posts/test.jpg"
            it[PostTable.customBrand] = "bmw"
            it[PostTable.customModel] = "m3"
            it[PostTable.postSource] = source.name
            it[PostTable.points] = points
        }[PostTable.id].value
    }

    private fun lastAssignedCounter(): Int = transaction {
        exec("SELECT last_assigned FROM early_spotter_counter WHERE id = 1") { rs ->
            rs.next()
            rs.getInt("last_assigned")
        } ?: error("early_spotter_counter row missing")
    }

    // ---------- Test principal: backfill puncte + remapare early spotter ----------

    @Test
    fun `V21 sets 10 points per post, recalculates spot_score, and assigns deterministic early spotter numbers`() {
        startAtV20()

        val now = Instant.now()
        // created_at deliberat egale pentru bob si carol, ca sa testam tie-break-ul pe id.
        val aliceId = seedUser("alice@example.com", "alice", now.minus(3, ChronoUnit.DAYS))
        val bobId = seedUser("bob@example.com", "bob", now.minus(2, ChronoUnit.DAYS))
        val carolId = seedUser("carol@example.com", "carol", now.minus(2, ChronoUnit.DAYS))

        // Alice: doua postari, una CAMERA cu engagement istoric (15 puncte), una GALLERY (0 puncte).
        seedPost(aliceId, PostSource.CAMERA, points = 15)
        seedPost(aliceId, PostSource.GALLERY, points = 0)
        // Bob: o postare GALLERY.
        seedPost(bobId, PostSource.GALLERY, points = 0)
        // Carol: fara postari.

        applyV21()

        // --- puncte ---
        val postPoints = transaction { PostTable.selectAll().map { it[PostTable.points] } }
        assertTrue(postPoints.all { it == 10 }, "Every post must have exactly 10 points after backfill")
        assertEquals(3, postPoints.size)

        val scores = transaction {
            UserTable.selectAll().associate { it[UserTable.id].value to it[UserTable.spotScore] }
        }
        assertEquals(20, scores.getValue(aliceId)) // 2 posts * 10
        assertEquals(10, scores.getValue(bobId))   // 1 post * 10
        assertEquals(0, scores.getValue(carolId))  // no posts

        // --- early spotter: ordine deterministă created_at ASC, id ASC ---
        val spotters = transaction {
            UserTable.selectAll()
                .map { it[UserTable.id].value to Pair(it[UserTable.isEarlySpotter], it[UserTable.earlySpotterNumber]) }
                .toMap()
        }
        assertEquals(1, spotters.getValue(aliceId).second)
        assertTrue(spotters.getValue(aliceId).first!!)

        val bobCarolNumbers = setOf(spotters.getValue(bobId).second, spotters.getValue(carolId).second)
        assertEquals(setOf(2, 3), bobCarolNumbers)
        // Tie-break determinist pe id (ordonare Postgres, nu Java UUID.compareTo): cine are id
        // mai mic dintre bob/carol primeste numarul 2.
        val expectedSecond = transaction {
            exec("SELECT id FROM users WHERE id IN ('$bobId', '$carolId') ORDER BY id ASC LIMIT 1") { rs ->
                rs.next()
                UUID.fromString(rs.getString("id"))
            }
        }
        assertEquals(2, spotters.getValue(expectedSecond!!).second)

        assertTrue(spotters.values.all { it.first == true })
        assertEquals(setOf(1, 2, 3), spotters.values.map { it.second }.toSet())

        // --- counter resetat la MAX(early_spotter_number) ---
        assertEquals(3, lastAssignedCounter())
    }

    // ---------- Garda >1000 useri ----------

    @Test
    fun `V21 aborts with an exception when more than 1000 users exist`() {
        startAtV20()

        transaction {
            exec(
                """
                INSERT INTO auth_credentials (id, email, password, provider)
                SELECT gen_random_uuid(), 'bulk' || g || '@example.com', 'hash', 'REGULAR'
                FROM generate_series(1, 1001) g
                """.trimIndent()
            )
            exec(
                """
                INSERT INTO users (id, auth_credential_id, full_name, username, country, birth_date)
                SELECT gen_random_uuid(), ac.id, 'Bulk User', 'bulkuser' || row_number() OVER (), 'RO', DATE '1995-01-01'
                FROM auth_credentials ac
                WHERE ac.email LIKE 'bulk%@example.com'
                """.trimIndent()
            )
        }

        assertThrows(FlywayException::class.java) { applyV21() }
    }

    // ---------- Continuitate: createUser dupa V21 continua de la MAX + 1 ----------

    @Test
    fun `UserDao createUser continues numbering from MAX(early_spotter_number) after V21`() = runTest {
        startAtV20()

        val now = Instant.now()
        seedUser("alice@example.com", "alice", now.minus(2, ChronoUnit.DAYS))
        seedUser("bob@example.com", "bob", now.minus(1, ChronoUnit.DAYS))

        applyV21()
        assertEquals(2, lastAssignedCounter())

        // V21 backfilled alice and bob as early spotters retroactively — they must NOT receive
        // the bonus, since it only applies to profiles created through UserDao.createUser.
        val ledgerRowsAfterBackfill = transaction { EarlySpotterBonusLedgerTable.selectAll().count() }
        assertEquals(0, ledgerRowsAfterBackfill)

        val dao = UserDao()
        val cred = transaction {
            AuthTable.insert {
                it[AuthTable.email] = "newcomer@example.com"
                it[AuthTable.provider] = "REGULAR"
                it[AuthTable.password] = "hash"
            }[AuthTable.id].value
        }
        val newUserId = dao.createUser(
            User(
                authCredentialId = cred,
                profilePicturePath = null,
                fullName = "Newcomer",
                phoneNumber = null,
                birthDate = LocalDate.of(1995, 1, 1),
                username = "newcomer",
                country = "RO",
            )
        ).userId

        val newUser = dao.getUserById(newUserId)
        assertEquals(3, newUser?.earlySpotterNumber)
        assertEquals(3, lastAssignedCounter())

        // Unlike alice/bob, newcomer was created via UserDao.createUser after V21/V30 — it must
        // receive the ledger entry and the 300-point bonus.
        assertEquals(300, newUser?.spotScore)
        val ledgerRowsAfterNewcomer = transaction {
            EarlySpotterBonusLedgerTable.selectAll()
                .where { EarlySpotterBonusLedgerTable.userId eq newUserId }
                .count()
        }
        assertEquals(1, ledgerRowsAfterNewcomer)
    }
}
