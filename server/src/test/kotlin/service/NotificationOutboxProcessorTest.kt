package service

import com.revio.server.config.NotificationMetrics
import com.revio.server.features.notification.DevicePlatform
import com.revio.server.features.notification.FcmPriority
import com.revio.server.features.notification.FcmSendResult
import com.revio.server.features.notification.FcmTerminalReason
import com.revio.server.features.notification.FirebaseProject
import com.revio.server.features.notification.IPushDispatchService
import com.revio.server.features.notification.NotificationCategory
import com.revio.server.features.notification.NotificationEventService
import com.revio.server.features.notification.NotificationOutboxDAO
import com.revio.server.features.notification.NotificationOutboxProcessor
import com.revio.server.features.notification.NotificationOutboxTable
import com.revio.server.features.notification.NotificationTable
import com.revio.server.features.notification.NotificationTargetType
import com.revio.server.features.notification.OutboxState
import com.revio.server.features.notification.UserDeviceDAO
import com.revio.server.features.leaderboard.LeaderboardDAO
import com.revio.server.features.leaderboard.LeaderboardDeltaDAO
import com.revio.server.features.leaderboard.LeaderboardDeltaService
import com.revio.server.features.user.UserTable
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import testutils.ChallengeTestSeed
import testutils.TestDatabaseFactory
import testutils.UserTestSeed
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * NotificationOutboxProcessor (plan §18, step 3.6) — the outbox's actual send-with-retry logic
 * that PushDispatcherLoop (step 3.5) delegates to. Real Testcontainers Postgres (needs real
 * notification/device/outbox rows), fake IPushDispatchService (no real FCM/network).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationOutboxProcessorTest {

    private val outboxDao = NotificationOutboxDAO()
    private val deviceDao = UserDeviceDAO()
    private val eventService = NotificationEventService()
    private val leaderboardDao = LeaderboardDAO()
    private val leaderboardDeltaService = LeaderboardDeltaService(leaderboardDao, LeaderboardDeltaDAO())

    @BeforeAll
    fun setup() = TestDatabaseFactory.start()

    @AfterAll
    fun tearDown() = TestDatabaseFactory.stop()

    @BeforeEach
    fun clean() = TestDatabaseFactory.cleanDatabase()

    private class FakePushDispatchService(
        private val resultFor: (callIndex: Int, fcmToken: String) -> FcmSendResult,
    ) : IPushDispatchService {
        val calls = mutableListOf<String>()
        val ttlSecondsByCall = mutableListOf<Long?>()
        val dataByCall = mutableListOf<Map<String, String>>()
        val priorityByCall = mutableListOf<FcmPriority>()

        override suspend fun send(
            project: FirebaseProject,
            fcmToken: String,
            title: String,
            body: String,
            data: Map<String, String>,
            priority: FcmPriority,
            ttlSeconds: Long?,
        ): FcmSendResult {
            val index = calls.size
            calls.add(fcmToken)
            ttlSecondsByCall.add(ttlSeconds)
            dataByCall.add(data)
            priorityByCall.add(priority)
            return resultFor(index, fcmToken)
        }
    }

    private fun seedUser(email: String, username: String) =
        UserTestSeed.seedUser(UserTestSeed.seedAuthCredential(email).authCredentialId, username = username)

    private suspend fun seedDevice(userId: UUID, deviceId: String, fcmToken: String) {
        deviceDao.registerDevice(
            userId = userId,
            deviceId = deviceId,
            fcmToken = fcmToken,
            firebaseProject = FirebaseProject.DEBUG,
            platform = DevicePlatform.ANDROID,
            appVersion = "1.0.0",
            timezone = null,
            locale = null,
        )
    }

    private fun seedNotification(userId: UUID, dedupeKey: String) = transaction {
        eventService.record(
            recipientId = userId,
            category = NotificationCategory.LIKES,
            dedupeKey = dedupeKey,
            actorId = null,
            actorUsername = "alex",
            title = "Alex liked your spot",
            body = "",
        )
    }

    /** @return the seeded challenge's id, and the CHALLENGES notification recorded for it, in that order. */
    private fun seedChallengeNotification(userId: UUID, dedupeKey: String): Pair<UUID, UUID> {
        val familyId = ChallengeTestSeed.seedFamily()
        val challengeId = ChallengeTestSeed.seedChallenge(
            familyId = familyId,
            startsAt = Instant.now().minusSeconds(3600),
            endsAt = Instant.now().plusSeconds(3600),
        )
        val notificationId = transaction {
            eventService.recordBroadcast(
                recipientId = userId,
                category = NotificationCategory.CHALLENGES,
                dedupeKey = dedupeKey,
                targetType = NotificationTargetType.CHALLENGE,
                challengeId = challengeId,
                title = "🏁 New challenge is live",
                body = "Tap to see the details and start spotting.",
                deepLink = "challenge",
            )
        }
        return challengeId to notificationId
    }

    private fun outboxRow(id: UUID) = transaction {
        NotificationOutboxTable.selectAll().where { NotificationOutboxTable.id eq id }.single()
    }

    private fun notificationRow(id: UUID) = transaction {
        NotificationTable.selectAll().where { NotificationTable.id eq id }.single()
    }

    private fun forceDueNow(outboxId: UUID) = transaction {
        NotificationOutboxTable.update({ NotificationOutboxTable.id eq outboxId }) {
            it[NotificationOutboxTable.nextAttemptAt] = Instant.now().atOffset(ZoneOffset.UTC).minusSeconds(1)
        }
    }

    @Test
    fun `a 5xx failure followed by a retry that succeeds ends up delivered`() = runTest {
        val userId = seedUser("owner1@example.com", "owner1")
        seedDevice(userId, "device-1", "token-1")
        val notificationId = seedNotification(userId, "like:post-1:w1")
        outboxDao.enqueue(notificationId, deviceDao.findByUserAndDevice(userId, "device-1")!!.id)
        val outboxId = outboxDao.find(notificationId, deviceDao.findByUserAndDevice(userId, "device-1")!!.id)!!.id

        val fake = FakePushDispatchService { index, _ ->
            if (index == 0) FcmSendResult.Retriable(500) else FcmSendResult.Accepted("msg-delivered")
        }
        val processor = NotificationOutboxProcessor(outboxDao, deviceDao, fake, leaderboardDao, leaderboardDeltaService)

        processor.processDueBatch()
        val afterFirstAttempt = outboxRow(outboxId)
        assertEquals(OutboxState.FAILED, afterFirstAttempt[NotificationOutboxTable.state])
        assertEquals(1, afterFirstAttempt[NotificationOutboxTable.attempts])

        // Force the scheduled backoff to be due now, simulating the next dispatcher tick.
        forceDueNow(outboxId)
        processor.processDueBatch()

        val afterRetry = outboxRow(outboxId)
        assertEquals(OutboxState.ACCEPTED, afterRetry[NotificationOutboxTable.state])
        assertEquals("msg-delivered", afterRetry[NotificationOutboxTable.fcmMessageId])
        assertEquals(2, fake.calls.size)
    }

    @Test
    fun `an UNREGISTERED terminal error deactivates the device and never retries`() = runTest {
        val userId = seedUser("owner2@example.com", "owner2")
        seedDevice(userId, "device-2", "token-2")
        val notificationId = seedNotification(userId, "like:post-2:w1")
        val device = deviceDao.findByUserAndDevice(userId, "device-2")!!
        outboxDao.enqueue(notificationId, device.id)
        val outboxId = outboxDao.find(notificationId, device.id)!!.id

        val fake = FakePushDispatchService { _, _ -> FcmSendResult.Terminal(FcmTerminalReason.UNREGISTERED) }
        val processor = NotificationOutboxProcessor(outboxDao, deviceDao, fake, leaderboardDao, leaderboardDeltaService)

        processor.processDueBatch()

        val row = outboxRow(outboxId)
        assertEquals(OutboxState.DEAD, row[NotificationOutboxTable.state])
        assertEquals("UNREGISTERED", row[NotificationOutboxTable.lastErrorCode])

        val deactivatedDevice = deviceDao.findById(device.id)
        assertFalse(deactivatedDevice!!.isActive)
        assertNull(deactivatedDevice.fcmToken)

        // Zero retries: a second batch must not call FCM again — the row is no longer PENDING/FAILED.
        processor.processDueBatch()
        assertEquals(1, fake.calls.size)
    }

    @Test
    fun `an already-expired event is dropped without ever calling FCM`() = runTest {
        val userId = seedUser("owner3@example.com", "owner3")
        seedDevice(userId, "device-3", "token-3")
        val notificationId = seedNotification(userId, "like:post-3:w1")
        val device = deviceDao.findByUserAndDevice(userId, "device-3")!!
        outboxDao.enqueue(
            notificationId,
            device.id,
            expiresAt = Instant.now().atOffset(ZoneOffset.UTC).minusHours(1),
        )
        val outboxId = outboxDao.find(notificationId, device.id)!!.id

        val fake = FakePushDispatchService { _, _ -> FcmSendResult.Accepted("should-not-be-used") }
        val processor = NotificationOutboxProcessor(outboxDao, deviceDao, fake, leaderboardDao, leaderboardDeltaService)

        processor.processDueBatch()

        val row = outboxRow(outboxId)
        assertEquals(OutboxState.DROPPED, row[NotificationOutboxTable.state])
        assertTrue(fake.calls.isEmpty(), "FCM must never be called for an already-expired event")
    }

    // ── step 4.3 — LikeService/DiscoveryJob now send a real expiresAt at enqueue time ────────

    @Test
    fun `an already-expired event increments the outbox dropped-expired metric`() = runTest {
        val userId = seedUser("owner-ttl-metric@example.com", "owner_ttl_metric")
        seedDevice(userId, "device-ttl-metric", "token-ttl-metric")
        val notificationId = seedNotification(userId, "like:post-ttl-metric:w1")
        val device = deviceDao.findByUserAndDevice(userId, "device-ttl-metric")!!
        outboxDao.enqueue(
            notificationId,
            device.id,
            expiresAt = Instant.now().atOffset(ZoneOffset.UTC).minusHours(1),
        )

        val before = NotificationMetrics.snapshot().outboxDroppedExpired
        val fake = FakePushDispatchService { _, _ -> FcmSendResult.Accepted("should-not-be-used") }
        val processor = NotificationOutboxProcessor(outboxDao, deviceDao, fake, leaderboardDao, leaderboardDeltaService)

        processor.processDueBatch()

        assertEquals(before + 1, NotificationMetrics.snapshot().outboxDroppedExpired)
    }

    @Test
    fun `a row with a future expiresAt is sent with a matching android_ttl`() = runTest {
        val userId = seedUser("owner-ttl-valid@example.com", "owner_ttl_valid")
        seedDevice(userId, "device-ttl-valid", "token-ttl-valid")
        val notificationId = seedNotification(userId, "like:post-ttl-valid:w1")
        val device = deviceDao.findByUserAndDevice(userId, "device-ttl-valid")!!
        // Mirrors LikeService's LIKE_NOTIFICATION_FRESHNESS_HOURS (6h) freshness TTL.
        outboxDao.enqueue(
            notificationId,
            device.id,
            expiresAt = Instant.now().atOffset(ZoneOffset.UTC).plusHours(6),
        )

        val fake = FakePushDispatchService { _, _ -> FcmSendResult.Accepted("msg-ttl-valid") }
        val processor = NotificationOutboxProcessor(outboxDao, deviceDao, fake, leaderboardDao, leaderboardDeltaService)

        processor.processDueBatch()

        assertEquals(1, fake.calls.size)
        val ttlSeconds = fake.ttlSecondsByCall.single()
        assertTrue(ttlSeconds != null, "expected android.ttl to be set for a row with a future expiresAt")
        // ~6h in seconds, allowing slack for the test's own execution time.
        assertTrue(ttlSeconds!! in (6L * 3600 - 60)..(6L * 3600), "expected ~6h of ttl, was ${ttlSeconds}s")

        val row = outboxRow(outboxDao.find(notificationId, device.id)!!.id)
        assertEquals(OutboxState.ACCEPTED, row[NotificationOutboxTable.state])
    }

    @Test
    fun `100 distinct events produce exactly 100 sends with zero duplicates`() = runTest {
        val outboxIds = (1..100).map { i ->
            val userId = seedUser("owner-batch-$i@example.com", "owner_batch_$i")
            seedDevice(userId, "device-$i", "token-$i")
            val notificationId = seedNotification(userId, "like:post-$i:w1")
            val device = deviceDao.findByUserAndDevice(userId, "device-$i")!!
            outboxDao.enqueue(notificationId, device.id)
            outboxDao.find(notificationId, device.id)!!.id
        }

        val fake = FakePushDispatchService { index, _ -> FcmSendResult.Accepted("msg-$index") }
        val processor = NotificationOutboxProcessor(outboxDao, deviceDao, fake, leaderboardDao, leaderboardDeltaService)

        processor.processDueBatch(limit = 200)

        assertEquals(100, fake.calls.size)
        assertEquals(100, fake.calls.distinct().size, "no device should have been sent to twice")

        val acceptedCount = outboxIds.count { id -> outboxRow(id)[NotificationOutboxTable.state] == OutboxState.ACCEPTED }
        assertEquals(100, acceptedCount)
    }

    // ---------- backlog collapsing (plan §14 / §18, step 5.5) ----------

    @Test
    fun `4 due events for the same user and device collapse into a single FCM send`() = runTest {
        val userId = seedUser("collapse1@example.com", "collapse1")
        seedDevice(userId, "device-c1", "token-c1")
        val deviceId = deviceDao.findByUserAndDevice(userId, "device-c1")!!.id

        val notificationIds = (1..4).map { i ->
            val notificationId = seedNotification(userId, "like:post-c1-$i:w1")
            outboxDao.enqueue(notificationId, deviceId)
            notificationId
        }

        val fake = FakePushDispatchService { index, _ -> FcmSendResult.Accepted("msg-collapsed-$index") }
        val processor = NotificationOutboxProcessor(outboxDao, deviceDao, fake, leaderboardDao, leaderboardDeltaService)

        processor.processDueBatch()

        assertEquals(1, fake.calls.size, "4 deferred events for the same (user, device) must produce exactly 1 push")

        val outboxIds = notificationIds.map { outboxDao.find(it, deviceId)!!.id }
        outboxIds.forEach { id ->
            val row = outboxRow(id)
            assertEquals(OutboxState.ACCEPTED, row[NotificationOutboxTable.state])
            assertEquals("msg-collapsed-0", row[NotificationOutboxTable.fcmMessageId])
        }

        // The 4 underlying user_notifications (inbox) rows are untouched — still 4 separate rows,
        // each with its own original title.
        notificationIds.forEach { id ->
            val row = notificationRow(id)
            assertEquals("Alex liked your spot", row[NotificationTable.title])
        }
    }

    @Test
    fun `two different devices for the same user are never collapsed together`() = runTest {
        val userId = seedUser("collapse2@example.com", "collapse2")
        seedDevice(userId, "device-c2a", "token-c2a")
        seedDevice(userId, "device-c2b", "token-c2b")
        val deviceA = deviceDao.findByUserAndDevice(userId, "device-c2a")!!
        val deviceB = deviceDao.findByUserAndDevice(userId, "device-c2b")!!

        val notificationA = seedNotification(userId, "like:post-c2a:w1")
        val notificationB = seedNotification(userId, "like:post-c2b:w1")
        outboxDao.enqueue(notificationA, deviceA.id)
        outboxDao.enqueue(notificationB, deviceB.id)

        val fake = FakePushDispatchService { index, _ -> FcmSendResult.Accepted("msg-$index") }
        val processor = NotificationOutboxProcessor(outboxDao, deviceDao, fake, leaderboardDao, leaderboardDeltaService)

        processor.processDueBatch()

        assertEquals(2, fake.calls.size, "different devices must each get their own send, never collapsed")
        assertEquals(2, fake.calls.distinct().size)
    }

    @Test
    fun `an expired row is dropped and excluded from the collapsed send, leaving only its inbox row behind`() = runTest {
        val userId = seedUser("collapse3@example.com", "collapse3")
        seedDevice(userId, "device-c3", "token-c3")
        val deviceId = deviceDao.findByUserAndDevice(userId, "device-c3")!!.id

        val staleNotificationId = seedNotification(userId, "like:post-c3-stale:w1")
        outboxDao.enqueue(staleNotificationId, deviceId, expiresAt = Instant.now().atOffset(ZoneOffset.UTC).minusHours(1))
        val freshNotificationIds = (1..3).map { i ->
            val notificationId = seedNotification(userId, "like:post-c3-fresh-$i:w1")
            outboxDao.enqueue(notificationId, deviceId)
            notificationId
        }

        val fake = FakePushDispatchService { index, _ -> FcmSendResult.Accepted("msg-fresh-$index") }
        val processor = NotificationOutboxProcessor(outboxDao, deviceDao, fake, leaderboardDao, leaderboardDeltaService)

        processor.processDueBatch()

        assertEquals(1, fake.calls.size, "only the 3 fresh rows collapse into 1 send — the expired one never reaches FCM")

        val staleOutboxId = outboxDao.find(staleNotificationId, deviceId)!!.id
        assertEquals(OutboxState.DROPPED, outboxRow(staleOutboxId)[NotificationOutboxTable.state])
        // Dropped, but its inbox row is untouched — still present with its original title.
        assertEquals("Alex liked your spot", notificationRow(staleNotificationId)[NotificationTable.title])

        freshNotificationIds.forEach { id ->
            val outboxId = outboxDao.find(id, deviceId)!!.id
            assertEquals(OutboxState.ACCEPTED, outboxRow(outboxId)[NotificationOutboxTable.state])
        }
    }

    // ---------- day-7 leaderboard copy recomputed at dispatch (plan §9 / §18, step 6.5) ----------

    private fun setScore(userId: UUID, score: Int) = transaction {
        UserTable.update({ UserTable.id eq userId }) { it[UserTable.spotScore] = score }
    }

    private fun seedReminderWithEnqueuedDelta(userId: UUID, dedupeKey: String, title: String, enqueuedDeltaPoints: Int) = transaction {
        eventService.record(
            recipientId = userId,
            category = NotificationCategory.REMINDERS,
            dedupeKey = dedupeKey,
            actorId = null,
            actorUsername = null,
            title = title,
            body = "irrelevant at enqueue",
            enqueuedDeltaPoints = enqueuedDeltaPoints,
        )
    }

    @Test
    fun `an undrifted day-7 delta keeps the numeric copy, recomputed fresh at dispatch`() = runTest {
        val ahead = seedUser("delta-ahead@example.com", "delta_ahead")
        setScore(ahead, 50)
        val me = seedUser("delta-me@example.com", "delta_me")
        setScore(me, 40)
        seedDevice(me, "device-delta1", "token-delta1")

        // At enqueue: S_A=50, S=40 -> delta = 11. Nothing changes before dispatch.
        val notificationId = seedReminderWithEnqueuedDelta(me, "inactivity:d7:2026-06-01", "stale enqueue-time title", enqueuedDeltaPoints = 11)
        outboxDao.enqueue(notificationId, deviceDao.findByUserAndDevice(me, "device-delta1")!!.id)

        val fake = FakePushDispatchService { _, _ -> FcmSendResult.Accepted("msg") }
        val processor = NotificationOutboxProcessor(outboxDao, deviceDao, fake, leaderboardDao, leaderboardDeltaService)
        processor.processDueBatch()

        val row = notificationRow(notificationId)
        assertEquals("The board moved without you", row[NotificationTable.title]) // delta 11 > 10
    }

    @Test
    fun `a day-7 delta that drifted more than 30% between enqueue and dispatch falls back to generic copy`() = runTest {
        val ahead = seedUser("drift-ahead@example.com", "drift_ahead")
        setScore(ahead, 45)
        val me = seedUser("drift-me@example.com", "drift_me")
        setScore(me, 40)
        seedDevice(me, "device-drift1", "token-drift1")

        // At enqueue: S_A=45, S=40 -> delta = 6, stored as enqueuedDeltaPoints.
        val notificationId = seedReminderWithEnqueuedDelta(me, "inactivity:d7:2026-06-02", "stale enqueue-time title", enqueuedDeltaPoints = 6)
        outboxDao.enqueue(notificationId, deviceDao.findByUserAndDevice(me, "device-drift1")!!.id)

        // Before dispatch, the leaderboard moves a lot: the leader now needs 20 points, not 6 -> >30% drift.
        setScore(ahead, 60)

        val fake = FakePushDispatchService { _, _ -> FcmSendResult.Accepted("msg") }
        val processor = NotificationOutboxProcessor(outboxDao, deviceDao, fake, leaderboardDao, leaderboardDeltaService)
        processor.processDueBatch()

        val row = notificationRow(notificationId)
        assertEquals("Your spots have been quiet", row[NotificationTable.title])
        assertTrue(row[NotificationTable.body].none { it.isDigit() }, "generic fallback copy must never contain a number")
    }

    @Test
    fun `a non-REMINDERS notification is never touched by the leaderboard-copy recompute`() = runTest {
        val userId = seedUser("notrem@example.com", "notrem")
        seedDevice(userId, "device-notrem", "token-notrem")
        val notificationId = seedNotification(userId, "like:post-notrem:w1")
        outboxDao.enqueue(notificationId, deviceDao.findByUserAndDevice(userId, "device-notrem")!!.id)

        val fake = FakePushDispatchService { _, _ -> FcmSendResult.Accepted("msg") }
        val processor = NotificationOutboxProcessor(outboxDao, deviceDao, fake, leaderboardDao, leaderboardDeltaService)
        processor.processDueBatch()

        assertEquals("Alex liked your spot", notificationRow(notificationId)[NotificationTable.title])
    }

    // ---------- CHALLENGES: payload, priority, TTL, and never-collapse (push-notifications plan, "challenge is live" work) ----------

    @Test
    fun `a CHALLENGES payload carries deep_link and challenge_id, sent at NORMAL priority`() = runTest {
        val userId = seedUser("challenge-payload@example.com", "challengepayload")
        seedDevice(userId, "device-challenge-payload", "token-challenge-payload")
        val device = deviceDao.findByUserAndDevice(userId, "device-challenge-payload")!!
        val (challengeId, notificationId) = seedChallengeNotification(userId, "challenge_started:payload-test")
        outboxDao.enqueue(notificationId, device.id)

        val fake = FakePushDispatchService { _, _ -> FcmSendResult.Accepted("msg-challenge-payload") }
        val processor = NotificationOutboxProcessor(outboxDao, deviceDao, fake, leaderboardDao, leaderboardDeltaService)
        processor.processDueBatch()

        assertEquals(1, fake.calls.size)
        val data = fake.dataByCall.single()
        assertEquals("challenge", data["deep_link"])
        assertEquals(challengeId.toString(), data["challenge_id"])
        assertEquals(FcmPriority.NORMAL, fake.priorityByCall.single())
    }

    @Test
    fun `a CHALLENGES row's TTL is derived from its outbox row's expiresAt, same as any other category`() = runTest {
        val userId = seedUser("challenge-ttl@example.com", "challengettl")
        seedDevice(userId, "device-challenge-ttl", "token-challenge-ttl")
        val device = deviceDao.findByUserAndDevice(userId, "device-challenge-ttl")!!
        val (_, notificationId) = seedChallengeNotification(userId, "challenge_started:ttl-test")
        outboxDao.enqueue(notificationId, device.id, expiresAt = Instant.now().atOffset(ZoneOffset.UTC).plusHours(6))

        val fake = FakePushDispatchService { _, _ -> FcmSendResult.Accepted("msg-challenge-ttl") }
        val processor = NotificationOutboxProcessor(outboxDao, deviceDao, fake, leaderboardDao, leaderboardDeltaService)
        processor.processDueBatch()

        assertEquals(1, fake.calls.size)
        val ttlSeconds = fake.ttlSecondsByCall.single()
        assertTrue(ttlSeconds != null, "expected android.ttl to be set for a CHALLENGES row with a future expiresAt")
        assertTrue(ttlSeconds!! in (6L * 3600 - 60)..(6L * 3600), "expected ~6h of ttl, was ${ttlSeconds}s")
    }

    @Test
    fun `a CHALLENGES row due alongside a LIKE on the same device produces two sends, never one collapsed`() = runTest {
        val userId = seedUser("challenge-nocollapse@example.com", "challengenocollapse")
        seedDevice(userId, "device-challenge-nocollapse", "token-challenge-nocollapse")
        val device = deviceDao.findByUserAndDevice(userId, "device-challenge-nocollapse")!!

        val (challengeId, challengeNotificationId) = seedChallengeNotification(userId, "challenge_started:nocollapse-test")
        outboxDao.enqueue(challengeNotificationId, device.id)
        val likeNotificationId = seedNotification(userId, "like:post-nocollapse:w1")
        outboxDao.enqueue(likeNotificationId, device.id)

        val fake = FakePushDispatchService { index, _ -> FcmSendResult.Accepted("msg-nocollapse-$index") }
        val processor = NotificationOutboxProcessor(outboxDao, deviceDao, fake, leaderboardDao, leaderboardDeltaService)
        processor.processDueBatch()

        assertEquals(2, fake.calls.size, "a CHALLENGES row must never collapse with another category due on the same device")
        assertTrue(fake.dataByCall.none { it.containsKey("collapsed") }, "neither send should be the generic collapsed backlog summary")
        assertEquals(challengeId.toString(), fake.dataByCall.single { it["category"] == "CHALLENGES" }["challenge_id"])

        val challengeOutboxId = outboxDao.find(challengeNotificationId, device.id)!!.id
        val likeOutboxId = outboxDao.find(likeNotificationId, device.id)!!.id
        assertEquals(OutboxState.ACCEPTED, outboxRow(challengeOutboxId)[NotificationOutboxTable.state])
        assertEquals(OutboxState.ACCEPTED, outboxRow(likeOutboxId)[NotificationOutboxTable.state])
    }
}
