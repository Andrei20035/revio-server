package migration

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.util.UUID

/**
 * Verifică migrația V29 (plan §9-E2): sweep-ul singleton al lui V23/Part 2, re-rulat pentru orice
 * car_models rămas fără family_id după V23 — de ex. un model inserat ulterior fără family_id
 * explicit. Izolat de suita partajată din [testutils.TestDatabaseFactory], la fel ca
 * MigrationCarFamiliesBackfillTest: migrează până la V28 (catalogul complet există și V23 deja
 * a rulat, deci family_id IS NULL ar trebui să fie deja 0), inserează direct un rând "orfan",
 * apoi aplică V29 și verifică rezultatul.
 */
class MigrationV29OrphanCarFamiliesBackfillTest {

    private lateinit var container: PostgreSQLContainer<*>
    private lateinit var dataSource: HikariDataSource

    private fun startAtV28() {
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
            .target("28")
            .load()
            .migrate()

        Database.connect(dataSource)
    }

    private fun applyV29() {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migrations")
            .target("29")
            .load()
            .migrate()
    }

    @AfterEach
    fun tearDown() {
        if (::dataSource.isInitialized) dataSource.close()
        if (::container.isInitialized) container.stop()
    }

    // ---------- helpers ----------

    private fun esc(value: String) = value.replace("'", "''")

    private fun countModelsWithNullFamily(): Int = transaction {
        exec("SELECT COUNT(*) AS c FROM car_models WHERE family_id IS NULL") { rs -> rs.next(); rs.getInt("c") } ?: 0
    }

    private fun familyIdOf(brand: String, model: String): UUID? = transaction {
        exec("SELECT family_id FROM car_models WHERE brand = '${esc(brand)}' AND model = '${esc(model)}'") { rs ->
            if (rs.next()) rs.getString("family_id")?.let(UUID::fromString) else null
        }
    }

    private fun familyNameOf(brand: String, model: String): String? = transaction {
        exec(
            """
            SELECT cf.name AS name
              FROM car_models cm
              JOIN car_families cf ON cf.id = cm.family_id
             WHERE cm.brand = '${esc(brand)}' AND cm.model = '${esc(model)}'
            """.trimIndent()
        ) { rs -> if (rs.next()) rs.getString("name") else null }
    }

    /** Simulates a model inserted after V23 ran, without an explicit family_id. */
    private fun insertOrphanModel(brand: String, model: String) {
        transaction {
            exec(
                """
                INSERT INTO car_models (id, brand, model, family_id)
                VALUES ('${UUID.randomUUID()}', '${esc(brand)}', '${esc(model)}', NULL)
                """.trimIndent()
            )
        }
    }

    // ---------- V29 backfill, run through Flyway ----------

    @Test
    fun `every car_models row has a family_id after V29, including a model added after V23`() {
        startAtV28()
        insertOrphanModel("TestBrand", "Orphan Model One")

        applyV29()

        assertEquals(0, countModelsWithNullFamily())
    }

    @Test
    fun `an orphan model gets its own singleton family named after itself`() {
        startAtV28()
        insertOrphanModel("TestBrand", "Orphan Model Two")

        applyV29()

        val familyId = familyIdOf("TestBrand", "Orphan Model Two")
        assertNotNull(familyId, "'Orphan Model Two' must have been assigned a family by the singleton sweep")
        assertEquals("Orphan Model Two", familyNameOf("TestBrand", "Orphan Model Two"))
    }

    @Test
    fun `two orphan models of the same brand and name-derived family do not collide`() {
        startAtV28()
        insertOrphanModel("TestBrand", "Orphan Model Three")
        insertOrphanModel("OtherBrand", "Orphan Model Three")

        applyV29()

        val first = familyIdOf("TestBrand", "Orphan Model Three")
        val second = familyIdOf("OtherBrand", "Orphan Model Three")
        assertNotNull(first)
        assertNotNull(second)
        assertEquals(0, countModelsWithNullFamily())
    }

    @Test
    fun `V29 is a no-op when V23 already left no orphans`() {
        startAtV28()

        applyV29()

        assertEquals(0, countModelsWithNullFamily())
    }
}
