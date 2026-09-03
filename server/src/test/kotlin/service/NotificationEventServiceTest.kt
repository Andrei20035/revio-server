package service

import com.revio.server.features.notification.NotificationCategory
import com.revio.server.features.notification.NotificationEventService
import com.revio.server.features.notification.NotificationPushState
import com.revio.server.features.notification.NotificationTable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import testutils.ChallengeTestSeed
import testutils.TestDatabaseFactory
import testutils.UserTestSeed
import java.time.Instant
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationEventServiceTest {

    private val service = NotificationEventService()

    @BeforeAll
    fun setup() {
        TestDatabaseFactory.start()
    }

    @AfterAll
    fun tearDown() {
        TestDatabaseFactory.stop()
    }

    @BeforeEach
    fun clean() {
        TestDatabaseFactory.cleanDatabase()
    }

    private fun seedUser(email: String = "recipient@example.com", username: String = "recipient") =
        UserTestSeed.seedUser(UserTestSeed.seedAuthCredential(email).authCredentialId, username = username)

    private fun rowsFor(userId: UUID, dedupeKey: String) = transaction {
        NotificationTable
            .selectAll()
            .where { (NotificationTable.userId eq userId) and (NotificationTable.dedupeKey eq dedupeKey) }
            .toList()
    }

    @Test
    fun `recording the same dedupe key 10 times aggregates into a single row with actor_count 10`() {
        val recipientId = seedUser()
        val actorId = seedUser("actor@example.com", "actor")
        val dedupeKey = "like:post-123:window-1"

        transaction {
            repeat(10) { i ->
                service.record(
                    recipientId = recipientId,
                    category = NotificationCategory.LIKES,
                    dedupeKey = dedupeKey,
                    actorId = actorId,
                    actorUsername = "actor",
                    title = "Alex liked your spot",
                    body = "",
                )
            }
        }

        val rows = rowsFor(recipientId, dedupeKey)
        assertEquals(1, rows.size)
        assertEquals(10, rows.single()[NotificationTable.actorCount])
    }

    @Test
    fun `rolling back the caller's transaction discards the recorded event`() {
        val recipientId = seedUser()
        val actorId = seedUser("actor2@example.com", "actor2")
        val dedupeKey = "like:post-456:window-1"

        runCatching {
            transaction {
                service.record(
                    recipientId = recipientId,
                    category = NotificationCategory.LIKES,
                    dedupeKey = dedupeKey,
                    actorId = actorId,
                    actorUsername = "actor2",
                    title = "Alex liked your spot",
                    body = "",
                )
                error("force rollback")
            }
        }

        val rows = rowsFor(recipientId, dedupeKey)
        assertEquals(0, rows.size)
    }

    // ---------- record() challengeId (push-notifications plan, "challenge is live" work) ----------

    private fun seedChallenge(): UUID {
        val familyId = ChallengeTestSeed.seedFamily()
        return ChallengeTestSeed.seedChallenge(
            familyId = familyId,
            startsAt = Instant.now(),
            endsAt = Instant.now().plusSeconds(3600),
        )
    }

    @Test
    fun `record with a challengeId persists it on the row`() {
        val recipientId = seedUser()
        val challengeId = seedChallenge()
        val dedupeKey = "challenge_started:$challengeId"

        service.record(
            recipientId = recipientId,
            category = NotificationCategory.CHALLENGES,
            dedupeKey = dedupeKey,
            actorId = null,
            actorUsername = null,
            challengeId = challengeId,
            title = "New challenge is live",
            body = "Tap to see the details and start spotting.",
        )

        val rows = rowsFor(recipientId, dedupeKey)
        assertEquals(1, rows.size)
        assertEquals(challengeId, rows.single()[NotificationTable.challengeId]?.value)
    }

    @Test
    fun `record without a challengeId leaves the column null`() {
        val recipientId = seedUser()
        val actorId = seedUser("actor3@example.com", "actor3")
        val dedupeKey = "like:post-789:window-1"

        service.record(
            recipientId = recipientId,
            category = NotificationCategory.LIKES,
            dedupeKey = dedupeKey,
            actorId = actorId,
            actorUsername = "actor3",
            title = "Alex liked your spot",
            body = "",
        )

        val rows = rowsFor(recipientId, dedupeKey)
        assertEquals(1, rows.size)
        assertEquals(null, rows.single()[NotificationTable.challengeId])
    }

    // ---------- recordBroadcast() (push-notifications plan, "challenge is live" work) ----------

    @Test
    fun `recordBroadcast called twice with the same dedupe key inserts only one row and never rewrites it`() {
        val recipientId = seedUser()
        val challengeId = seedChallenge()
        val dedupeKey = "challenge_started:$challengeId"

        val firstId = service.recordBroadcast(
            recipientId = recipientId,
            category = NotificationCategory.CHALLENGES,
            dedupeKey = dedupeKey,
            challengeId = challengeId,
            title = "New challenge is live",
            body = "Tap to see the details and start spotting.",
        )
        val secondId = service.recordBroadcast(
            recipientId = recipientId,
            category = NotificationCategory.CHALLENGES,
            dedupeKey = dedupeKey,
            challengeId = challengeId,
            title = "New challenge is live",
            body = "Tap to see the details and start spotting.",
        )

        assertEquals(firstId, secondId)

        val rows = rowsFor(recipientId, dedupeKey)
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(1, row[NotificationTable.actorCount])
        assertEquals("New challenge is live", row[NotificationTable.title])
        assertEquals("Tap to see the details and start spotting.", row[NotificationTable.body])
    }

    // ---------- withdrawCommentActor (plan §18, step 4.4) ----------

    private fun setPushState(userId: UUID, dedupeKey: String, state: NotificationPushState) = transaction {
        NotificationTable.update({ (NotificationTable.userId eq userId) and (NotificationTable.dedupeKey eq dedupeKey) }) {
            it[NotificationTable.pushState] = state
        }
    }

    @Test
    fun `withdrawing one of several actors decrements actor_count without deleting the row`() {
        val recipientId = seedUser()
        val alice = seedUser("alice@example.com", "alice")
        val bob = seedUser("bob@example.com", "bob")
        val dedupeKey = "comment:post-1:window-1"

        service.recordComment(recipientId, dedupeKey, alice, "alice", null, null)
        service.recordComment(recipientId, dedupeKey, bob, "bob", null, null)

        service.withdrawCommentActor(recipientId, dedupeKey)

        val rows = rowsFor(recipientId, dedupeKey)
        assertEquals(1, rows.size)
        assertEquals(1, rows.single()[NotificationTable.actorCount])
    }

    @Test
    fun `withdrawing the only actor before dispatch cancels (deletes) the row`() {
        val recipientId = seedUser()
        val alice = seedUser("alice2@example.com", "alice2")
        val dedupeKey = "comment:post-2:window-1"

        service.recordComment(recipientId, dedupeKey, alice, "alice2", null, null)
        // push_state defaults to NOT_SENT — this is "before dispatch".

        service.withdrawCommentActor(recipientId, dedupeKey)

        assertEquals(0, rowsFor(recipientId, dedupeKey).size)
    }

    @Test
    fun `withdrawing the only actor after dispatch only updates the row, never deletes it`() {
        val recipientId = seedUser()
        val alice = seedUser("alice3@example.com", "alice3")
        val dedupeKey = "comment:post-3:window-1"

        service.recordComment(recipientId, dedupeKey, alice, "alice3", null, null)
        setPushState(recipientId, dedupeKey, NotificationPushState.SENT)

        service.withdrawCommentActor(recipientId, dedupeKey)

        val rows = rowsFor(recipientId, dedupeKey)
        assertEquals(1, rows.size, "the row must survive once it has been dispatched, even at actor_count 0")
        assertEquals(0, rows.single()[NotificationTable.actorCount])
    }

    @Test
    fun `withdrawing from a dedupe key with no row is a no-op`() {
        val recipientId = seedUser()

        service.withdrawCommentActor(recipientId, "comment:post-404:window-1")

        assertEquals(0, rowsFor(recipientId, "comment:post-404:window-1").size)
    }

    // ---------- withdrawLikeActor (plan §18, step 5.2) ----------

    @Test
    fun `withdrawLikeActor decrements actor_count without deleting the row when other actors remain`() {
        val recipientId = seedUser()
        val alice = seedUser("like-alice@example.com", "like-alice")
        val bob = seedUser("like-bob@example.com", "like-bob")
        val dedupeKey = "like:post-1:window-1"

        transaction {
            service.record(recipientId, NotificationCategory.LIKES, dedupeKey, alice, "like-alice", title = "t", body = "")
            service.record(recipientId, NotificationCategory.LIKES, dedupeKey, bob, "like-bob", title = "t", body = "")
        }

        service.withdrawLikeActor(recipientId, dedupeKey)

        val rows = rowsFor(recipientId, dedupeKey)
        assertEquals(1, rows.size)
        assertEquals(1, rows.single()[NotificationTable.actorCount])
    }

    @Test
    fun `withdrawLikeActor withdrawing the only actor before dispatch cancels (deletes) the row`() {
        val recipientId = seedUser()
        val alice = seedUser("like-alice2@example.com", "like-alice2")
        val dedupeKey = "like:post-2:window-1"

        transaction {
            service.record(recipientId, NotificationCategory.LIKES, dedupeKey, alice, "like-alice2", title = "t", body = "")
        }
        // push_state defaults to NOT_SENT — this is "before dispatch".

        service.withdrawLikeActor(recipientId, dedupeKey)

        assertEquals(0, rowsFor(recipientId, dedupeKey).size)
    }

    @Test
    fun `withdrawLikeActor withdrawing the only actor after dispatch only updates the row, never deletes it`() {
        val recipientId = seedUser()
        val alice = seedUser("like-alice3@example.com", "like-alice3")
        val dedupeKey = "like:post-3:window-1"

        transaction {
            service.record(recipientId, NotificationCategory.LIKES, dedupeKey, alice, "like-alice3", title = "t", body = "")
        }
        setPushState(recipientId, dedupeKey, NotificationPushState.SENT)

        service.withdrawLikeActor(recipientId, dedupeKey)

        val rows = rowsFor(recipientId, dedupeKey)
        assertEquals(1, rows.size, "the row must survive once it has been dispatched, even at actor_count 0")
        assertEquals(0, rows.single()[NotificationTable.actorCount])
    }

    @Test
    fun `withdrawLikeActor from a dedupe key with no row is a no-op`() {
        val recipientId = seedUser()

        service.withdrawLikeActor(recipientId, "like:post-404:window-1")

        assertEquals(0, rowsFor(recipientId, "like:post-404:window-1").size)
    }

    // ---------- recordLike copy thresholds (plan §8.1 / §18, step 5.3) ----------

    @Test
    fun `recordLike with 1 actor names them and uses singular liked-copy`() {
        val recipientId = seedUser()
        val alice = seedUser("copy-alice@example.com", "copy-alice")
        val dedupeKey = "like:post-copy-1:window-1"

        service.recordLike(recipientId, dedupeKey, alice, "copy-alice")

        val row = rowsFor(recipientId, dedupeKey).single()
        assertEquals("copy-alice liked your spot", row[NotificationTable.title])
        assertEquals("", row[NotificationTable.body])
    }

    @Test
    fun `recordLike with 3 actors renders 'name and 2 others'`() {
        val recipientId = seedUser()
        val alice = seedUser("copy-alice2@example.com", "copy-alice2")
        val bob = seedUser("copy-bob2@example.com", "copy-bob2")
        val carol = seedUser("copy-carol2@example.com", "copy-carol2")
        val dedupeKey = "like:post-copy-2:window-1"

        service.recordLike(recipientId, dedupeKey, alice, "copy-alice2")
        service.recordLike(recipientId, dedupeKey, bob, "copy-bob2")
        service.recordLike(recipientId, dedupeKey, carol, "copy-carol2")

        val row = rowsFor(recipientId, dedupeKey).single()
        assertEquals(3, row[NotificationTable.actorCount])
        assertEquals("copy-carol2 and 2 others liked your spot", row[NotificationTable.title])
    }

    @Test
    fun `recordLike with 2 actors uses singular 'other'`() {
        val recipientId = seedUser()
        val alice = seedUser("copy-alice2b@example.com", "copy-alice2b")
        val bob = seedUser("copy-bob2b@example.com", "copy-bob2b")
        val dedupeKey = "like:post-copy-2b:window-1"

        service.recordLike(recipientId, dedupeKey, alice, "copy-alice2b")
        service.recordLike(recipientId, dedupeKey, bob, "copy-bob2b")

        val row = rowsFor(recipientId, dedupeKey).single()
        assertEquals("copy-bob2b and 1 other liked your spot", row[NotificationTable.title])
    }

    @Test
    fun `recordLike with 12 actors renders volume copy`() {
        val recipientId = seedUser()
        val dedupeKey = "like:post-copy-12:window-1"

        repeat(12) { i ->
            val actor = seedUser("copy-actor$i@example.com", "copy-actor$i")
            service.recordLike(recipientId, dedupeKey, actor, "copy-actor$i")
        }

        val row = rowsFor(recipientId, dedupeKey).single()
        assertEquals(12, row[NotificationTable.actorCount])
        assertEquals("Your spot is getting noticed", row[NotificationTable.title])
        assertEquals("12 new likes since you posted.", row[NotificationTable.body])
    }

    @Test
    fun `recordLike with no username falls back to Someone`() {
        val recipientId = seedUser()
        val alice = seedUser("copy-alice3@example.com", "copy-alice3")
        val dedupeKey = "like:post-copy-3:window-1"

        service.recordLike(recipientId, dedupeKey, alice, null)

        val row = rowsFor(recipientId, dedupeKey).single()
        assertEquals("Someone liked your spot", row[NotificationTable.title])
    }

    @Test
    fun `withdrawLikeActor re-renders copy across the 4-actor volume threshold as the count drops`() {
        val recipientId = seedUser()
        val dedupeKey = "like:post-copy-4:window-1"
        val actors = (0 until 4).map { i -> seedUser("copy-w$i@example.com", "copy-w$i") to "copy-w$i" }
        actors.forEach { (id, username) -> service.recordLike(recipientId, dedupeKey, id, username) }

        var row = rowsFor(recipientId, dedupeKey).single()
        assertEquals("Your spot is getting noticed", row[NotificationTable.title])
        assertEquals(4, row[NotificationTable.actorCount])

        service.withdrawLikeActor(recipientId, dedupeKey)

        row = rowsFor(recipientId, dedupeKey).single()
        assertEquals(3, row[NotificationTable.actorCount])
        assertEquals("copy-w3 and 2 others liked your spot", row[NotificationTable.title])
    }
}
