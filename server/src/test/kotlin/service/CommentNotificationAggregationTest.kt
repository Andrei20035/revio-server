package service

import com.revio.server.core.storage.LocalImageStorageService
import com.revio.server.features.notification.NotificationEventService
import com.revio.server.features.notification.NotificationOutboxDAO
import com.revio.server.features.notification.NotificationPushState
import com.revio.server.features.notification.NotificationTable
import com.revio.server.features.notification.UserDeviceDAO
import com.revio.server.features.notification.UserNotificationPrefsDAO
import com.revio.server.features.post.PostDAO
import com.revio.server.features.scoring.IScoringService
import features.comment.CommentDAO
import features.comment.CommentService
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import testutils.CommentTestSeed
import testutils.TestDatabaseFactory
import java.nio.file.Path
import java.util.UUID

/**
 * Real end-to-end coverage for the comment notification aggregation window (plan §18, step 4.2):
 * CommentService + real CommentDAO/PostDAO against Testcontainers Postgres, real
 * NotificationEventService, scoring mocked out (irrelevant to aggregation). Exercises the two
 * "same window" scenarios from the step's acceptance criteria naturally, in real time (all
 * comments land well within the same 15-minute bucket during a fast test run) — see
 * CommentServiceWindowTest.kt for the pure floor-math boundary test covering "a comment 16
 * minutes later opens a new window", which cannot be simulated with a real wall-clock wait here.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommentNotificationAggregationTest {

    private val commentDao = CommentDAO()
    private val postDao = PostDAO()
    private val scoringService = mockk<IScoringService>(relaxed = true)
    private val notificationEventService = NotificationEventService()
    private val notificationPrefsDao = UserNotificationPrefsDAO()
    private val userDeviceDao = UserDeviceDAO()
    private val notificationOutboxDao = NotificationOutboxDAO()
    private val storage = LocalImageStorageService(Path.of("/tmp/comment-aggregation-test-uploads"), "http://localhost:8080")

    private val service = CommentService(
        commentDao, storage, postDao, scoringService, notificationEventService,
        notificationPrefsDao, userDeviceDao, notificationOutboxDao,
        commentsPushEnabledProvider = { null },
    )

    @BeforeAll
    fun setup() = TestDatabaseFactory.start()

    @AfterAll
    fun tearDown() = TestDatabaseFactory.stop()

    @BeforeEach
    fun clean() = TestDatabaseFactory.cleanDatabase()

    private fun notificationRowsFor(ownerId: UUID) = transaction {
        NotificationTable
            .selectAll()
            .where { NotificationTable.userId eq ownerId }
            .toList()
    }

    @Test
    fun `5 comments from 3 distinct users aggregate into 1 row with 3 actors`() = runTest {
        val owner = CommentTestSeed.seedUser("owner1")
        val post = CommentTestSeed.seedPost(owner.userId)
        val alice = CommentTestSeed.seedUser("alice1")
        val bob = CommentTestSeed.seedUser("bob1")
        val carol = CommentTestSeed.seedUser("carol1")

        service.addComment(alice.userId, post.postId, "1")
        service.addComment(bob.userId, post.postId, "2")
        service.addComment(alice.userId, post.postId, "3")
        service.addComment(carol.userId, post.postId, "4")
        service.addComment(bob.userId, post.postId, "5")

        val rows = notificationRowsFor(owner.userId)
        assertEquals(1, rows.size)
        assertEquals(3, rows.single()[NotificationTable.actorCount])
    }

    @Test
    fun `3 comments from the same user count as 1 actor`() = runTest {
        val owner = CommentTestSeed.seedUser("owner2")
        val post = CommentTestSeed.seedPost(owner.userId)
        val alice = CommentTestSeed.seedUser("alice2")

        service.addComment(alice.userId, post.postId, "1")
        service.addComment(alice.userId, post.postId, "2")
        service.addComment(alice.userId, post.postId, "3")

        val rows = notificationRowsFor(owner.userId)
        assertEquals(1, rows.size)
        assertEquals(1, rows.single()[NotificationTable.actorCount])
    }

    @Test
    fun `self-comments never create or contribute to a notification row`() = runTest {
        val owner = CommentTestSeed.seedUser("owner3")
        val post = CommentTestSeed.seedPost(owner.userId)

        service.addComment(owner.userId, post.postId, "commenting on my own spot")

        assertEquals(0, notificationRowsFor(owner.userId).size)
    }

    // ---------- copy rendering thresholds, real actor counts end-to-end (plan §18, step 4.3) ----------

    @Test
    fun `1 actor renders the single-commenter title`() = runTest {
        val owner = CommentTestSeed.seedUser("owner4")
        val post = CommentTestSeed.seedPost(owner.userId)
        val alice = CommentTestSeed.seedUser("alice4")

        service.addComment(alice.userId, post.postId, "hi")

        val row = notificationRowsFor(owner.userId).single()
        assertEquals("alice4 commented on your spot", row[NotificationTable.title])
        assertEquals("", row[NotificationTable.body])
    }

    @Test
    fun `5th distinct actor flips the row to the volume title with a body`() = runTest {
        val owner = CommentTestSeed.seedUser("owner5")
        val post = CommentTestSeed.seedPost(owner.userId)
        val commenters = (1..5).map { CommentTestSeed.seedUser("commenter5_$it") }

        commenters.forEach { service.addComment(it.userId, post.postId, "hi") }

        val row = notificationRowsFor(owner.userId).single()
        assertEquals(5, row[NotificationTable.actorCount])
        assertEquals("Your spot has a conversation going", row[NotificationTable.title])
        assertEquals("5 people commented.", row[NotificationTable.body])
    }

    @Test
    fun `D7 - the comment's own text never appears in the notification row`() = runTest {
        val owner = CommentTestSeed.seedUser("owner6")
        val post = CommentTestSeed.seedPost(owner.userId)
        val alice = CommentTestSeed.seedUser("alice6")
        val secretText = "xXSuperSecretHarassingCommentTextXx"

        service.addComment(alice.userId, post.postId, secretText)

        val row = notificationRowsFor(owner.userId).single()
        assertFalse(row[NotificationTable.title].contains(secretText))
        assertFalse(row[NotificationTable.body].contains(secretText))
        assertFalse(row[NotificationTable.dedupeKey]!!.contains(secretText))
        assertFalse((row[NotificationTable.deepLink] ?: "").contains(secretText))
    }

    // ---------- cancellation on comment deletion (plan §18, step 4.4) ----------

    private fun setPushState(ownerId: UUID, state: NotificationPushState) = transaction {
        NotificationTable.update({ NotificationTable.userId eq ownerId }) {
            it[NotificationTable.pushState] = state
        }
    }

    @Test
    fun `deleting a comment before dispatch removes that actor and decrements actor_count`() = runTest {
        val owner = CommentTestSeed.seedUser("owner7")
        val post = CommentTestSeed.seedPost(owner.userId)
        val alice = CommentTestSeed.seedUser("alice7")
        val bob = CommentTestSeed.seedUser("bob7")

        val aliceComment = service.addComment(alice.userId, post.postId, "hi")
        service.addComment(bob.userId, post.postId, "hi")
        // push_state defaults to NOT_SENT — "before dispatch".

        service.deleteComment(aliceComment.id, alice.userId)

        val row = notificationRowsFor(owner.userId).single()
        assertEquals(1, row[NotificationTable.actorCount])
    }

    @Test
    fun `deleting the only comment before dispatch cancels (deletes) the notification row`() = runTest {
        val owner = CommentTestSeed.seedUser("owner8")
        val post = CommentTestSeed.seedPost(owner.userId)
        val alice = CommentTestSeed.seedUser("alice8")

        val aliceComment = service.addComment(alice.userId, post.postId, "hi")

        service.deleteComment(aliceComment.id, alice.userId)

        assertEquals(0, notificationRowsFor(owner.userId).size)
    }

    @Test
    fun `deleting the only comment after dispatch only updates the inbox row, never deletes it`() = runTest {
        val owner = CommentTestSeed.seedUser("owner9")
        val post = CommentTestSeed.seedPost(owner.userId)
        val alice = CommentTestSeed.seedUser("alice9")

        val aliceComment = service.addComment(alice.userId, post.postId, "hi")
        setPushState(owner.userId, NotificationPushState.SENT)

        service.deleteComment(aliceComment.id, alice.userId)

        val rows = notificationRowsFor(owner.userId)
        assertEquals(1, rows.size, "the row must survive deletion after dispatch")
        assertEquals(0, rows.single()[NotificationTable.actorCount])
    }

    @Test
    fun `deleting one of several comments from the same user within the window leaves the actor counted`() = runTest {
        val owner = CommentTestSeed.seedUser("owner10")
        val post = CommentTestSeed.seedPost(owner.userId)
        val alice = CommentTestSeed.seedUser("alice10")

        val first = service.addComment(alice.userId, post.postId, "1")
        service.addComment(alice.userId, post.postId, "2")

        service.deleteComment(first.id, alice.userId)

        val row = notificationRowsFor(owner.userId).single()
        assertEquals(1, row[NotificationTable.actorCount], "alice still has her second comment in the window")
    }
}
