package testutils

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.testcontainers.containers.PostgreSQLContainer
import javax.sql.DataSource

/**
 * Pornește un PostgreSQL real în Docker, aplică Flyway (baseline-ul curent și migrațiile ulterioare),
 * conectează Exposed la el și oferă un DataSource pentru alte nevoi (ex. Ktor testing).
 *
 * Un singur container per JVM run — pornim o dată, curățăm tabelele între teste.
 */
object TestDatabaseFactory {

    private var container: PostgreSQLContainer<*>? = null
    private var dataSource: HikariDataSource? = null
    private var started = false

    fun start() {
        if (started) return

        val pg = PostgreSQLContainer("postgres:16-alpine").apply {
            withDatabaseName("revio_test")
            withUsername("test")
            withPassword("test")
            withEnv("TZ", "UTC")
            withCommand("postgres", "-c", "timezone=UTC")
            withReuse(false)
        }
        pg.start()

        container = pg

        val hikariConfig = HikariConfig().apply {
            jdbcUrl = pg.jdbcUrl
            driverClassName = "org.postgresql.Driver"
            username = pg.username
            password = pg.password
            maximumPoolSize = 5
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            validate()
        }
        val ds = HikariDataSource(hikariConfig)
        dataSource = ds

        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migrations")
            .load()
            .migrate()

        Database.connect(ds)

        started = true
    }

    fun stop() {
        dataSource?.close()
        dataSource = null
        container?.stop()
        container = null
        started = false
    }

    /**
     * Șterge datele din toate tabelele relevante, păstrând schema.
     * Ordinea respectă FK-urile: copiii înainte, părinții după.
     */
    fun cleanDatabase() {
        val ds = dataSource ?: error("TestDatabaseFactory not started")
        ds.connection.use { conn ->
            conn.autoCommit = false
            conn.createStatement().use { st ->
                // TRUNCATE cu CASCADE e cea mai simplă cale de a curăța totul.
                st.execute(
                    """
                    TRUNCATE TABLE
                        activity_events,
                        leaderboard_rank_snapshots,
                        friend_requests,
                        friends,
                        likes,
                        reports,
                        moderation_violations,
                        user_notifications,
                        admin_audit_log,
                        orphaned_storage_objects,
                        challenge_reward_ledger,
                        challenge_participants,
                        challenge_contributions,
                        comments,
                        posts,
                        users_cars,
                        users,
                        auth_sessions,
                        auth_credentials,
                        challenges,
                        car_families,
                        car_models,
                        account_deletion_feedback,
                        first_post_feedback,
                        feedback_prompt_state,
                        user_feedback
                    RESTART IDENTITY CASCADE
                    """.trimIndent()
                )
                st.execute(
                    """
                    INSERT INTO early_spotter_counter (id, last_assigned)
                    VALUES (1, 0)
                    ON CONFLICT (id) DO UPDATE SET last_assigned = 0
                    """.trimIndent()
                )
            }
            conn.commit()
        }
    }

    fun dataSource(): DataSource = dataSource ?: error("TestDatabaseFactory not started")

    fun jdbcUrl(): String = container?.jdbcUrl ?: error("TestDatabaseFactory not started")
    fun username(): String = container?.username ?: error("TestDatabaseFactory not started")
    fun password(): String = container?.password ?: error("TestDatabaseFactory not started")
}
