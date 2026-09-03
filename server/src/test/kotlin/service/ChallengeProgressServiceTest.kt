package service

import com.revio.server.features.challenge.Challenge
import com.revio.server.features.challenge.ChallengeHistoryEntry
import com.revio.server.features.challenge.ChallengeHistoryFilter
import com.revio.server.features.challenge.ChallengeProgressService
import com.revio.server.features.challenge.ChallengeStatus
import com.revio.server.features.challenge.ChallengeUserAggregates
import com.revio.server.features.challenge.ContributionEvaluation
import com.revio.server.features.challenge.ContributionSummary
import com.revio.server.features.challenge.EffectiveChallengeStatus
import com.revio.server.features.challenge.IChallengeDAO
import com.revio.server.features.challenge.IChallengeHistoryDAO
import com.revio.server.features.challenge.IChallengeProgressDAO
import com.revio.server.features.challenge.ParticipantProgress
import com.revio.server.features.challenge.RewardState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * ChallengeProgressService — the thin orchestration layer between PostService/PostRemovalDAO and
 * IChallengeProgressDAO. Covers the "no active challenge" branch of the plan's §8 matrix
 * ("Postare în intervalul unui challenge DRAFT/CANCELLED → neeligibilă", expressed here as
 * findActive returning null since only a SCHEDULED, in-window challenge ever comes back from it)
 * and the REVOKE_AFTER_END policy predicate.
 */
class ChallengeProgressServiceTest {

    private val challengeId = UUID.randomUUID()
    private val userId = UUID.randomUUID()
    private val postId = UUID.randomUUID()
    private val carModelId = UUID.randomUUID()

    private fun activeChallenge(id: UUID = challengeId) = Challenge(
        id = id,
        title = "Weekend Golf Hunt",
        description = null,
        targetFamilyId = UUID.randomUUID(),
        requiredPosts = 5,
        rewardPoints = 300,
        startsAt = Instant.now().minusSeconds(100),
        endsAt = Instant.now().plusSeconds(100),
        adminTimezone = "Europe/Bucharest",
        status = ChallengeStatus.SCHEDULED,
        createdBy = null,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
        publishedAt = Instant.now(),
        cancelledAt = null,
        finalizedAt = null,
        notifiedStartedAt = null,
    )

    private fun challengeWith(
        id: UUID = UUID.randomUUID(),
        status: ChallengeStatus = ChallengeStatus.SCHEDULED,
        startsAt: Instant = Instant.now().minusSeconds(100),
        endsAt: Instant = Instant.now().plusSeconds(100),
    ) = Challenge(
        id = id,
        title = "Weekend Golf Hunt",
        description = null,
        targetFamilyId = UUID.randomUUID(),
        requiredPosts = 5,
        rewardPoints = 300,
        startsAt = startsAt,
        endsAt = endsAt,
        adminTimezone = "Europe/Bucharest",
        status = status,
        createdBy = null,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
        publishedAt = Instant.now(),
        cancelledAt = null,
        finalizedAt = null,
        notifiedStartedAt = null,
    )

    // ---------- evaluatePostForActiveChallenge ----------

