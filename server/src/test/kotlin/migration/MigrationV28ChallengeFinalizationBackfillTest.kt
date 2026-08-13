package migration

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Verifică backfill-ul grandfather din V28 (`finalized_at = ends_at` pentru challenge-urile
 * SCHEDULED deja încheiate), izolat de suita partajată din [testutils.TestDatabaseFactory] —
 * la fel ca MigrationCarFamiliesBackfillTest, pentru același motiv: cleanDatabase() ar goli
 * imediat orice ar produce V28. Migrează doar până la V27 (finalized_at nu există încă),
 * inserează challenge-uri direct prin SQL brut în cele trei stări relevante, apoi aplică V28.
 */
class MigrationV28ChallengeFinalizationBackfillTest {

    private lateinit var container: PostgreSQLContainer<*>
    private lateinit var dataSource: HikariDataSource

    private fun startAtV27() {
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
            .target("27")
            .load()
            .migrate()

        Database.connect(dataSource)
    }

    private fun applyV28() {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migrations")
            .target("28")
            .load()
            .migrate()
    }

    @AfterEach
    fun tearDown() {
        if (::dataSource.isInitialized) dataSource.close()
        if (::container.isInitialized) container.stop()
    }

    // ---------- helpers ----------

    private fun insertCarFamily(): UUID {
        val id = UUID.randomUUID()
        transaction {
            exec(
                """
                INSERT INTO car_families (id, brand, name)
                VALUES ('$id', 'Volkswagen', 'Golf-${id}')
                """.trimIndent()
            )
        }
        return id
    }

    /** Inserts a minimal, constraint-satisfying `challenges` row and returns its id. */
    private fun insertChallenge(
        familyId: UUID,
        status: String,
        startsAt: Instant,
        endsAt: Instant,
    ): UUID {
        val id = UUID.randomUUID()
        transaction {
            exec(
                """
                INSERT INTO challenges
                    (id, title, target_family_id, required_posts, reward_points,
                     starts_at, ends_at, admin_timezone, status)
                VALUES
                    ('$id', 'Spot the Golf', '$familyId', 5, 300,
                     '${startsAt}', '${endsAt}', 'Europe/Bucharest', '$status')
                """.trimIndent()
            )
        }
        return id
    }

    private fun finalizedAtOf(challengeId: UUID): Instant? = transaction {
        exec("SELECT finalized_at FROM challenges WHERE id = '$challengeId'") { rs ->
            if (rs.next()) rs.getTimestamp("finalized_at")?.toInstant() else null
        }
    }

    // ---------- V28 grandfather backfill, run through Flyway ----------

    @Test
    fun `a SCHEDULED challenge that already ended is grandfathered with finalized_at equal to ends_at`() {
        startAtV27()
        val familyId = insertCarFamily()
        val now = Instant.now()
        val endsAt = now.minusSeconds(3600).truncatedTo(ChronoUnit.MICROS)
        val startsAt = endsAt.minusSeconds(3600)
        val endedId = insertChallenge(familyId, "SCHEDULED", startsAt, endsAt)

        applyV28()

        assertEquals(endsAt, finalizedAtOf(endedId))
    }

    @Test
    fun `a SCHEDULED challenge still active is left with finalized_at NULL`() {
        startAtV27()
        val familyId = insertCarFamily()
        val now = Instant.now()
        val activeId = insertChallenge(familyId, "SCHEDULED", now.minusSeconds(3600), now.plusSeconds(3600))

        applyV28()

        assertNull(finalizedAtOf(activeId))
    }

    @Test
    fun `a DRAFT challenge is left with finalized_at NULL regardless of its window`() {
        startAtV27()
        val familyId = insertCarFamily()
        val now = Instant.now()
        val draftId = insertChallenge(familyId, "DRAFT", now.minusSeconds(7200), now.minusSeconds(3600))

        applyV28()

        assertNull(finalizedAtOf(draftId))
    }
}
