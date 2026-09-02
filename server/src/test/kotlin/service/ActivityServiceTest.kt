package service

import com.revio.server.core.storage.IStorageService
import com.revio.server.features.activity.ActivityEventType
import com.revio.server.features.activity.ActivityService
import com.revio.server.features.leaderboard.ILeaderboardDAO
import com.revio.server.features.leaderboard.ILeaderboardSnapshotDAO
import com.revio.server.features.post.IPostDAO
import features.activity.ActivityEventRow
import features.activity.CommentActivityRow
import features.activity.IActivityDAO
import features.activity.LikeActivityRow
import com.revio.server.features.leaderboard.UserScoreStreak
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class ActivityServiceTest {

    private val activityDao = mockk<IActivityDAO>()
    private val snapshotDao = mockk<ILeaderboardSnapshotDAO>()
    private val leaderboardDao = mockk<ILeaderboardDAO>()
    private val postDao = mockk<IPostDAO>()
    private val storage = mockk<IStorageService>(relaxed = true)

    private val service = ActivityService(activityDao, snapshotDao, leaderboardDao, postDao, storage, "UTC")

    private val userId = UUID.randomUUID()

    private fun userScoreStreak(score: Int) = UserScoreStreak(
        userId = userId,
        username = "alice",
        profilePicturePath = null,
        spotScore = score,
        currentStreak = 0,
        lastStreakDate = null,
        lastStreakTimezone = null,
    )

    /** Stubs every source ActivityService merges into `items`, all empty by default. */
    private fun stubEmptySources() {
        coEvery { activityDao.getLikeItems(userId, any()) } returns emptyList()
        coEvery { activityDao.getCommentItems(userId, any()) } returns emptyList()
        coEvery { activityDao.getPersistedEvents(userId, any()) } returns emptyList()
        coEvery { activityDao.countTodayUniqueInteractors(userId, any()) } returns 0L
    }

    // ---------- weeklySpotScore ----------

    @Test
    fun `weeklySpotScore is the delta between current score and the week-start snapshot baseline`() = runTest {
        stubEmptySources()
        coEvery { leaderboardDao.getUserScoreAndStreak(userId) } returns userScoreStreak(score = 340)
        coEvery { snapshotDao.getSpotScoreOnOrBefore(userId, any()) } returns 100

        val result = service.getActivity(userId, 50, "UTC")

        assertEquals(240, result.weeklySpotScore)
    }

    @Test
    fun `weeklySpotScore clamps to 0 when score dropped below the baseline`() = runTest {
        stubEmptySources()
        coEvery { leaderboardDao.getUserScoreAndStreak(userId) } returns userScoreStreak(score = 50)
        coEvery { snapshotDao.getSpotScoreOnOrBefore(userId, any()) } returns 100

        val result = service.getActivity(userId, 50, "UTC")

        assertEquals(0, result.weeklySpotScore)
    }

    @Test
    fun `weeklySpotScore falls back to summing post points since week start when no snapshot exists`() = runTest {
        stubEmptySources()
        coEvery { leaderboardDao.getUserScoreAndStreak(userId) } returns userScoreStreak(score = 340)
        coEvery { snapshotDao.getSpotScoreOnOrBefore(userId, any()) } returns null
        coEvery { postDao.sumPointsSince(userId, any()) } returns 42

        val result = service.getActivity(userId, 50, "UTC")

        assertEquals(42, result.weeklySpotScore)
    }

    @Test
    fun `getActivity does not throw and returns 200-shape response when no snapshot has ever run`() = runTest {
        stubEmptySources()
        coEvery { leaderboardDao.getUserScoreAndStreak(userId) } returns null
        coEvery { snapshotDao.getSpotScoreOnOrBefore(userId, any()) } returns null
        coEvery { postDao.sumPointsSince(userId, any()) } returns 0

        val result = service.getActivity(userId, 50, "UTC")

        assertEquals(0, result.weeklySpotScore)
        assertEquals(0, result.todayInteractions)
        assertTrue(result.items.isEmpty())
    }

    // ---------- todayInteractions ----------

    @Test
    fun `todayInteractions passes through the DAO's unique interactor count`() = runTest {
        stubEmptySources()
        coEvery { activityDao.countTodayUniqueInteractors(userId, any()) } returns 3L
        coEvery { leaderboardDao.getUserScoreAndStreak(userId) } returns userScoreStreak(score = 0)
        coEvery { snapshotDao.getSpotScoreOnOrBefore(userId, any()) } returns 0

        val result = service.getActivity(userId, 50, "UTC")

        assertEquals(3, result.todayInteractions)
    }

    // ---------- items: merge + sort + mapping ----------

    @Test
    fun `items from all four types are merged and sorted newest first`() = runTest {
        val now = Instant.now()
        coEvery { leaderboardDao.getUserScoreAndStreak(userId) } returns userScoreStreak(score = 0)
        coEvery { snapshotDao.getSpotScoreOnOrBefore(userId, any()) } returns 0
        coEvery { activityDao.countTodayUniqueInteractors(userId, any()) } returns 0L

        val likeRow = LikeActivityRow(
            likeId = UUID.randomUUID(),
            actorUserId = UUID.randomUUID(),
            actorUsername = "tommy82",
            actorProfilePicturePath = "avatars/tommy.jpg",
            postId = UUID.randomUUID(),
            postImageKey = "posts/porsche.jpg",
            brand = "Porsche",
            model = "GT3",
            createdAt = now.minus(2, ChronoUnit.HOURS),
        )
        val commentRow = CommentActivityRow(
            commentId = UUID.randomUUID(),
            actorUserId = UUID.randomUUID(),
            actorUsername = "charlotte_khan",
            actorProfilePicturePath = null,
            postId = UUID.randomUUID(),
            postImageKey = "posts/bmw.jpg",
            brand = "BMW",
            model = "M4",
            commentText = "Incredible spec, where did you find this?",
            createdAt = now.minus(4, ChronoUnit.HOURS),
        )
        val leaderboardUpRow = ActivityEventRow(
            id = UUID.randomUUID(),
            type = ActivityEventType.LEADERBOARD_UP,
            valueInt = 3,
            createdAt = now.minus(1, ChronoUnit.DAYS),
        )
        val streakRow = ActivityEventRow(
            id = UUID.randomUUID(),
            type = ActivityEventType.STREAK,
            valueInt = 5,
            createdAt = now.minus(2, ChronoUnit.DAYS),
        )

        coEvery { activityDao.getLikeItems(userId, any()) } returns listOf(likeRow)
        coEvery { activityDao.getCommentItems(userId, any()) } returns listOf(commentRow)
        coEvery { activityDao.getPersistedEvents(userId, any()) } returns listOf(leaderboardUpRow, streakRow)

        val result = service.getActivity(userId, 50, "UTC")

        assertEquals(4, result.items.size)
        assertEquals(listOf("LIKE", "COMMENT", "LEADERBOARD_UP", "STREAK"), result.items.map { it.type })
    }

    @Test
    fun `LIKE item is mapped with bold-username fields, brand, model and thumbnail`() = runTest {
        stubEmptySources()
        coEvery { leaderboardDao.getUserScoreAndStreak(userId) } returns userScoreStreak(score = 0)
        coEvery { snapshotDao.getSpotScoreOnOrBefore(userId, any()) } returns 0

        val actorId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val likeRow = LikeActivityRow(
            likeId = UUID.randomUUID(),
            actorUserId = actorId,
            actorUsername = "tommy82",
            actorProfilePicturePath = "avatars/tommy.jpg",
            postId = postId,
            postImageKey = "posts/porsche.jpg",
            brand = "Porsche",
            model = "GT3",
            createdAt = Instant.now(),
        )
        coEvery { activityDao.getLikeItems(userId, any()) } returns listOf(likeRow)

        val result = service.getActivity(userId, 50, "UTC")

        val item = result.items.single()
        assertEquals("LIKE", item.type)
        assertEquals(actorId, item.actorUserId)
        assertEquals("tommy82", item.actorUsername)
        assertEquals(postId, item.postId)
        assertEquals("Porsche", item.brand)
        assertEquals("GT3", item.model)
    }

    @Test
    fun `COMMENT item carries the comment text`() = runTest {
        stubEmptySources()
        coEvery { leaderboardDao.getUserScoreAndStreak(userId) } returns userScoreStreak(score = 0)
        coEvery { snapshotDao.getSpotScoreOnOrBefore(userId, any()) } returns 0

        val commentRow = CommentActivityRow(
            commentId = UUID.randomUUID(),
            actorUserId = UUID.randomUUID(),
            actorUsername = "charlotte_khan",
            actorProfilePicturePath = null,
            postId = UUID.randomUUID(),
            postImageKey = "posts/bmw.jpg",
            brand = "BMW",
            model = "M4",
            commentText = "Incredible spec, where did you find this?",
            createdAt = Instant.now(),
        )
        coEvery { activityDao.getCommentItems(userId, any()) } returns listOf(commentRow)

        val result = service.getActivity(userId, 50, "UTC")

        val item = result.items.single()
        assertEquals("COMMENT", item.type)
        assertEquals("Incredible spec, where did you find this?", item.commentText)
    }

    @Test
    fun `LEADERBOARD_UP item exposes placesMoved when the persisted event reflects a multi-place jump`() = runTest {
        stubEmptySources()
        coEvery { leaderboardDao.getUserScoreAndStreak(userId) } returns userScoreStreak(score = 0)
        coEvery { snapshotDao.getSpotScoreOnOrBefore(userId, any()) } returns 0

        val eventId = UUID.randomUUID()
        coEvery { activityDao.getPersistedEvents(userId, any()) } returns listOf(
            ActivityEventRow(id = eventId, type = ActivityEventType.LEADERBOARD_UP, valueInt = 3, createdAt = Instant.now()),
        )

        val result = service.getActivity(userId, 50, "UTC")

        val item = result.items.single()
        assertEquals("LEADERBOARD_UP", item.type)
        assertEquals("lb:$eventId", item.id)
        assertEquals(3, item.placesMoved)
    }

    @Test
    fun `STREAK item exposes streakDays`() = runTest {
        stubEmptySources()
        coEvery { leaderboardDao.getUserScoreAndStreak(userId) } returns userScoreStreak(score = 0)
        coEvery { snapshotDao.getSpotScoreOnOrBefore(userId, any()) } returns 0

        val eventId = UUID.randomUUID()
        coEvery { activityDao.getPersistedEvents(userId, any()) } returns listOf(
            ActivityEventRow(id = eventId, type = ActivityEventType.STREAK, valueInt = 5, createdAt = Instant.now()),
        )

        val result = service.getActivity(userId, 50, "UTC")

        val item = result.items.single()
        assertEquals("STREAK", item.type)
        assertEquals("streak:$eventId", item.id)
        assertEquals(5, item.streakDays)
    }

    @Test
    fun `items are truncated to limit after merge-sort`() = runTest {
        coEvery { leaderboardDao.getUserScoreAndStreak(userId) } returns userScoreStreak(score = 0)
        coEvery { snapshotDao.getSpotScoreOnOrBefore(userId, any()) } returns 0
        coEvery { activityDao.countTodayUniqueInteractors(userId, any()) } returns 0L
        coEvery { activityDao.getCommentItems(userId, any()) } returns emptyList()

        val now = Instant.now()
        val likeRows = (1..5).map { i ->
            LikeActivityRow(
                likeId = UUID.randomUUID(),
                actorUserId = UUID.randomUUID(),
                actorUsername = "user$i",
                actorProfilePicturePath = null,
                postId = UUID.randomUUID(),
                postImageKey = "posts/$i.jpg",
                brand = "Brand$i",
                model = "Model$i",
                createdAt = now.minus(i.toLong(), ChronoUnit.HOURS),
            )
        }
        coEvery { activityDao.getLikeItems(userId, any()) } returns likeRows
        coEvery { activityDao.getPersistedEvents(userId, any()) } returns emptyList()

        val result = service.getActivity(userId, 2, "UTC")

        assertEquals(2, result.items.size)
        // Newest two (i=1, i=2) survive the truncation.
        assertEquals("user1", result.items[0].actorUsername)
        assertEquals("user2", result.items[1].actorUsername)
    }

    // ---------- LIKE / COMMENT aggregation (Partea II, Pasul 3) ----------

    private fun likeRow(
        postId: UUID,
        createdAt: Instant,
        actorUserId: UUID = UUID.randomUUID(),
        actorUsername: String = "user-$actorUserId",
    ) = LikeActivityRow(
        likeId = UUID.randomUUID(),
        actorUserId = actorUserId,
        actorUsername = actorUsername,
        actorProfilePicturePath = null,
        postId = postId,
        postImageKey = "posts/$postId.jpg",
        brand = "Porsche",
        model = "911",
        createdAt = createdAt,
    )

    private fun commentRow(
        postId: UUID,
        createdAt: Instant,
        actorUserId: UUID = UUID.randomUUID(),
        actorUsername: String = "user-$actorUserId",
        commentText: String = "nice spot",
    ) = CommentActivityRow(
        commentId = UUID.randomUUID(),
        actorUserId = actorUserId,
        actorUsername = actorUsername,
        actorProfilePicturePath = null,
        postId = postId,
        postImageKey = "posts/$postId.jpg",
        brand = "BMW",
        model = "M4",
        commentText = commentText,
        createdAt = createdAt,
    )

    @Test
    fun `three likes on the same post within the 60-minute window collapse into one card`() = runTest {
        stubEmptySources()
        coEvery { leaderboardDao.getUserScoreAndStreak(userId) } returns userScoreStreak(score = 0)
        coEvery { snapshotDao.getSpotScoreOnOrBefore(userId, any()) } returns 0

        val postId = UUID.randomUUID()
        val windowBase = Instant.parse("2026-08-25T10:00:00Z") // aligned to a 60-min bucket
        val actor1 = UUID.randomUUID()
        val actor2 = UUID.randomUUID()
        val actor3 = UUID.randomUUID()
        coEvery { activityDao.getLikeItems(userId, any()) } returns listOf(
            likeRow(postId, windowBase.plusSeconds(60), actor1, "alice"),
            likeRow(postId, windowBase.plusSeconds(600), actor2, "bob"),
            likeRow(postId, windowBase.plusSeconds(1800), actor3, "carol"), // most recent -> representative
        )

        val result = service.getActivity(userId, 50, "UTC")

        val item = result.items.single()
        assertEquals("LIKE", item.type)
        assertEquals(3, item.actorCount)
        assertEquals(actor3, item.actorUserId)
        assertEquals("carol", item.actorUsername)
        assertEquals(windowBase.plusSeconds(1800), item.createdAt)
    }

    @Test
    fun `likes on different posts are never aggregated together`() = runTest {
        stubEmptySources()
        coEvery { leaderboardDao.getUserScoreAndStreak(userId) } returns userScoreStreak(score = 0)
        coEvery { snapshotDao.getSpotScoreOnOrBefore(userId, any()) } returns 0

        val now = Instant.parse("2026-08-25T10:00:00Z")
        coEvery { activityDao.getLikeItems(userId, any()) } returns listOf(
            likeRow(UUID.randomUUID(), now),
            likeRow(UUID.randomUUID(), now.plusSeconds(60)),
        )

        val result = service.getActivity(userId, 50, "UTC")

        assertEquals(2, result.items.size)
        assertTrue(result.items.all { it.actorCount == 1 })
    }

    @Test
    fun `likes 08_59 and 09_01 on the same post fall in different calendar buckets and stay separate`() = runTest {
        stubEmptySources()
        coEvery { leaderboardDao.getUserScoreAndStreak(userId) } returns userScoreStreak(score = 0)
        coEvery { snapshotDao.getSpotScoreOnOrBefore(userId, any()) } returns 0

        val postId = UUID.randomUUID()
        coEvery { activityDao.getLikeItems(userId, any()) } returns listOf(
            likeRow(postId, Instant.parse("2026-08-25T08:59:00Z")),
            likeRow(postId, Instant.parse("2026-08-25T09:01:00Z")),
        )

        val result = service.getActivity(userId, 50, "UTC")

        assertEquals(2, result.items.size)
        assertTrue(result.items.all { it.actorCount == 1 })
    }

    @Test
    fun `a single comment keeps its commentText and actorCount 1`() = runTest {
        stubEmptySources()
        coEvery { leaderboardDao.getUserScoreAndStreak(userId) } returns userScoreStreak(score = 0)
        coEvery { snapshotDao.getSpotScoreOnOrBefore(userId, any()) } returns 0

        coEvery { activityDao.getCommentItems(userId, any()) } returns listOf(
            commentRow(UUID.randomUUID(), Instant.now(), commentText = "Incredible spec"),
        )

        val result = service.getActivity(userId, 50, "UTC")

        val item = result.items.single()
        assertEquals(1, item.actorCount)
        assertEquals("Incredible spec", item.commentText)
    }

    @Test
    fun `comments from two different users within the 15-minute window aggregate to one actor each and drop commentText`() = runTest {
        stubEmptySources()
        coEvery { leaderboardDao.getUserScoreAndStreak(userId) } returns userScoreStreak(score = 0)
        coEvery { snapshotDao.getSpotScoreOnOrBefore(userId, any()) } returns 0

        val postId = UUID.randomUUID()
        val windowBase = Instant.parse("2026-08-25T10:00:00Z")
        coEvery { activityDao.getCommentItems(userId, any()) } returns listOf(
            commentRow(postId, windowBase.plusSeconds(60), commentText = "first"),
            commentRow(postId, windowBase.plusSeconds(120), commentText = "second"),
        )

        val result = service.getActivity(userId, 50, "UTC")

        val item = result.items.single()
        assertEquals("COMMENT", item.type)
        assertEquals(2, item.actorCount)
        assertEquals(null, item.commentText)
    }

    @Test
    fun `three comments from the same user in the same window count as a single actor`() = runTest {
        stubEmptySources()
        coEvery { leaderboardDao.getUserScoreAndStreak(userId) } returns userScoreStreak(score = 0)
        coEvery { snapshotDao.getSpotScoreOnOrBefore(userId, any()) } returns 0

        val postId = UUID.randomUUID()
        val actor = UUID.randomUUID()
        val windowBase = Instant.parse("2026-08-25T10:00:00Z")
        coEvery { activityDao.getCommentItems(userId, any()) } returns listOf(
            commentRow(postId, windowBase.plusSeconds(60), actor, "dave", "one"),
            commentRow(postId, windowBase.plusSeconds(120), actor, "dave", "two"),
            commentRow(postId, windowBase.plusSeconds(180), actor, "dave", "three"),
        )

        val result = service.getActivity(userId, 50, "UTC")

        val item = result.items.single()
        assertEquals(1, item.actorCount)
        assertEquals("three", item.commentText)
    }

    @Test
    fun `zero items in one category does not affect aggregation of the other`() = runTest {
        stubEmptySources()
        coEvery { leaderboardDao.getUserScoreAndStreak(userId) } returns userScoreStreak(score = 0)
        coEvery { snapshotDao.getSpotScoreOnOrBefore(userId, any()) } returns 0

        val postId = UUID.randomUUID()
        val windowBase = Instant.parse("2026-08-25T10:00:00Z")
        coEvery { activityDao.getLikeItems(userId, any()) } returns listOf(
            likeRow(postId, windowBase),
            likeRow(postId, windowBase.plusSeconds(60)),
        )
        // getCommentItems stays empty via stubEmptySources()

        val result = service.getActivity(userId, 50, "UTC")

        val item = result.items.single()
        assertEquals("LIKE", item.type)
        assertEquals(2, item.actorCount)
    }

    // ---------- over-fetch (Partea II, Pasul 4) ----------

    @Test
    fun `raw fetch requests limit times 4 rows from like and comment sources, but only limit from persisted events`() = runTest {
        stubEmptySources()
        coEvery { leaderboardDao.getUserScoreAndStreak(userId) } returns userScoreStreak(score = 0)
        coEvery { snapshotDao.getSpotScoreOnOrBefore(userId, any()) } returns 0

        service.getActivity(userId, 50, "UTC")

        coVerify(exactly = 1) { activityDao.getLikeItems(userId, 200) }
        coVerify(exactly = 1) { activityDao.getCommentItems(userId, 200) }
        coVerify(exactly = 1) { activityDao.getPersistedEvents(userId, 50) }
    }

    @Test
    fun `raw fetch is capped at 400 rows even when limit times 4 would exceed it`() = runTest {
        stubEmptySources()
        coEvery { leaderboardDao.getUserScoreAndStreak(userId) } returns userScoreStreak(score = 0)
        coEvery { snapshotDao.getSpotScoreOnOrBefore(userId, any()) } returns 0

        service.getActivity(userId, 200, "UTC") // 200 * 4 = 800, must be capped to 400

        coVerify(exactly = 1) { activityDao.getLikeItems(userId, 400) }
        coVerify(exactly = 1) { activityDao.getCommentItems(userId, 400) }
    }

    @Test
    fun `over-fetch fills the page with an older group that a limit-sized raw fetch would have missed`() = runTest {
        stubEmptySources()
        coEvery { leaderboardDao.getUserScoreAndStreak(userId) } returns userScoreStreak(score = 0)
        coEvery { snapshotDao.getSpotScoreOnOrBefore(userId, any()) } returns 0

        // 5 likes on the newest post, all in the same window -> collapse to 1 card.
        val newestPost = UUID.randomUUID()
        val newestWindow = Instant.parse("2026-08-25T12:00:00Z")
        val recentLikes = (0 until 5).map { i -> likeRow(newestPost, newestWindow.plusSeconds(i.toLong())) }
        // 3 more likes on an older post, in an earlier (separate) window -> a 2nd card, but only
        // reachable if the raw fetch pulls past the first 5 rows (a plain `limit=2` raw fetch
        // would only ever see rows from the newest post and never learn this group exists).
        val olderPost = UUID.randomUUID()
        val olderWindow = Instant.parse("2026-08-25T09:00:00Z")
        val olderLikes = (0 until 3).map { i -> likeRow(olderPost, olderWindow.plusSeconds(i.toLong())) }

        coEvery { activityDao.getLikeItems(userId, any()) } returns (recentLikes + olderLikes)

        val result = service.getActivity(userId, 2, "UTC")

        assertEquals(2, result.items.size)
        assertEquals(setOf(newestPost, olderPost), result.items.map { it.postId }.toSet())
        val newestCard = result.items.single { it.postId == newestPost }
        val olderCard = result.items.single { it.postId == olderPost }
        assertEquals(5, newestCard.actorCount)
        assertEquals(3, olderCard.actorCount)
    }
}
