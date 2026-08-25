package service

import com.revio.server.features.leaderboard.LeaderboardDAO
import com.revio.server.features.leaderboard.LeaderboardDeltaDAO
import com.revio.server.features.leaderboard.LeaderboardDeltaService
import com.revio.server.features.user.UserTable
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.statements.StatementType
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import testutils.TestDatabaseFactory
import testutils.UserTestSeed
import java.util.UUID

/**
 * Real Testcontainers Postgres coverage for LeaderboardDeltaService/-DAO (plan §18, step 6.1):
 * both the single-user path (getUserRank/getUserScoreAndStreak + the two extra queries) and the
 * batch single-pass window-function query, against real seeded users/scores.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderboardDeltaServiceTest {

    private val leaderboardDao = LeaderboardDAO()
    private val deltaDao = LeaderboardDeltaDAO()
    private val service = LeaderboardDeltaService(leaderboardDao, deltaDao)

    @BeforeAll
    fun setup() = TestDatabaseFactory.start()

    @AfterAll
    fun tearDown() = TestDatabaseFactory.stop()

    @BeforeEach
    fun clean() = TestDatabaseFactory.cleanDatabase()

    private fun seedUser(email: String, username: String, score: Int): UUID {
        val userId = UserTestSeed.seedUser(UserTestSeed.seedAuthCredential(email).authCredentialId, username = username)
        transaction { UserTable.update({ UserTable.id eq userId }) { it[UserTable.spotScore] = score } }
        return userId
    }

    // ---------- computeDelta (single-user) ----------

    @Test
    fun `computeDelta returns null for a user that does not exist`() = runTest {
        assertNull(service.computeDelta(UUID.randomUUID()))
    }

    @Test
    fun `computeDelta for the sole (rank 1) user has a null pointsToGuaranteeMoveUp`() = runTest {
        val userId = seedUser("solo@example.com", "solo", score = 100)

        val delta = service.computeDelta(userId)!!

        assertNull(delta.pointsToGuaranteeMoveUp)
        assertEquals(0, delta.placesGainedWithOnePoint)
    }

    @Test
    fun `computeDelta case 1 - next score 1 higher, not tied - delta is 2`() = runTest {
        seedUser("ahead1@example.com", "ahead1", score = 41)
        val me = seedUser("me1@example.com", "me1", score = 40)

        val delta = service.computeDelta(me)!!

        assertEquals(2, delta.pointsToGuaranteeMoveUp)
        assertEquals(0, delta.placesGainedWithOnePoint)
    }

    @Test
    fun `computeDelta case 2 - tied with a group ahead - delta is 1, placesGained counts the tie group`() = runTest {
        // 4 users tied at 40, all ranked ahead of "me" (seeded first -> smaller/earlier id isn't
        // guaranteed by insertion order, so we assert the count rather than depend on UUID order).
        val tiedIds = (1..4).map { i -> seedUser("tied$i@example.com", "tied$i", score = 40) }
        val me = seedUser("me2@example.com", "me2", score = 40)

        val delta = service.computeDelta(me)!!

        assertEquals(1, delta.pointsToGuaranteeMoveUp)
        // Some subset of the 4 tied users rank ahead of "me" by UUID tie-break; the rest rank
        // behind. placesGainedWithOnePoint counts exactly the ones ahead.
        val expectedAhead = transaction {
            tiedIds.count { id ->
                UserTable.select(UserTable.id).where { (UserTable.spotScore eq 40) and (UserTable.id less me) }
                    .map { it[UserTable.id].value }
                    .contains(id)
            }
        }
        assertEquals(expectedAhead, delta.placesGainedWithOnePoint)
    }

    @Test
    fun `computeDelta case 3 - next distinct score 5 higher - delta is 6`() = runTest {
        seedUser("ahead3@example.com", "ahead3", score = 45)
        val me = seedUser("me3@example.com", "me3", score = 40)

        val delta = service.computeDelta(me)!!

        assertEquals(6, delta.pointsToGuaranteeMoveUp)
    }

    // ---------- computeAllDeltas (batch, single-pass) ----------

    @Test
    fun `computeAllDeltas matches computeDelta for every seeded user`() = runTest {
        seedUser("batch-ahead@example.com", "batch_ahead", score = 45)
        val me = seedUser("batch-me@example.com", "batch_me", score = 40)
        val solo = seedUser("batch-solo@example.com", "batch_solo", score = 100)

        val all = service.computeAllDeltas()

        assertEquals(service.computeDelta(me), all[me])
        assertEquals(service.computeDelta(solo), all[solo])
        assertEquals(3, all.size)
    }

    @Test
    fun `computeAllDeltas is a single query (verified with EXPLAIN)`() = runTest {
        seedUser("explain-a@example.com", "explain_a", score = 10)
        seedUser("explain-b@example.com", "explain_b", score = 20)
        seedUser("explain-c@example.com", "explain_c", score = 20)

        val plan = transaction {
            exec(
                """
                EXPLAIN
                SELECT id, spot_score,
                       ROW_NUMBER() OVER (ORDER BY spot_score DESC, id ASC) AS rnk,
                       LAG(spot_score) OVER (ORDER BY spot_score DESC, id ASC) AS score_ahead,
                       COUNT(*) OVER (
                           PARTITION BY spot_score
                           ORDER BY id ASC
                           ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING
                       ) AS tied_ahead
                FROM users
                """.trimIndent(),
                explicitStatementType = StatementType.SELECT,
            ) { rs ->
                val lines = mutableListOf<String>()
                while (rs.next()) lines += rs.getString(1)
                lines
            } ?: emptyList()
        }

        val planText = plan.joinToString("\n")
        assertTrue(planText.contains("WindowAgg"), "expected a windowed aggregate plan, got:\n$planText")
        // A single pass: exactly one scan of `users` feeding all the window functions, never a
        // per-row subquery/nested loop back into it.
        assertEquals(1, plan.count { it.contains("on users") }, "expected exactly one scan of users, got:\n$planText")
    }

    @Test
    fun `computeAllDeltas on an empty table returns an empty map`() = runTest {
        assertEquals(emptyMap<UUID, Any>(), service.computeAllDeltas())
    }
}
