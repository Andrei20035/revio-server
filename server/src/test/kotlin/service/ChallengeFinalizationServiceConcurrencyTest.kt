package service

import com.revio.server.features.challenge.ChallengeContributionTable
import com.revio.server.features.challenge.ChallengeDAO
import com.revio.server.features.challenge.ChallengeFinalizationService
import com.revio.server.features.challenge.ChallengeParticipantTable
import com.revio.server.features.challenge.ChallengeProgressDAO
import com.revio.server.features.challenge.ChallengeRewardLedgerTable
import com.revio.server.features.challenge.LedgerEntryKind
import com.revio.server.features.challenge.RewardState
import com.revio.server.features.user.UserTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import testutils.ChallengeTestSeed
import testutils.CommentTestSeed
import testutils.TestDatabaseFactory
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * ChallengeFinalizationService.finalize under real concurrency (plan §9-C4), with real threads —
 * same pattern as ChallengeProgressDaoTest.kt's "concurrency" section. The advisory lock is a
 * work-avoidance optimization, not the source of correctness (that's the per-participant
 * `SELECT ... FOR UPDATE` inside finalizeParticipants), so this asserts the outcome that actually
 * matters regardless of how the two calls interleave: exactly one grant, once.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChallengeFinalizationServiceConcurrencyTest {

    private val challengeDao = ChallengeDAO()
    private val progressDao = ChallengeProgressDAO()
    private val service = ChallengeFinalizationService(challengeDao, progressDao)

    @BeforeAll
    fun setup() = TestDatabaseFactory.start()

    @AfterAll
    fun tearDown() = TestDatabaseFactory.stop()

    @BeforeEach
    fun clean() = TestDatabaseFactory.cleanDatabase()

    private fun spotScore(userId: UUID): Int = transaction {
        UserTable.select(UserTable.spotScore).where { UserTable.id eq userId }.single()[UserTable.spotScore]
    }

    private fun grantLedgerRowCount(challengeId: UUID, userId: UUID): Int = transaction {
        ChallengeRewardLedgerTable.select(ChallengeRewardLedgerTable.id)
            .where {
                (ChallengeRewardLedgerTable.challengeId eq challengeId) and
                    (ChallengeRewardLedgerTable.userId eq userId) and
                    (ChallengeRewardLedgerTable.kind eq LedgerEntryKind.GRANT)
            }
            .count()
            .toInt()
    }

    private fun participantRewardState(challengeId: UUID, userId: UUID): RewardState = transaction {
        ChallengeParticipantTable.selectAll()
            .where { (ChallengeParticipantTable.challengeId eq challengeId) and (ChallengeParticipantTable.userId eq userId) }
            .single()[ChallengeParticipantTable.rewardState]
    }

    /** Inserts a participant row directly, bypassing evaluatePostContribution's own grant/revoke. */
    private fun seedParticipant(challengeId: UUID, userId: UUID, rewardState: RewardState, contributionCountCache: Int) = transaction {
        ChallengeParticipantTable.insert {
            it[ChallengeParticipantTable.challengeId] = challengeId
            it[ChallengeParticipantTable.userId] = userId
            it[ChallengeParticipantTable.contributionCount] = contributionCountCache
            it[ChallengeParticipantTable.rewardState] = rewardState
        }
    }

    /** Inserts one real contribution row (with a real backing post) directly. */
    private fun seedContribution(challengeId: UUID, userId: UUID, modelId: UUID) = transaction {
        val postId = ChallengeTestSeed.seedCameraPost(userId, modelId)
        ChallengeContributionTable.insert {
            it[ChallengeContributionTable.challengeId] = challengeId
            it[ChallengeContributionTable.userId] = userId
            it[ChallengeContributionTable.postId] = postId
            it[ChallengeContributionTable.carModelId] = modelId
            it[ChallengeContributionTable.postCreatedAt] = Instant.now().atOffset(ZoneOffset.UTC)
        }
    }

    @Test
    fun `two simultaneous finalizes of the same due challenge grant exactly once`() {
        val familyId = ChallengeTestSeed.seedFamily()
        val modelId = ChallengeTestSeed.seedModel("volkswagen", "golf r", familyId)
        val user = CommentTestSeed.seedUser()
        val now = Instant.now()
        val challengeId = ChallengeTestSeed.seedChallenge(
            familyId = familyId,
            startsAt = now.minus(4, ChronoUnit.HOURS),
            endsAt = now.minus(1, ChronoUnit.HOURS),
            requiredPosts = 3,
            rewardPoints = 300,
        )
        repeat(3) { seedContribution(challengeId, user.userId, modelId) }
        seedParticipant(challengeId, user.userId, RewardState.NONE, contributionCountCache = 3)

        val results = runBlocking(Dispatchers.IO) {
            (1..2).map {
                async(Dispatchers.IO) { service.finalize(challengeId, now) }
            }.awaitAll()
        }

        assertEquals(1, results.sumOf { it?.grantedCount ?: 0 }, "exactly one grant across both calls")
        assertEquals(0, results.sumOf { it?.revokedCount ?: 0 })
        assertEquals(RewardState.GRANTED, participantRewardState(challengeId, user.userId))
        assertEquals(300, spotScore(user.userId), "spot_score must be moved exactly once, not twice")
        assertEquals(1, grantLedgerRowCount(challengeId, user.userId), "exactly one GRANT ledger row, not two")
    }
}