    @Test
    fun `evaluatePostForActiveChallenge returns null and never calls the progress DAO when no challenge is active`() = runTest {
        val challengeDao = mockk<IChallengeDAO>()
        val postCreatedAt = Instant.now()
        coEvery { challengeDao.findActive(postCreatedAt) } returns null
        val progressDao = mockk<IChallengeProgressDAO>(relaxed = true)

        val result = ChallengeProgressService(challengeDao, progressDao)
            .evaluatePostForActiveChallenge(userId, postId, carModelId, postCreatedAt)

        assertNull(result)
        coVerify(exactly = 0) { progressDao.evaluatePostContribution(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `evaluatePostForActiveChallenge delegates to the progress DAO for the challenge found active at that exact instant`() = runTest {
        val challengeDao = mockk<IChallengeDAO>()
        val postCreatedAt = Instant.now()
        coEvery { challengeDao.findActive(postCreatedAt) } returns activeChallenge()
        val progressDao = mockk<IChallengeProgressDAO>()
        val expected = ContributionEvaluation(eligible = true, contributionCount = 3)
        coEvery {
            progressDao.evaluatePostContribution(challengeId, userId, postId, carModelId, postCreatedAt)
        } returns expected

        val result = ChallengeProgressService(challengeDao, progressDao)
            .evaluatePostForActiveChallenge(userId, postId, carModelId, postCreatedAt)

        assertEquals(expected, result)
    }

    @Test
    fun `evaluatePostForActiveChallenge uses the same instant to find the active challenge and to evaluate the window`() = runTest {
        // Guards against clock-skew bugs: "is there an active challenge" and "is this post inside
        // its window" must be answered from the SAME instant, not two separate Instant.now() reads.
        val challengeDao = mockk<IChallengeDAO>()
        val postCreatedAt = Instant.parse("2026-08-08T10:00:00Z")
        coEvery { challengeDao.findActive(postCreatedAt) } returns activeChallenge()
        val progressDao = mockk<IChallengeProgressDAO>()
        coEvery {
            progressDao.evaluatePostContribution(challengeId, userId, postId, carModelId, postCreatedAt)
        } returns ContributionEvaluation(eligible = true)

        ChallengeProgressService(challengeDao, progressDao)
            .evaluatePostForActiveChallenge(userId, postId, carModelId, postCreatedAt)

        coVerify(exactly = 1) { challengeDao.findActive(postCreatedAt) }
        coVerify(exactly = 1) { progressDao.evaluatePostContribution(challengeId, userId, postId, carModelId, postCreatedAt) }
    }

    // ---------- contributionRevokePolicy — REVOKE_AFTER_END ----------

    @Test
    fun `contributionRevokePolicy allows revoking a contribution removed before endsAt`() {
        val service = ChallengeProgressService(mockk(relaxed = true), mockk(relaxed = true))
        val policy = service.contributionRevokePolicy()

        assertTrue(policy(Instant.now().plusSeconds(3600)), "endsAt still in the future")
    }

    @Test
    fun `contributionRevokePolicy also allows revoking after endsAt, matching the current REVOKE_AFTER_END=true MVP policy`() {
        val service = ChallengeProgressService(mockk(relaxed = true), mockk(relaxed = true))
        val policy = service.contributionRevokePolicy()

        assertTrue(policy(Instant.now().minusSeconds(3600)), "endsAt already in the past — plan §2.6a: still revoked")
    }

    // ---------- getUserProgress / listUserContributions ----------

    @Test
    fun `getUserProgress returns the DAO's progress when a participant row exists`() = runTest {
        val progressDao = mockk<IChallengeProgressDAO>()
        coEvery { progressDao.getProgress(challengeId, userId) } returns ParticipantProgress(contributionCount = 4, rewardState = RewardState.GRANTED)

        val progress = ChallengeProgressService(mockk(relaxed = true), progressDao).getUserProgress(challengeId, userId)

        assertEquals(4, progress.contributionCount)
        assertEquals(RewardState.GRANTED, progress.rewardState)
    }

    @Test
    fun `getUserProgress defaults to zero contributions and NONE when there is no participant row yet`() = runTest {
        val progressDao = mockk<IChallengeProgressDAO>()
        coEvery { progressDao.getProgress(challengeId, userId) } returns null

        val progress = ChallengeProgressService(mockk(relaxed = true), progressDao).getUserProgress(challengeId, userId)

        assertEquals(0, progress.contributionCount)
        assertEquals(RewardState.NONE, progress.rewardState)
    }

    @Test
    fun `listUserContributions passes through the DAO's list unchanged`() = runTest {
        val progressDao = mockk<IChallengeProgressDAO>()
        val summaries = listOf(
            ContributionSummary(postId = postId, createdAt = Instant.now(), imageKey = "posts/test.jpg", carBrand = "volkswagen", carModel = "golf r"),
        )
        coEvery { progressDao.listContributionsForUser(challengeId, userId) } returns summaries

        val result = ChallengeProgressService(mockk(relaxed = true), progressDao).listUserContributions(challengeId, userId)

        assertEquals(summaries, result)
    }

    // ---------- listUserHistory — effectiveStatus applied per element, pagination, validation ----------

    private fun serviceWithHistory(historyDao: IChallengeHistoryDAO) =
        ChallengeProgressService(mockk(relaxed = true), mockk(relaxed = true), historyDao)

    @Test
    fun `listUserHistory computes effectiveStatus per item from each challenge's own status and window`() = runTest {
        val now = Instant.now()
        val active = challengeWith(status = ChallengeStatus.SCHEDULED, startsAt = now.minusSeconds(100), endsAt = now.plusSeconds(100))
        val ended = challengeWith(status = ChallengeStatus.SCHEDULED, startsAt = now.minusSeconds(200), endsAt = now.minusSeconds(100))
        val scheduled = challengeWith(status = ChallengeStatus.SCHEDULED, startsAt = now.plusSeconds(100), endsAt = now.plusSeconds(200))
        val cancelled = challengeWith(status = ChallengeStatus.CANCELLED, startsAt = now.minusSeconds(200), endsAt = now.plusSeconds(200))
        val progress = ParticipantProgress(contributionCount = 1, rewardState = RewardState.NONE)
        val historyDao = mockk<IChallengeHistoryDAO>()
        coEvery { historyDao.listUserHistory(userId, 21, null, null, null) } returns listOf(
            ChallengeHistoryEntry(active, progress),
            ChallengeHistoryEntry(ended, progress),
            ChallengeHistoryEntry(scheduled, progress),
            ChallengeHistoryEntry(cancelled, progress),
        )

        val page = serviceWithHistory(historyDao).listUserHistory(userId, limit = 20, cursorEndsAt = null, cursorId = null, filter = null)

        assertEquals(
            listOf(
                EffectiveChallengeStatus.ACTIVE,
                EffectiveChallengeStatus.ENDED,
                EffectiveChallengeStatus.SCHEDULED,
                EffectiveChallengeStatus.CANCELLED,
            ),
            page.items.map { it.effectiveStatus },
        )
    }

    @Test
    fun `listUserHistory sets hasMore and trims the extra row when the DAO returns limit+1`() = runTest {
        val historyDao = mockk<IChallengeHistoryDAO>()
        val progress = ParticipantProgress(contributionCount = 0, rewardState = RewardState.NONE)
        val entries = (1..3).map { ChallengeHistoryEntry(challengeWith(), progress) }
        coEvery { historyDao.listUserHistory(userId, 3, null, null, null) } returns entries

        val page = serviceWithHistory(historyDao).listUserHistory(userId, limit = 2, cursorEndsAt = null, cursorId = null, filter = null)

        assertEquals(2, page.items.size)
        assertTrue(page.hasMore)
        assertEquals(page.items.last().challenge.endsAt, page.nextCursorEndsAt)
        assertEquals(page.items.last().challenge.id, page.nextCursorId)
    }

    @Test
    fun `listUserHistory reports hasMore=false and a null cursor when the DAO returns fewer than limit+1`() = runTest {
        val historyDao = mockk<IChallengeHistoryDAO>()
        val progress = ParticipantProgress(contributionCount = 0, rewardState = RewardState.NONE)
        coEvery { historyDao.listUserHistory(userId, 21, null, null, null) } returns listOf(ChallengeHistoryEntry(challengeWith(), progress))

        val page = serviceWithHistory(historyDao).listUserHistory(userId, limit = 20, cursorEndsAt = null, cursorId = null, filter = null)

        assertFalse(page.hasMore)
        assertNull(page.nextCursorEndsAt)
        assertNull(page.nextCursorId)
    }

    @Test
    fun `listUserHistory defaults limit to 20 when 0 is passed`() = runTest {
        val historyDao = mockk<IChallengeHistoryDAO>()
        coEvery { historyDao.listUserHistory(userId, 21, null, null, null) } returns emptyList()

        serviceWithHistory(historyDao).listUserHistory(userId, limit = 0, cursorEndsAt = null, cursorId = null, filter = null)

        coVerify(exactly = 1) { historyDao.listUserHistory(userId, 21, null, null, null) }
    }

    @Test
    fun `listUserHistory rejects a limit above the max`() = runTest {
        val historyDao = mockk<IChallengeHistoryDAO>(relaxed = true)

        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                serviceWithHistory(historyDao).listUserHistory(userId, limit = 51, cursorEndsAt = null, cursorId = null, filter = null)
            }
        }
    }

    @Test
    fun `listUserHistory rejects a cursor with only one of cursorEndsAt or cursorId set`() = runTest {
        val historyDao = mockk<IChallengeHistoryDAO>(relaxed = true)

        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                serviceWithHistory(historyDao).listUserHistory(userId, limit = 20, cursorEndsAt = Instant.now(), cursorId = null, filter = null)
            }
        }
    }

    @Test
    fun `listUserHistory passes the filter through to the DAO unchanged`() = runTest {
        val historyDao = mockk<IChallengeHistoryDAO>()
        coEvery { historyDao.listUserHistory(userId, 21, null, null, ChallengeHistoryFilter.COMPLETED) } returns emptyList()

        serviceWithHistory(historyDao).listUserHistory(userId, limit = 20, cursorEndsAt = null, cursorId = null, filter = ChallengeHistoryFilter.COMPLETED)

        coVerify(exactly = 1) { historyDao.listUserHistory(userId, 21, null, null, ChallengeHistoryFilter.COMPLETED) }
    }

    // ---------- getUserAggregates ----------

    @Test
    fun `getUserAggregates delegates to the history DAO unchanged`() = runTest {
        val historyDao = mockk<IChallengeHistoryDAO>()
        val expected = ChallengeUserAggregates(joinedCount = 7, completedCount = 4, pointsEarned = 320, totalContributions = 19)
        coEvery { historyDao.getUserAggregates(userId) } returns expected

        val result = serviceWithHistory(historyDao).getUserAggregates(userId)

        assertEquals(expected, result)
    }
}
