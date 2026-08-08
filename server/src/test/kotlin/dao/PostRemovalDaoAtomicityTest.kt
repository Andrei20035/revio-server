package dao

import com.revio.server.features.car_family.CarFamilyTable
import com.revio.server.features.car_model.CarModelTable
import com.revio.server.features.challenge.ChallengeContributionTable
import com.revio.server.features.challenge.ChallengeParticipantTable
import com.revio.server.features.challenge.ChallengeDAO
import com.revio.server.features.challenge.ChallengeProgressDAO
import com.revio.server.features.challenge.ChallengeRewardLedgerTable
import com.revio.server.features.challenge.ChallengeStatus
import com.revio.server.features.challenge.IChallengeProgressDAO
import com.revio.server.features.challenge.RewardState
import com.revio.server.features.moderation.AdminAuditLogTable
import com.revio.server.features.moderation.ModerationReason
import com.revio.server.features.moderation.ModerationViolationTable
import com.revio.server.features.notification.NotificationTable
import com.revio.server.features.post.ModerationRemoval
import com.revio.server.features.post.PostRemovalDAO
import com.revio.server.features.post.PostSource
import com.revio.server.features.post.PostTable
import com.revio.server.features.scoring.ScoringDaoImpl
import com.revio.server.features.user.UserTable
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import testutils.CommentTestSeed
import testutils.TestDatabaseFactory
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Post removal must be all-or-nothing: reconciling the post's challenge contributions and
 * deleting the post row either both commit or neither does.
 *
 * The failure mode being guarded is specific. challenge_contributions.post_id is ON DELETE
 * CASCADE, so a delete that committed while reconciliation had failed would erase the
 * contribution rows with no chance to revoke the reward they earned — leaving
 * contribution_count/reward_state disagreeing with reality and spot_score inflated forever.
 * Nothing recomputes that, and the ledger invariant still holds, so no reconciliation pass
 * would even notice.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostRemovalDaoAtomicityTest {

    private val challengeDao = ChallengeDAO()
    private val challengeProgressDao = ChallengeProgressDAO()
    private val scoringDao = ScoringDaoImpl()
    private val removalDao = PostRemovalDAO(challengeProgressDao, scoringDao)

    /** The MVP policy (REVOKE_AFTER_END = true): a removed contribution always revokes. */
    private val alwaysRevoke: (Instant) -> Boolean = { true }

    private val requiredPosts = 2
    private val rewardPoints = 300
    private val normalPointsPerPost = 10

    @BeforeAll
    fun setup() = TestDatabaseFactory.start()

    @AfterAll
    fun tearDown() = TestDatabaseFactory.stop()

    @BeforeEach
    fun clean() = TestDatabaseFactory.cleanDatabase()

    // ---------- fixture ----------

    private data class Fixture(
        val userId: UUID,
        val challengeId: UUID,
        val firstPostId: UUID,
        val secondPostId: UUID,
    )

    /**
     * A user who completed a 2-post challenge: both posts contributed, the 300-point reward is
     * GRANTED, and spot_score is 320 (300 reward + 10 normal points per post).
     */
    private suspend fun completedChallengeFixture(): Fixture {
        val user = CommentTestSeed.seedUser()
        val familyId = transaction {
            CarFamilyTable.insert {
                it[CarFamilyTable.brand] = "volkswagen"
                it[CarFamilyTable.name] = "Golf"
            }[CarFamilyTable.id].value
        }
        val carModelId = transaction {
            CarModelTable.insert {
                it[CarModelTable.brand] = "volkswagen"
                it[CarModelTable.model] = "golf r"
                it[CarModelTable.familyId] = familyId
            }[CarModelTable.id].value
        }

        val now = Instant.now()
        // Built through the production DAO rather than a hand-rolled insert, so the fixture
        // exercises the same insert/publish path the admin routes use.
        val challengeId = challengeDao.insert(
            title = "Spot 2 Golfs",
            description = null,
            targetFamilyId = familyId,
            requiredPosts = requiredPosts,
            rewardPoints = rewardPoints,
            startsAt = now.minus(1, ChronoUnit.HOURS),
            endsAt = now.plus(1, ChronoUnit.HOURS),
            adminTimezone = "Europe/Bucharest",
            createdBy = null,
        )
        challengeDao.updateStatus(challengeId, ChallengeStatus.SCHEDULED, publishedAt = now)

        val firstPostId = seedCameraPost(user.userId, carModelId)
        val secondPostId = seedCameraPost(user.userId, carModelId)
        setSpotScore(user.userId, normalPointsPerPost * 2)

        challengeProgressDao.evaluatePostContribution(challengeId, user.userId, firstPostId, carModelId, now)
        challengeProgressDao.evaluatePostContribution(challengeId, user.userId, secondPostId, carModelId, now)

        // Guard the fixture itself, so a later assertion failure can't be blamed on bad setup.
        assertEquals(requiredPosts, contributionCount(challengeId, user.userId))
        assertEquals(RewardState.GRANTED, rewardState(challengeId, user.userId))
        assertEquals(normalPointsPerPost * 2 + rewardPoints, spotScore(user.userId))

        return Fixture(user.userId, challengeId, firstPostId, secondPostId)
    }

    private fun seedCameraPost(ownerUserId: UUID, carModelId: UUID): UUID = transaction {
        PostTable.insert {
            it[PostTable.userId] = ownerUserId
            it[PostTable.imageKey] = "posts/test.jpg"
            it[PostTable.carModelId] = carModelId
            it[PostTable.postSource] = PostSource.CAMERA.name
            it[PostTable.points] = normalPointsPerPost
        }[PostTable.id].value
    }

    private fun setSpotScore(userId: UUID, score: Int) = transaction {
        UserTable.update({ UserTable.id eq userId }) { it[UserTable.spotScore] = score }
    }

    private fun spotScore(userId: UUID): Int = transaction {
        UserTable.select(UserTable.spotScore).where { UserTable.id eq userId }.single()[UserTable.spotScore]
    }

    private fun postExists(postId: UUID): Boolean = transaction {
        PostTable.select(PostTable.id).where { PostTable.id eq postId }.any()
    }

    private fun contributionExists(postId: UUID): Boolean = transaction {
        ChallengeContributionTable.select(ChallengeContributionTable.id)
            .where { ChallengeContributionTable.postId eq postId }
            .any()
    }

    private fun contributionCount(challengeId: UUID, userId: UUID): Int = transaction {
        ChallengeParticipantTable.select(ChallengeParticipantTable.contributionCount)
            .where { (ChallengeParticipantTable.challengeId eq challengeId) and (ChallengeParticipantTable.userId eq userId) }
            .single()[ChallengeParticipantTable.contributionCount]
    }

    private fun rewardState(challengeId: UUID, userId: UUID): RewardState = transaction {
        ChallengeParticipantTable.select(ChallengeParticipantTable.rewardState)
            .where { (ChallengeParticipantTable.challengeId eq challengeId) and (ChallengeParticipantTable.userId eq userId) }
            .single()[ChallengeParticipantTable.rewardState]
    }

    private fun ledgerEntryCount(userId: UUID): Int = transaction {
        ChallengeRewardLedgerTable.select(ChallengeRewardLedgerTable.id)
            .where { ChallengeRewardLedgerTable.userId eq userId }
            .count()
            .toInt()
    }

    private fun violationCount(postId: UUID): Long = transaction {
        ModerationViolationTable.select(ModerationViolationTable.id)
            .where { ModerationViolationTable.postId eq postId }
            .count()
    }

    private fun notificationCount(userId: UUID): Long = transaction {
        NotificationTable.select(NotificationTable.id)
            .where { NotificationTable.userId eq userId }
            .count()
    }

    private fun auditLogCount(targetId: UUID): Long = transaction {
        AdminAuditLogTable.select(AdminAuditLogTable.id)
            .where { AdminAuditLogTable.targetId eq targetId }
            .count()
    }

    // ---------- 1. successful reconciliation commits everything ----------

    @Test
    fun `successful removal deletes the post and reconciles contribution, reward and score together`() = runTest {
        val fixture = completedChallengeFixture()

        val outcome = removalDao.removePostAtomically(fixture.firstPostId, alwaysRevoke)

        assertEquals(1, outcome.deletedRows)
        assertEquals(1, outcome.reversals.size)
        assertTrue(outcome.reversals.single().rewardRevoked, "Dropping below the threshold must revoke the reward")

        assertFalse(postExists(fixture.firstPostId), "Post row must be gone")
        assertFalse(contributionExists(fixture.firstPostId), "Its contribution must be gone")
        assertTrue(postExists(fixture.secondPostId), "The other post must be untouched")

        assertEquals(1, contributionCount(fixture.challengeId, fixture.userId))
        assertEquals(RewardState.NONE, rewardState(fixture.challengeId, fixture.userId))
        // 320 - 300 (reward revoked) - 10 (the post's own normal points) = 10
        assertEquals(normalPointsPerPost, spotScore(fixture.userId))
        assertEquals(2, ledgerEntryCount(fixture.userId), "One GRANT from the fixture plus one REVOKE")
    }

    // ---------- 2 & 3. failed reconciliation rolls the whole thing back ----------

    @Test
    fun `failed reconciliation propagates and rolls back the post deletion entirely`() = runTest {
        val fixture = completedChallengeFixture()

        val failingChallengeDao = mockk<IChallengeProgressDAO>()
        every {
            failingChallengeDao.removeContributionsForPostInCurrentTransaction(any(), any())
        } throws IllegalStateException("simulated reconciliation failure")
        val failingRemovalDao = PostRemovalDAO(failingChallengeDao, scoringDao)

        assertThrows<IllegalStateException> {
            kotlinx.coroutines.runBlocking {
                failingRemovalDao.removePostAtomically(fixture.firstPostId, alwaysRevoke)
            }
        }

        // Everything the transaction touched — post row, contribution, participant state and
        // spot_score — must read exactly as it did before the attempt.
        assertTrue(postExists(fixture.firstPostId), "Post must survive a failed removal, so it can be retried")
        assertTrue(contributionExists(fixture.firstPostId), "Its contribution must survive")
        assertTrue(postExists(fixture.secondPostId))
        assertEquals(requiredPosts, contributionCount(fixture.challengeId, fixture.userId))
        assertEquals(RewardState.GRANTED, rewardState(fixture.challengeId, fixture.userId))
        assertEquals(normalPointsPerPost * 2 + rewardPoints, spotScore(fixture.userId))
        assertEquals(1, ledgerEntryCount(fixture.userId), "Only the fixture's GRANT — no REVOKE was written")
    }

    @Test
    fun `retrying after a failed removal succeeds and leaves the same state as a first-try removal`() = runTest {
        val fixture = completedChallengeFixture()

        val failingChallengeDao = mockk<IChallengeProgressDAO>()
        every {
            failingChallengeDao.removeContributionsForPostInCurrentTransaction(any(), any())
        } throws IllegalStateException("simulated reconciliation failure")

        assertThrows<IllegalStateException> {
            kotlinx.coroutines.runBlocking {
                PostRemovalDAO(failingChallengeDao, scoringDao).removePostAtomically(fixture.firstPostId, alwaysRevoke)
            }
        }

        removalDao.removePostAtomically(fixture.firstPostId, alwaysRevoke)

        assertFalse(postExists(fixture.firstPostId))
        assertFalse(contributionExists(fixture.firstPostId))
        assertEquals(1, contributionCount(fixture.challengeId, fixture.userId))
        assertEquals(RewardState.NONE, rewardState(fixture.challengeId, fixture.userId))
        assertEquals(normalPointsPerPost, spotScore(fixture.userId))
    }

    @Test
    fun `removing a post that never contributed leaves challenge progress and reward untouched`() = runTest {
        val fixture = completedChallengeFixture()
        val unrelatedPostId = transaction {
            PostTable.insert {
                it[PostTable.userId] = fixture.userId
                it[PostTable.imageKey] = "posts/unrelated.jpg"
                it[PostTable.customBrand] = "bmw"
                it[PostTable.customModel] = "m3"
                it[PostTable.postSource] = PostSource.GALLERY.name
                it[PostTable.points] = 0
            }[PostTable.id].value
        }

        val outcome = removalDao.removePostAtomically(unrelatedPostId, alwaysRevoke)

        assertEquals(1, outcome.deletedRows)
        assertTrue(outcome.reversals.isEmpty())
        assertEquals(requiredPosts, contributionCount(fixture.challengeId, fixture.userId))
        assertEquals(RewardState.GRANTED, rewardState(fixture.challengeId, fixture.userId))
        assertEquals(normalPointsPerPost * 2 + rewardPoints, spotScore(fixture.userId))
    }

    // ---------- 5. a moderated removal's rollback must not leave an orphaned violation/notification ----------

    /**
     * When [ModerationRemoval] is passed, the violation row, the audit log entry and the "post
     * removed" notification are written in the SAME transaction as the challenge reconciliation
     * and the post delete (see PostRemovalDAO's KDoc). A violation for a post that turned out not
     * to be removed — because the transaction rolled back — would be a phantom accusation sitting
     * in the DB with no post to back it up; a notification for it would tell the user a post was
     * removed when it wasn't. This pins that a failed reconciliation rolls all of it back together.
     */
    @Test
    fun `a moderated removal's rollback leaves no violation, audit log entry, or notification behind`() = runTest {
        val fixture = completedChallengeFixture()
        val admin = CommentTestSeed.seedUser(username = "moderator")

        val failingChallengeDao = mockk<IChallengeProgressDAO>()
        every {
            failingChallengeDao.removeContributionsForPostInCurrentTransaction(any(), any())
        } throws IllegalStateException("simulated reconciliation failure")
        val failingRemovalDao = PostRemovalDAO(failingChallengeDao, scoringDao)

        assertThrows<IllegalStateException> {
            kotlinx.coroutines.runBlocking {
                failingRemovalDao.removePostAtomically(
                    fixture.firstPostId,
                    alwaysRevoke,
                    ModerationRemoval(
                        adminId = admin.userId,
                        reason = ModerationReason.SPAM_OR_MISLEADING,
                        reasonDetails = null,
                    ),
                )
            }
        }

        assertTrue(postExists(fixture.firstPostId), "Post must survive a failed removal, so it can be retried")
        assertEquals(0L, violationCount(fixture.firstPostId), "No violation must survive a rolled-back removal")
        assertEquals(0L, notificationCount(fixture.userId), "No notification must survive a rolled-back removal")
        assertEquals(0L, auditLogCount(fixture.firstPostId), "No audit log entry must survive a rolled-back removal")
    }

    @Test
    fun `a moderated removal that succeeds commits the violation, audit log entry and notification together with the delete`() = runTest {
        val fixture = completedChallengeFixture()
        val admin = CommentTestSeed.seedUser(username = "moderator")

        val outcome = removalDao.removePostAtomically(
            fixture.firstPostId,
            alwaysRevoke,
            ModerationRemoval(
                adminId = admin.userId,
                reason = ModerationReason.SPAM_OR_MISLEADING,
                reasonDetails = null,
            ),
        )

        assertFalse(postExists(fixture.firstPostId))
        assertEquals(1, outcome.deletedRows)
        assertTrue(outcome.violationId != null)
        assertEquals(1L, violationCount(fixture.firstPostId))
        assertEquals(1L, notificationCount(fixture.userId))
        assertEquals(1L, auditLogCount(fixture.firstPostId))
    }
}
