package migration

import com.revio.server.features.comment.CommentTable
import com.revio.server.features.post.PostTable
import com.revio.server.features.user.UserTable
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.statements.StatementType
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.time.LocalDate
import java.util.UUID

/**
 * Verifică migrația V36 (extinderea user_notifications cu coloanele sociale) izolat de suita
 * partajată din [testutils.TestDatabaseFactory], care golește tabelele imediat după migrare —
 * exact ca [MigrationV21BackfillTest]. Fiecare test pornește propriul container, migrează doar
 * până la V35 (înaintea extinderii), inserează un fixture cu forma veche a tabelei, apoi aplică
 * V36 și verifică backfill-ul și comportamentul noilor constrângeri/FK-uri.
 */
class MigrationV36Test {

    private lateinit var container: PostgreSQLContainer<*>
    private lateinit var dataSource: HikariDataSource

    private fun startAtV35() {
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
            .target("35")
            .load()
            .migrate()

        Database.connect(dataSource)
    }

    private fun applyV36() {
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

    private fun seedUser(email: String, username: String): UUID = transaction {
        val credId = exec(
            "INSERT INTO auth_credentials (id, email, password, provider) " +
                "VALUES (gen_random_uuid(), '$email', 'hash', 'REGULAR') RETURNING id",
            explicitStatementType = StatementType.SELECT,
        ) { rs -> rs.next(); UUID.fromString(rs.getString("id")) } ?: error("insert failed")

        UserTable.insert {
            it[UserTable.authCredentialId] = credId
            it[UserTable.fullName] = "Test User"
            it[UserTable.username] = username
            it[UserTable.country] = "RO"
            it[UserTable.birthDate] = LocalDate.of(1995, 1, 1)
        }[UserTable.id].value
    }

    private fun seedPost(userId: UUID): UUID = transaction {
        PostTable.insert {
            it[PostTable.userId] = userId
            it[PostTable.imageKey] = "posts/test.jpg"
            it[PostTable.customBrand] = "bmw"
            it[PostTable.customModel] = "m3"
        }[PostTable.id].value
    }

    private fun seedComment(userId: UUID, postId: UUID): UUID = transaction {
        CommentTable.insert {
            it[CommentTable.userId] = userId
            it[CommentTable.postId] = postId
            it[CommentTable.commentText] = "nice spot"
        }[CommentTable.id].value
    }

    /** Inserts a row using exactly the pre-V36 column set — mirrors PostRemovalDAO/BanDAO today. */
    private fun seedLegacyModerationNotification(userId: UUID): UUID = transaction {
        exec(
            """
            INSERT INTO user_notifications (id, user_id, type, title, body, blocking)
            VALUES (gen_random_uuid(), '$userId', 'ACCOUNT_SUSPENDED', 'Your account was suspended', 'Body', TRUE)
            RETURNING id
            """.trimIndent(),
            explicitStatementType = StatementType.SELECT,
        ) { rs -> rs.next(); UUID.fromString(rs.getString("id")) } ?: error("insert failed")
    }

    private fun notificationColumn(id: UUID, column: String): String? = transaction {
        exec("SELECT $column FROM user_notifications WHERE id = '$id'") { rs ->
            rs.next()
            rs.getString(column)
        }
    }

    // ---------- Backfill pe rânduri existente ----------

    @Test
    fun `V36 backfills existing notification rows to category ACCOUNT with safe defaults`() {
        startAtV35()

        val userId = seedUser("alice@example.com", "alice")
        val notificationId = seedLegacyModerationNotification(userId)

        val createdAtBefore = notificationColumn(notificationId, "created_at")

        applyV36()

        assertEquals("ACCOUNT", notificationColumn(notificationId, "category"))
        assertEquals("NONE", notificationColumn(notificationId, "target_type"))
        assertEquals("NOT_SENT", notificationColumn(notificationId, "push_state"))
        assertEquals("1", notificationColumn(notificationId, "actor_count"))
        assertNull(notificationColumn(notificationId, "dedupe_key"))
        assertNull(notificationColumn(notificationId, "post_id"))
        // updated_at is aligned to created_at for pre-existing rows, not left at migration time.
        assertEquals(createdAtBefore, notificationColumn(notificationId, "updated_at"))
    }

    @Test
    fun `V36 does not disturb existing notification columns or row count`() {
        startAtV35()

        val userId = seedUser("alice@example.com", "alice")
        seedLegacyModerationNotification(userId)
        seedLegacyModerationNotification(userId)

        applyV36()

        val count = transaction {
            exec("SELECT COUNT(*) AS c FROM user_notifications") { rs -> rs.next(); rs.getInt("c") }
        }
        assertEquals(2, count)
    }

    // ---------- FK ON DELETE SET NULL (G18) ----------

    @Test
    fun `deleting a post sets post_id to NULL on the notification row instead of removing it`() {
        startAtV35()
        applyV36()

        val userId = seedUser("alice@example.com", "alice")
        val posterId = seedUser("bob@example.com", "bob")
        val postId = seedPost(posterId)

        val notificationId = transaction {
            exec(
                """
                INSERT INTO user_notifications
                    (id, user_id, type, title, body, blocking, category, target_type, post_id)
                VALUES
                    (gen_random_uuid(), '$userId', 'ACCOUNT_SUSPENDED', 't', 'b', FALSE, 'LIKES', 'POST', '$postId')
                RETURNING id
                """.trimIndent(),
                explicitStatementType = StatementType.SELECT,
            ) { rs -> rs.next(); UUID.fromString(rs.getString("id")) } ?: error("insert failed")
        }

        transaction { PostTable.deleteWhere { PostTable.id eq postId } }

        assertNull(notificationColumn(notificationId, "post_id"))
        // The row itself survives — only the dangling reference is cleared.
        assertEquals("LIKES", notificationColumn(notificationId, "category"))
    }

    @Test
    fun `deleting a comment sets comment_id to NULL on the notification row`() {
        startAtV35()
        applyV36()

        val userId = seedUser("alice@example.com", "alice")
        val commenterId = seedUser("bob@example.com", "bob")
        val postId = seedPost(userId)
        val commentId = seedComment(commenterId, postId)

        val notificationId = transaction {
            exec(
                """
                INSERT INTO user_notifications
                    (id, user_id, type, title, body, blocking, category, target_type, post_id, comment_id)
                VALUES
                    (gen_random_uuid(), '$userId', 'ACCOUNT_SUSPENDED', 't', 'b', FALSE, 'COMMENTS', 'COMMENT', '$postId', '$commentId')
                RETURNING id
                """.trimIndent(),
                explicitStatementType = StatementType.SELECT,
            ) { rs -> rs.next(); UUID.fromString(rs.getString("id")) } ?: error("insert failed")
        }

        transaction { CommentTable.deleteWhere { CommentTable.id eq commentId } }

        assertNull(notificationColumn(notificationId, "comment_id"))
        assertEquals(postId.toString(), notificationColumn(notificationId, "post_id"))
    }

    @Test
    fun `deleting the actor's account sets last_actor_user_id to NULL`() {
        startAtV35()
        applyV36()

        val userId = seedUser("alice@example.com", "alice")
        val actorId = seedUser("bob@example.com", "bob")

        val notificationId = transaction {
            exec(
                """
                INSERT INTO user_notifications
                    (id, user_id, type, title, body, blocking, category, last_actor_user_id, last_actor_username)
                VALUES
                    (gen_random_uuid(), '$userId', 'ACCOUNT_SUSPENDED', 't', 'b', FALSE, 'LIKES', '$actorId', 'bob')
                RETURNING id
                """.trimIndent(),
                explicitStatementType = StatementType.SELECT,
            ) { rs -> rs.next(); UUID.fromString(rs.getString("id")) } ?: error("insert failed")
        }

        transaction { UserTable.deleteWhere { UserTable.id eq actorId } }

        assertNull(notificationColumn(notificationId, "last_actor_user_id"))
        // The denormalized username survives the actor row's own deletion.
        assertEquals("bob", notificationColumn(notificationId, "last_actor_username"))
    }

    // ---------- UNIQUE (user_id, dedupe_key) ----------

    @Test
    fun `two rows for the same user with the same dedupe_key collide`() {
        startAtV35()
        applyV36()

        val userId = seedUser("alice@example.com", "alice")

        transaction {
            exec(
                """
                INSERT INTO user_notifications (id, user_id, type, title, body, blocking, category, dedupe_key)
                VALUES (gen_random_uuid(), '$userId', 'ACCOUNT_SUSPENDED', 't', 'b', FALSE, 'LIKES', 'like:post-1:window-1')
                """.trimIndent()
            )
        }

        assertThrows(ExposedSQLException::class.java) {
            transaction {
                exec(
                    """
                    INSERT INTO user_notifications (id, user_id, type, title, body, blocking, category, dedupe_key)
                    VALUES (gen_random_uuid(), '$userId', 'ACCOUNT_SUSPENDED', 't', 'b', FALSE, 'LIKES', 'like:post-1:window-1')
                    """.trimIndent()
                )
            }
        }
    }

    @Test
    fun `multiple NULL dedupe_key rows for the same user do not collide`() {
        startAtV35()
        applyV36()

        val userId = seedUser("alice@example.com", "alice")

        // Two moderation-style rows, neither setting dedupe_key — must both succeed, matching
        // how existing moderation code already inserts multiple notifications per user.
        transaction {
            exec(
                "INSERT INTO user_notifications (id, user_id, type, title, body, blocking) " +
                    "VALUES (gen_random_uuid(), '$userId', 'ACCOUNT_SUSPENDED', 't', 'b', FALSE)"
            )
            exec(
                "INSERT INTO user_notifications (id, user_id, type, title, body, blocking) " +
                    "VALUES (gen_random_uuid(), '$userId', 'ACCOUNT_UNSUSPENDED', 't', 'b', FALSE)"
            )
        }

        val count = transaction {
            exec("SELECT COUNT(*) AS c FROM user_notifications WHERE user_id = '$userId'") { rs ->
                rs.next(); rs.getInt("c")
            }
        }
        assertEquals(2, count)
    }

    // ---------- CHECK constraint ----------

    @Test
    fun `an invalid category is rejected`() {
        startAtV35()
        applyV36()

        val userId = seedUser("alice@example.com", "alice")

        assertThrows(ExposedSQLException::class.java) {
            transaction {
                exec(
                    """
                    INSERT INTO user_notifications (id, user_id, type, title, body, blocking, category)
                    VALUES (gen_random_uuid(), '$userId', 'ACCOUNT_SUSPENDED', 't', 'b', FALSE, 'NOT_A_CATEGORY')
                    """.trimIndent()
                )
            }
        }
    }
}
