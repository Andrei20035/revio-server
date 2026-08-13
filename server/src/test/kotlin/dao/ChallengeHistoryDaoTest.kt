package dao

import com.revio.server.features.challenge.ChallengeHistoryDAO
import com.revio.server.features.challenge.ChallengeHistoryFilter
import com.revio.server.features.challenge.ChallengeParticipantTable
import com.revio.server.features.challenge.ChallengeProgressDAO
import com.revio.server.features.challenge.RewardState
import kotlinx.coroutines.runBlocking
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
import testutils.ChallengeTestSeed
import testutils.CommentTestSeed
import testutils.TestDatabaseFactory
import java.time.Instant
import java.util.UUID

/**
 * IChallengeHistoryDAO — the read side backing "My Challenges" (plan §5.1/§7 Pas 4): a user's
 * own participation history (only challenges they have a challenge_participants row for) and
 * their lifetime aggregates. Deliberately separate from ChallengeProgressDAO, whose single
 * multi-op transaction is the write path for one challenge/user at a time; this DAO is pure
 * reads across many challenges for one user.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChallengeHistoryDaoTest {

    private val dao = ChallengeHistoryDAO()
    private val progressDao = ChallengeProgressDAO()

    @BeforeAll
    fun setup() = TestDatabaseFactory.start()

    @AfterAll
    fun tearDown() = TestDatabaseFactory.stop()

    @BeforeEach
    fun clean() = TestDatabaseFactory.cleanDatabase()

    // ---------- helpers ----------

    /** Inserts a bare participant row (no reward logic) — enough to make a challenge "participated in". */
    private fun seedParticipant(challengeId: UUID, userId: UUID, contributionCount: Int = 0, rewardState: RewardState = RewardState.NONE) = transaction {
        ChallengeParticipantTable.insert {
            it[ChallengeParticipantTable.challengeId] = challengeId
            it[ChallengeParticipantTable.userId] = userId
            it[ChallengeParticipantTable.contributionCount] = contributionCount
            it[ChallengeParticipantTable.rewardState] = rewardState
        }
    }

    /**
     * A 1-hour-wide challenge ending at [endsAt]. Callers must space [endsAt] values at least a
     * few hours apart within a test — `challenges` has a no-overlap exclusion constraint on
     * SCHEDULED windows (V24), so two windows that touch or cross fail the insert.
     */
    private fun seedChallengeEndingAt(endsAt: Instant, familyId: UUID, requiredPosts: Int = 5): UUID =
        ChallengeTestSeed.seedChallenge(
            familyId = familyId,
            startsAt = endsAt.minusSeconds(3600),
            endsAt = endsAt,
            requiredPosts = requiredPosts,
        )

    // ---------- listUserHistory — empty / basic shape ----------

    @Test
    fun `listUserHistory returns an empty list for a user with no participation`() = runBlocking {
        val user = CommentTestSeed.seedUser()

        val page = dao.listUserHistory(user.userId, limit = 20, cursorEndsAt = null, cursorId = null, filter = null)

        assertTrue(page.isEmpty())
    }

    @Test
    fun `listUserHistory only includes challenges the user has a participant row for`() = runBlocking {
        val user = CommentTestSeed.seedUser()
        val familyId = ChallengeTestSeed.seedFamily()
        val now = Instant.now()
        val participated = seedChallengeEndingAt(now.minusSeconds(3600), familyId)
        seedChallengeEndingAt(now.minusSeconds(6 * 3600), familyId) // never joined — must not appear
        seedParticipant(participated, user.userId)

        val page = dao.listUserHistory(user.userId, limit = 20, cursorEndsAt = null, cursorId = null, filter = null)

        assertEquals(listOf(participated), page.map { it.challenge.id })
    }

    @Test
    fun `listUserHistory orders by endsAt descending, most recently finished first`() = runBlocking {
        val user = CommentTestSeed.seedUser()
        val familyId = ChallengeTestSeed.seedFamily()
        val now = Instant.now()
        val oldest = seedChallengeEndingAt(now.minusSeconds(30 * 24 * 3600), familyId)
        val newest = seedChallengeEndingAt(now.minusSeconds(3600), familyId)
        val middle = seedChallengeEndingAt(now.minusSeconds(10 * 24 * 3600), familyId)
        listOf(oldest, newest, middle).forEach { seedParticipant(it, user.userId) }

        val page = dao.listUserHistory(user.userId, limit = 20, cursorEndsAt = null, cursorId = null, filter = null)

        assertEquals(listOf(newest, middle, oldest), page.map { it.challenge.id })
    }

    @Test
    fun `listUserHistory returns each user's own progress alongside the challenge`() = runBlocking {
        val user = CommentTestSeed.seedUser()
        val familyId = ChallengeTestSeed.seedFamily()
        val now = Instant.now()
        val challengeId = seedChallengeEndingAt(now.minusSeconds(3600), familyId)
        seedParticipant(challengeId, user.userId, contributionCount = 3, rewardState = RewardState.GRANTED)

        val page = dao.listUserHistory(user.userId, limit = 20, cursorEndsAt = null, cursorId = null, filter = null)

        assertEquals(1, page.size)
        assertEquals(3, page[0].progress.contributionCount)
        assertEquals(RewardState.GRANTED, page[0].progress.rewardState)
    }

    // ---------- listUserHistory — isolation between users ----------

    @Test
    fun `listUserHistory never returns another user's participation`() = runBlocking {
        val userA = CommentTestSeed.seedUser(username = "alice")
        val userB = CommentTestSeed.seedUser(username = "bob")
        val familyId = ChallengeTestSeed.seedFamily()
        val now = Instant.now()
        val challengeA = seedChallengeEndingAt(now.minusSeconds(3600), familyId)
        val challengeB = seedChallengeEndingAt(now.minusSeconds(6 * 3600), familyId)
        seedParticipant(challengeA, userA.userId)
        seedParticipant(challengeB, userB.userId)

        val pageA = dao.listUserHistory(userA.userId, limit = 20, cursorEndsAt = null, cursorId = null, filter = null)
        val pageB = dao.listUserHistory(userB.userId, limit = 20, cursorEndsAt = null, cursorId = null, filter = null)

        assertEquals(listOf(challengeA), pageA.map { it.challenge.id })
        assertEquals(listOf(challengeB), pageB.map { it.challenge.id })
    }

    // ---------- listUserHistory — filter ----------

    @Test
    fun `listUserHistory filter=COMPLETED returns only GRANTED participants`() = runBlocking {
        val user = CommentTestSeed.seedUser()
        val familyId = ChallengeTestSeed.seedFamily()
        val now = Instant.now()
        val completed = seedChallengeEndingAt(now.minusSeconds(3600), familyId)
        val notCompleted = seedChallengeEndingAt(now.minusSeconds(6 * 3600), familyId)
        seedParticipant(completed, user.userId, rewardState = RewardState.GRANTED)
        seedParticipant(notCompleted, user.userId, rewardState = RewardState.NONE)

        val page = dao.listUserHistory(user.userId, limit = 20, cursorEndsAt = null, cursorId = null, filter = ChallengeHistoryFilter.COMPLETED)

        assertEquals(listOf(completed), page.map { it.challenge.id })
    }

    @Test
    fun `listUserHistory filter=NOT_COMPLETED excludes GRANTED participants`() = runBlocking {
        val user = CommentTestSeed.seedUser()
        val familyId = ChallengeTestSeed.seedFamily()
        val now = Instant.now()
        val completed = seedChallengeEndingAt(now.minusSeconds(3600), familyId)
        val notCompleted = seedChallengeEndingAt(now.minusSeconds(6 * 3600), familyId)
        seedParticipant(completed, user.userId, rewardState = RewardState.GRANTED)
        seedParticipant(notCompleted, user.userId, rewardState = RewardState.NONE)

        val page = dao.listUserHistory(user.userId, limit = 20, cursorEndsAt = null, cursorId = null, filter = ChallengeHistoryFilter.NOT_COMPLETED)

        assertEquals(listOf(notCompleted), page.map { it.challenge.id })
    }

    // ---------- listUserHistory — stable keyset pagination ----------

    @Test
    fun `listUserHistory paginates stably across pages without duplicates or omissions`() = runBlocking {
        val user = CommentTestSeed.seedUser()
        val familyId = ChallengeTestSeed.seedFamily()
        val now = Instant.now()
        // i=1 ends closest to now (newest), i=7 ends furthest in the past (oldest) — already in
        // the DESC-by-endsAt order the DAO must reproduce.
        val challengeIds = (1..7).map { i -> seedChallengeEndingAt(now.minusSeconds(i * 3600L), familyId) }
        challengeIds.forEach { seedParticipant(it, user.userId) }
        val expectedOrder = challengeIds

        val collected = mutableListOf<UUID>()
        var cursorEndsAt: Instant? = null
        var cursorId: UUID? = null
        var pages = 0
        do {
            val page = dao.listUserHistory(user.userId, limit = 3, cursorEndsAt = cursorEndsAt, cursorId = cursorId, filter = null)
            collected += page.map { it.challenge.id }
            val last = page.lastOrNull()
            cursorEndsAt = last?.challenge?.endsAt
            cursorId = last?.challenge?.id
            pages++
        } while (page_hasMore(page = page, pageSize = 3) && pages < 10)

        assertEquals(expectedOrder, collected, "keyset pagination must reproduce the exact full ordering with no dup/omission")
        assertEquals(3, pages, "7 items at page size 3 must take ceil(7/3) = 3 pages")
    }

    private fun page_hasMore(page: List<*>, pageSize: Int): Boolean = page.size == pageSize

    // ---------- getUserAggregates ----------

    @Test
    fun `getUserAggregates returns zeros for a user with no participation`() = runBlocking {
        val user = CommentTestSeed.seedUser()

        val aggregates = dao.getUserAggregates(user.userId)

        assertEquals(0, aggregates.joinedCount)
        assertEquals(0, aggregates.completedCount)
        assertEquals(0, aggregates.pointsEarned)
        assertEquals(0, aggregates.totalContributions)
    }

    @Test
    fun `getUserAggregates counts joined, completed and contributions across mixed-state challenges`() = runBlocking {
        val user = CommentTestSeed.seedUser()
        val familyId = ChallengeTestSeed.seedFamily()
        val modelId = ChallengeTestSeed.seedModel("volkswagen", "golf r", familyId)
        val now = Instant.now()

        // Challenge 1: completed (reaches requiredPosts via the real grant path). Windows must be
        // disjoint (challenges.excl_challenges_no_overlap) — each post's postCreatedAt is chosen
        // inside its own challenge's window, not a shared "now".
        val completedWindowPost = now.minusSeconds(20 * 3600)
        val completedChallenge = ChallengeTestSeed.seedChallenge(
            familyId = familyId,
            startsAt = completedWindowPost.minusSeconds(3600),
            endsAt = completedWindowPost.plusSeconds(3600),
            requiredPosts = 1,
            rewardPoints = 100,
        )
        val post1 = ChallengeTestSeed.seedCameraPost(user.userId, modelId)
        progressDao.evaluatePostContribution(completedChallenge, user.userId, post1, modelId, completedWindowPost)
        progressDao.finalizeParticipants(completedChallenge)

        // Challenge 2: joined, not completed. A separate window, far from the first.
        val incompleteWindowPost = now.minusSeconds(10 * 3600)
        val incompleteChallenge = ChallengeTestSeed.seedChallenge(
            familyId = familyId,
            startsAt = incompleteWindowPost.minusSeconds(3600),
            endsAt = incompleteWindowPost.plusSeconds(3600),
            requiredPosts = 5,
            rewardPoints = 50,
        )
        val post2 = ChallengeTestSeed.seedCameraPost(user.userId, modelId)
        progressDao.evaluatePostContribution(incompleteChallenge, user.userId, post2, modelId, incompleteWindowPost)

        val aggregates = dao.getUserAggregates(user.userId)

        assertEquals(2, aggregates.joinedCount)
        assertEquals(1, aggregates.completedCount)
        assertEquals(2, aggregates.totalContributions)
        assertEquals(100, aggregates.pointsEarned)
    }

    @Test
    fun `getUserAggregates pointsEarned reflects a revoke floored at zero, not the nominal reward`() = runBlocking {
        val user = CommentTestSeed.seedUser()
        val familyId = ChallengeTestSeed.seedFamily()
        val modelId = ChallengeTestSeed.seedModel("volkswagen", "golf r", familyId)
        val now = Instant.now()
        val challengeId = ChallengeTestSeed.seedChallenge(
            familyId = familyId, startsAt = now.minusSeconds(7200), endsAt = now.plusSeconds(3600), requiredPosts = 1, rewardPoints = 300,
        )
        val postId = ChallengeTestSeed.seedCameraPost(user.userId, modelId)
        progressDao.evaluatePostContribution(challengeId, user.userId, postId, modelId, now)
        progressDao.finalizeParticipants(challengeId)

        // Drop the user's spot_score below the reward amount before revoking, so the revoke's
        // applied_delta is floored short of -300 — the same scenario ChallengeProgressDaoTest
        // exercises directly against spot_score. pointsEarned must reflect the floored value.
        transaction {
            com.revio.server.features.user.UserTable.update({ com.revio.server.features.user.UserTable.id eq user.userId }) {
                it[com.revio.server.features.user.UserTable.spotScore] = 100
            }
        }
        transaction { progressDao.removeContributionsForPostInCurrentTransaction(postId) { true } }

        // GRANT(+300) then REVOKE(floored to -100) nets to +200, not 0.
        val aggregates = dao.getUserAggregates(user.userId)

        assertEquals(200, aggregates.pointsEarned)
    }
}
