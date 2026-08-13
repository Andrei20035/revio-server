package dao

import com.revio.server.features.car_family.CarFamilyTable
import com.revio.server.features.challenge.ChallengeDAO
import com.revio.server.features.challenge.ChallengeStatus
import com.revio.server.features.challenge.ChallengeTable
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import testutils.TestDatabaseFactory
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * IChallengeDAO.findDueForFinalization: the set the finalization job (plan §9-C) works through —
 * SCHEDULED, ended, not yet finalized. DRAFT/CANCELLED/active/already-finalized must never appear.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChallengeDaoFindDueForFinalizationTest {

    private val dao = ChallengeDAO()

    @BeforeAll
    fun setup() = TestDatabaseFactory.start()

    @AfterAll
    fun tearDown() = TestDatabaseFactory.stop()

    @BeforeEach
    fun clean() = TestDatabaseFactory.cleanDatabase()

    private fun seedFamily(name: String = "Golf"): UUID = transaction {
        CarFamilyTable.insert {
            it[CarFamilyTable.brand] = "volkswagen"
            it[CarFamilyTable.name] = name
        }[CarFamilyTable.id].value
    }

    /** Inserts a challenge directly (bypassing ChallengeService), with [finalizedAt] settable. */
    private fun seedChallenge(
        familyId: UUID,
        title: String,
        status: ChallengeStatus,
        startsAt: Instant,
        endsAt: Instant,
        finalizedAt: Instant? = null,
    ): UUID = transaction {
        val id = ChallengeTable.insert {
            it[ChallengeTable.title] = title
            it[ChallengeTable.targetFamilyId] = familyId
            it[ChallengeTable.requiredPosts] = 5
            it[ChallengeTable.rewardPoints] = 300
            it[ChallengeTable.startsAt] = startsAt.atOffset(ZoneOffset.UTC)
            it[ChallengeTable.endsAt] = endsAt.atOffset(ZoneOffset.UTC)
            it[ChallengeTable.adminTimezone] = "Europe/Bucharest"
            it[ChallengeTable.status] = status
        }[ChallengeTable.id].value

        if (finalizedAt != null) {
            ChallengeTable.update({ ChallengeTable.id eq id }) {
                it[ChallengeTable.finalizedAt] = finalizedAt.atOffset(ZoneOffset.UTC)
            }
        }
        id
    }

    @Test
    fun `returns a SCHEDULED challenge whose window already ended and is not yet finalized`() = runTest {
        val familyId = seedFamily()
        val now = Instant.now()
        val due = seedChallenge(familyId, "Due", ChallengeStatus.SCHEDULED, now.minus(2, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS))

        val result = dao.findDueForFinalization(now, limit = 10)

        assertEquals(listOf(due), result.map { it.id })
    }

    @Test
    fun `skips a DRAFT challenge even if its window has passed`() = runTest {
        val familyId = seedFamily()
        val now = Instant.now()
        seedChallenge(familyId, "Draft", ChallengeStatus.DRAFT, now.minus(2, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS))

        val result = dao.findDueForFinalization(now, limit = 10)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `skips a CANCELLED challenge even if its window has passed`() = runTest {
        val familyId = seedFamily()
        val now = Instant.now()
        seedChallenge(familyId, "Cancelled", ChallengeStatus.CANCELLED, now.minus(2, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS))

        val result = dao.findDueForFinalization(now, limit = 10)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `skips a SCHEDULED challenge that is still active`() = runTest {
        val familyId = seedFamily()
        val now = Instant.now()
        seedChallenge(familyId, "Active", ChallengeStatus.SCHEDULED, now.minus(1, ChronoUnit.HOURS), now.plus(1, ChronoUnit.HOURS))

        val result = dao.findDueForFinalization(now, limit = 10)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `skips a SCHEDULED challenge that already has finalizedAt set`() = runTest {
        val familyId = seedFamily()
        val now = Instant.now()
        seedChallenge(
            familyId, "Already finalized", ChallengeStatus.SCHEDULED,
            now.minus(2, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS),
            finalizedAt = now.minus(1, ChronoUnit.DAYS),
        )

        val result = dao.findDueForFinalization(now, limit = 10)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `orders results by endsAt ascending and respects limit`() = runTest {
        val familyId = seedFamily()
        val now = Instant.now()
        val oldest = seedChallenge(familyId, "Oldest", ChallengeStatus.SCHEDULED, now.minus(5, ChronoUnit.DAYS), now.minus(4, ChronoUnit.DAYS))
        val middle = seedChallenge(familyId, "Middle", ChallengeStatus.SCHEDULED, now.minus(3, ChronoUnit.DAYS), now.minus(2, ChronoUnit.DAYS))
        seedChallenge(familyId, "Newest", ChallengeStatus.SCHEDULED, now.minus(2, ChronoUnit.HOURS), now.minus(1, ChronoUnit.HOURS))

        val limited = dao.findDueForFinalization(now, limit = 2)

        assertEquals(listOf(oldest, middle), limited.map { it.id })
    }
}
