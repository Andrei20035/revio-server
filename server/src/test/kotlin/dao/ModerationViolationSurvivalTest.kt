package dao

import com.revio.server.features.challenge.ChallengeProgressDAO
import com.revio.server.features.moderation.ModerationReason
import com.revio.server.features.moderation.ModerationViolationDAO
import com.revio.server.features.moderation.ModerationViolationTable
import com.revio.server.features.post.ModerationRemoval
import com.revio.server.features.post.PostRemovalDAO
import com.revio.server.features.post.PostTable
import com.revio.server.features.scoring.ScoringDaoImpl
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import testutils.CommentTestSeed
import java.time.Instant
import testutils.TestDatabaseFactory
import java.util.UUID

/**
 * moderation_violations.post_id is deliberately NOT a foreign key to posts.id (see
 * ModerationViolationTable's KDoc): every post-referencing table cascades ON DELETE, and a
 * violation row is written in the very same transaction that deletes the post it documents. If
 * post_id were a real FK, the delete would either fail (RESTRICT) or wipe the violation it just
 * created (CASCADE) — either way the audit trail for the removal would be gone at the exact
 * moment it needs to exist. This test pins that the violation survives.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModerationViolationSurvivalTest {

    private val removalDao = PostRemovalDAO(ChallengeProgressDAO(), ScoringDaoImpl())
    private val violationDao = ModerationViolationDAO()
    private val alwaysRevoke: (Instant) -> Boolean = { true }

    @BeforeAll
    fun setup() = TestDatabaseFactory.start()

    @AfterAll
    fun tearDown() = TestDatabaseFactory.stop()

    @BeforeEach
    fun clean() = TestDatabaseFactory.cleanDatabase()

    private fun postExists(postId: UUID): Boolean = transaction {
        PostTable.select(PostTable.id).where { PostTable.id eq postId }.any()
    }

    @Test
    fun `the violation row survives the cascade that deletes its post`() = runTest {
        val alice = CommentTestSeed.seedUser(username = "alice")
        val admin = CommentTestSeed.seedUser(username = "moderator")
        val post = CommentTestSeed.seedPost(alice.userId)

        val outcome = removalDao.removePostAtomically(
            post.postId,
            alwaysRevoke,
            ModerationRemoval(
                adminId = admin.userId,
                reason = ModerationReason.SPAM_OR_MISLEADING,
                reasonDetails = null,
            ),
        )

        assertEquals(1, outcome.deletedRows)
        assertFalse(postExists(post.postId), "The post row must actually be gone")

        val violation = violationDao.findById(outcome.violationId!!)
        assertTrue(violation != null, "The violation must survive the post's own cascade delete")
        assertEquals(post.postId, violation!!.postId, "post_id is a plain UUID snapshot, not a live FK")
        assertEquals(alice.userId, violation.userId)
        assertEquals(admin.userId, violation.adminId)
        assertEquals(ModerationReason.SPAM_OR_MISLEADING, violation.reason)
        assertNull(violation.revokedAt)
    }

    @Test
    fun `the violation is queryable directly by post_id after the post is gone`() = runTest {
        val alice = CommentTestSeed.seedUser(username = "alice")
        val admin = CommentTestSeed.seedUser(username = "moderator")
        val post = CommentTestSeed.seedPost(alice.userId)

        removalDao.removePostAtomically(
            post.postId,
            alwaysRevoke,
            ModerationRemoval(admin.userId, ModerationReason.NO_CAR_CONTENT, reasonDetails = null),
        )

        val rowsByPostId = transaction {
            ModerationViolationTable.selectAll().where { ModerationViolationTable.postId eq post.postId }.count()
        }
        assertEquals(1L, rowsByPostId)
    }
}
