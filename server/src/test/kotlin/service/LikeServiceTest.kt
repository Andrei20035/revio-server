package service

import com.revio.server.features.notification.DevicePlatform
import com.revio.server.features.notification.FirebaseProject
import com.revio.server.features.notification.INotificationEventService
import com.revio.server.features.notification.INotificationOutboxDAO
import com.revio.server.features.notification.IUserDeviceDAO
import com.revio.server.features.notification.IUserNotificationPrefsDAO
import com.revio.server.features.notification.UserDevice
import com.revio.server.features.notification.UserNotificationPrefs
import com.revio.server.features.post.IPostDAO
import com.revio.server.features.post.PostOwnerInfo
import com.revio.server.features.post.PostSource
import com.revio.server.features.scoring.IScoringService
import com.revio.server.features.user.IUserDAO
import com.revio.server.features.user.User
import features.like.ILikeDAO
import features.like.ILikeNotificationCursorDAO
import features.like.LikeNotificationParticipation
import features.like.LikePostNotFoundException
import features.like.LikeService
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

class LikeServiceTest {

    private val likeDao = mockk<ILikeDAO>(relaxed = true)
    private val postDao = mockk<IPostDAO>(relaxed = true)
    private val scoringService = mockk<IScoringService>(relaxed = true)
    private val notificationEventService = mockk<INotificationEventService>(relaxed = true)
    private val userDeviceDao = mockk<IUserDeviceDAO>(relaxed = true)
    private val notificationOutboxDao = mockk<INotificationOutboxDAO>(relaxed = true)
    private val likeNotificationCursorDao = mockk<ILikeNotificationCursorDAO>(relaxed = true)
    private val userDao = mockk<IUserDAO>(relaxed = true)
    private val notificationPrefsDao = mockk<IUserNotificationPrefsDAO>(relaxed = true)

    private fun newService(
        dao: ILikeDAO = likeDao,
        pDao: IPostDAO = postDao,
        scoring: IScoringService = scoringService,
        notifications: INotificationEventService = notificationEventService,
        deviceDao: IUserDeviceDAO = userDeviceDao,
        outboxDao: INotificationOutboxDAO = notificationOutboxDao,
        cursorDao: ILikeNotificationCursorDAO = likeNotificationCursorDao,
        uDao: IUserDAO = userDao,
        prefsDao: IUserNotificationPrefsDAO = notificationPrefsDao,
        likesPushEnabledProvider: () -> String? = { null },
    ) = LikeService(dao, pDao, scoring, notifications, deviceDao, outboxDao, cursorDao, uDao, prefsDao, likesPushEnabledProvider)

    private fun fakePrefs(userId: UUID, likesEnabled: Boolean) = UserNotificationPrefs(
        userId = userId,
        likesEnabled = likesEnabled,
        commentsEnabled = true,
        discoveryEnabled = true,
        remindersEnabled = true,
        quietStart = java.time.LocalTime.MIDNIGHT,
        quietEnd = java.time.LocalTime.of(8, 0),
    )

    private fun stubLikerUsername(likerId: UUID, username: String?) {
        coEvery { userDao.getUserById(likerId) } returns username?.let {
            User(
                authCredentialId = UUID.randomUUID(),
                fullName = "Test User",
                phoneNumber = null,
                birthDate = java.time.LocalDate.of(2000, 1, 1),
                username = it,
                country = "RO",
            )
        }
    }

    private fun stubCameraPost(postId: UUID, ownerId: UUID) {
        coEvery { postDao.getOwnerAndSource(postId) } returns PostOwnerInfo(ownerId, PostSource.CAMERA)
    }

    private fun stubGalleryPost(postId: UUID, ownerId: UUID) {
        coEvery { postDao.getOwnerAndSource(postId) } returns PostOwnerInfo(ownerId, PostSource.GALLERY)
    }

    // ---------- toggleLike: like path ----------

    @Test
    fun `toggleLike creates like when user has not liked and returns liked=true`() = runTest {
        val dao = mockk<ILikeDAO>()
        val userId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        coEvery { dao.hasUserLikedPost(userId, postId) } returns false
        coEvery { dao.likePost(userId, postId) } returns Unit
        coEvery { dao.getLikeCount(postId) } returns 1L
        coEvery { postDao.getOwnerAndSource(postId) } returns PostOwnerInfo(ownerId, PostSource.CAMERA)

        val result = newService(dao).toggleLike(userId, postId)

        assertTrue(result.liked)
        assertEquals(1L, result.count)
        coVerify(exactly = 1) { dao.likePost(userId, postId) }
        coVerify(exactly = 0) { dao.unlikePost(any(), any()) }
    }

    @Test
    fun `toggleLike removes like when user has already liked and returns liked=false`() = runTest {
        val dao = mockk<ILikeDAO>()
        val userId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        coEvery { dao.hasUserLikedPost(userId, postId) } returns true
        coEvery { dao.unlikePost(userId, postId) } returns 1
        coEvery { dao.getLikeCount(postId) } returns 0L
        coEvery { postDao.getOwnerAndSource(postId) } returns PostOwnerInfo(ownerId, PostSource.CAMERA)

        val result = newService(dao).toggleLike(userId, postId)

        assertFalse(result.liked)
        assertEquals(0L, result.count)
        coVerify(exactly = 1) { dao.unlikePost(userId, postId) }
        coVerify(exactly = 0) { dao.likePost(any(), any()) }
    }

    @Test
    fun `toggleLike returns updated count from DAO after toggle`() = runTest {
        val dao = mockk<ILikeDAO>()
        val userId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        coEvery { dao.hasUserLikedPost(userId, postId) } returns false
        coEvery { dao.likePost(userId, postId) } returns Unit
        coEvery { dao.getLikeCount(postId) } returns 42L
        coEvery { postDao.getOwnerAndSource(postId) } returns PostOwnerInfo(ownerId, PostSource.CAMERA)

        val result = newService(dao).toggleLike(userId, postId)

        assertEquals(42L, result.count)
    }

    // ---------- toggleLike: error mapping ----------

    @Test
    fun `toggleLike throws LikePostNotFoundException when post not found`() {
        val postId = UUID.randomUUID()
        coEvery { likeDao.hasUserLikedPost(any(), postId) } returns false
        coEvery { postDao.getOwnerAndSource(postId) } returns null

        assertThrows(LikePostNotFoundException::class.java) {
            runBlocking { newService().toggleLike(UUID.randomUUID(), postId) }
        }
    }

    @Test
    fun `toggleLike maps FK violation (23503) to LikePostNotFoundException`() {
        val dao = mockk<ILikeDAO>()
        val postId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        coEvery { dao.hasUserLikedPost(any(), postId) } returns false
        coEvery { postDao.getOwnerAndSource(postId) } returns PostOwnerInfo(ownerId, PostSource.CAMERA)
        coEvery { dao.likePost(any(), postId) } throws
                ExposedSQLException(SQLException("FK violation", "23503"), emptyList(), mockk(relaxed = true))

        assertThrows(LikePostNotFoundException::class.java) {
            runBlocking { newService(dao).toggleLike(UUID.randomUUID(), postId) }
        }
    }

    @Test
    fun `toggleLike rethrows non-FK ExposedSQLException`() {
        val dao = mockk<ILikeDAO>()
        val postId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        coEvery { dao.hasUserLikedPost(any(), any()) } returns false
        coEvery { postDao.getOwnerAndSource(postId) } returns PostOwnerInfo(ownerId, PostSource.CAMERA)
        coEvery { dao.likePost(any(), any()) } throws
                ExposedSQLException(SQLException("other error", "42000"), emptyList(), mockk(relaxed = true))

        assertThrows(ExposedSQLException::class.java) {
            runBlocking { newService(dao).toggleLike(UUID.randomUUID(), postId) }
        }
    }

    @Test
    fun `toggleLike does not call likePost when already liked`() = runTest {
        val dao = mockk<ILikeDAO>(relaxed = true)
        val userId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        coEvery { dao.hasUserLikedPost(userId, postId) } returns true
        coEvery { dao.getLikeCount(postId) } returns 5L
        coEvery { postDao.getOwnerAndSource(postId) } returns PostOwnerInfo(ownerId, PostSource.CAMERA)

        newService(dao).toggleLike(userId, postId)

        coVerify(exactly = 0) { dao.likePost(any(), any()) }
    }

    // ---------- scoring: CAMERA vs GALLERY ----------

    @Test
    fun `toggleLike calls onPostLiked for CAMERA post`() = runTest {
        val userId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        coEvery { likeDao.hasUserLikedPost(userId, postId) } returns false
        coEvery { likeDao.getLikeCount(postId) } returns 1L
        stubCameraPost(postId, ownerId)

        newService().toggleLike(userId, postId)

        coVerify(exactly = 1) {
            scoringService.onPostLiked(ownerId, postId, userId, PostSource.CAMERA)
        }
    }

    @Test
    fun `toggleLike does not call onPostLiked for GALLERY post`() = runTest {
        val userId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        coEvery { likeDao.hasUserLikedPost(userId, postId) } returns false
        coEvery { likeDao.getLikeCount(postId) } returns 1L
        stubGalleryPost(postId, ownerId)

        newService().toggleLike(userId, postId)

        coVerify(exactly = 1) {
            scoringService.onPostLiked(ownerId, postId, userId, PostSource.GALLERY)
        }
    }

    @Test
    fun `toggleLike calls onPostUnliked for CAMERA post`() = runTest {
        val userId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        coEvery { likeDao.hasUserLikedPost(userId, postId) } returns true
        coEvery { likeDao.getLikeCount(postId) } returns 0L
        stubCameraPost(postId, ownerId)

        newService().toggleLike(userId, postId)

        coVerify(exactly = 1) {
            scoringService.onPostUnliked(ownerId, postId, userId, PostSource.CAMERA)
        }
    }

    @Test
    fun `toggleLike calls onPostUnliked for GALLERY post`() = runTest {
        val userId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        coEvery { likeDao.hasUserLikedPost(userId, postId) } returns true
        coEvery { likeDao.getLikeCount(postId) } returns 0L
        stubGalleryPost(postId, ownerId)

        newService().toggleLike(userId, postId)

        coVerify(exactly = 1) {
            scoringService.onPostUnliked(ownerId, postId, userId, PostSource.GALLERY)
        }
    }

    // ---------- notification hook + debounce (plan §18, step 5.1) ----------

    @Test
    fun `a self-like never records a notification event`() = runTest {
        val userId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        coEvery { likeDao.hasUserLikedPost(userId, postId) } returns false
        coEvery { likeDao.getLikeCount(postId) } returns 1L
        stubCameraPost(postId, userId)

        newService().toggleLike(userId, postId)

        coVerify(exactly = 0) {
            notificationEventService.recordLike(any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) { notificationOutboxDao.enqueue(any(), any(), any(), any()) }
    }

    @Test
    fun `a self-like never touches the device or outbox DAOs at all`() = runTest {
        val userId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        coEvery { likeDao.hasUserLikedPost(userId, postId) } returns false
        coEvery { likeDao.getLikeCount(postId) } returns 1L
        stubCameraPost(postId, userId)

        newService().toggleLike(userId, postId)

        coVerify(exactly = 0) { userDeviceDao.findActiveByUser(any()) }
    }

    @Test
    fun `a like from someone else records a LIKES notification event keyed to the current 60-min window`() = runTest {
        val userId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        coEvery { likeDao.hasUserLikedPost(userId, postId) } returns false
        coEvery { likeDao.getLikeCount(postId) } returns 1L
        stubCameraPost(postId, ownerId)
        coEvery { likeNotificationCursorDao.find(postId, userId) } returns null
        stubLikerUsername(userId, null)
        every {
            notificationEventService.recordLike(any(), any(), any(), any(), any(), any())
        } returns UUID.randomUUID()

        newService().toggleLike(userId, postId)

        val dedupeKeySlot = slot<String>()
        verify(exactly = 1) {
            notificationEventService.recordLike(
                recipientId = ownerId,
                dedupeKey = capture(dedupeKeySlot),
                actorId = userId,
                actorUsername = isNull(),
                postId = postId,
                deepLink = isNull(),
            )
        }
        assertTrue(dedupeKeySlot.captured.startsWith("like:$postId:"), "expected a windowed dedupe key, was ${dedupeKeySlot.captured}")
        coVerify(exactly = 1) { likeNotificationCursorDao.insert(postId, userId, any()) }
    }

    @Test
    fun `a like from someone else looks up and passes the liker's username for copy rendering`() = runTest {
        val userId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        coEvery { likeDao.hasUserLikedPost(userId, postId) } returns false
        coEvery { likeDao.getLikeCount(postId) } returns 1L
        stubCameraPost(postId, ownerId)
        coEvery { likeNotificationCursorDao.find(postId, userId) } returns null
        stubLikerUsername(userId, "alex")
        every {
            notificationEventService.recordLike(any(), any(), any(), any(), any(), any())
        } returns UUID.randomUUID()

        newService().toggleLike(userId, postId)

        verify(exactly = 1) {
            notificationEventService.recordLike(
                recipientId = ownerId,
                dedupeKey = any(),
                actorId = userId,
                actorUsername = "alex",
                postId = postId,
                deepLink = isNull(),
            )
        }
    }

    // ---------- notification aggregation window + announced-likers cursor (plan §18, step 5.2) ----------

    @Test
    fun `a like from a liker already committed for this post never records a notification event`() = runTest {
        val userId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        coEvery { likeDao.hasUserLikedPost(userId, postId) } returns false
        coEvery { likeDao.getLikeCount(postId) } returns 1L
        stubCameraPost(postId, ownerId)
        coEvery { likeNotificationCursorDao.find(postId, userId) } returns
            LikeNotificationParticipation(windowStartedAt = Instant.EPOCH, committed = true)

        newService().toggleLike(userId, postId)

        coVerify(exactly = 0) {
            notificationEventService.recordLike(any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) { notificationOutboxDao.enqueue(any(), any(), any(), any()) }
        coVerify(exactly = 0) { likeNotificationCursorDao.insert(any(), any(), any()) }
    }

    @Test
    fun `a like from a liker with a still-open window contribution does not record a second event`() = runTest {
        val userId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        coEvery { likeDao.hasUserLikedPost(userId, postId) } returns false
        coEvery { likeDao.getLikeCount(postId) } returns 1L
        stubCameraPost(postId, ownerId)
        val currentWindow = features.like.LikeService.windowStartFor(Instant.now())
        coEvery { likeNotificationCursorDao.find(postId, userId) } returns
            LikeNotificationParticipation(windowStartedAt = currentWindow, committed = false)

        newService().toggleLike(userId, postId)

        coVerify(exactly = 0) {
            notificationEventService.recordLike(any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) { likeNotificationCursorDao.insert(any(), any(), any()) }
    }

    @Test
    fun `unlike withdraws a still-open participation and deletes the cursor row`() = runTest {
        val userId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        coEvery { likeDao.hasUserLikedPost(userId, postId) } returns true
        coEvery { likeDao.getLikeCount(postId) } returns 0L
        stubCameraPost(postId, ownerId)
        val currentWindow = features.like.LikeService.windowStartFor(Instant.now())
        coEvery { likeNotificationCursorDao.find(postId, userId) } returns
            LikeNotificationParticipation(windowStartedAt = currentWindow, committed = false)

        newService().toggleLike(userId, postId)

        val dedupeKeySlot = slot<String>()
        verify(exactly = 1) { notificationEventService.withdrawLikeActor(recipientId = ownerId, dedupeKey = capture(dedupeKeySlot)) }
        assertEquals("like:$postId:${currentWindow.epochSecond}", dedupeKeySlot.captured)
        coVerify(exactly = 1) { likeNotificationCursorDao.delete(postId, userId) }
    }

    @Test
    fun `unlike of an already-committed liker does not withdraw anything`() = runTest {
        val userId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        coEvery { likeDao.hasUserLikedPost(userId, postId) } returns true
        coEvery { likeDao.getLikeCount(postId) } returns 0L
        stubCameraPost(postId, ownerId)
        coEvery { likeNotificationCursorDao.find(postId, userId) } returns
            LikeNotificationParticipation(windowStartedAt = Instant.EPOCH, committed = true)

        newService().toggleLike(userId, postId)

        verify(exactly = 0) { notificationEventService.withdrawLikeActor(any(), any()) }
        coVerify(exactly = 0) { likeNotificationCursorDao.delete(any(), any()) }
    }

    @Test
    fun `unlike of a liker with no participation row is a no-op on the notification aggregation`() = runTest {
        val userId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        coEvery { likeDao.hasUserLikedPost(userId, postId) } returns true
        coEvery { likeDao.getLikeCount(postId) } returns 0L
        stubCameraPost(postId, ownerId)
        coEvery { likeNotificationCursorDao.find(postId, userId) } returns null

        newService().toggleLike(userId, postId)

        verify(exactly = 0) { notificationEventService.withdrawLikeActor(any(), any()) }
        coVerify(exactly = 0) { likeNotificationCursorDao.delete(any(), any()) }
    }

    @Test
    fun `the first like schedules the outbox row roughly 60s out, not immediately`() = runTest {
        val userId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val notificationId = UUID.randomUUID()
        val device = UserDevice(
            id = UUID.randomUUID(),
            userId = ownerId,
            deviceId = "device-1",
            fcmToken = "token-1",
            firebaseProject = FirebaseProject.DEBUG,
            platform = DevicePlatform.ANDROID,
            appVersion = "1.0.0",
            timezone = null,
            locale = null,
            isActive = true,
        )
        coEvery { likeDao.hasUserLikedPost(userId, postId) } returns false
        coEvery { likeDao.getLikeCount(postId) } returns 1L
        stubCameraPost(postId, ownerId)
        every {
            notificationEventService.recordLike(any(), any(), any(), any(), any(), any())
        } returns notificationId
        coEvery { notificationPrefsDao.get(ownerId) } returns fakePrefs(ownerId, likesEnabled = true)
        coEvery { userDeviceDao.findActiveByUser(ownerId) } returns listOf(device)
        val notBeforeSlot = slot<OffsetDateTime>()
        coEvery {
            notificationOutboxDao.enqueue(eq(notificationId), eq(device.id), capture(notBeforeSlot), isNull())
        } just Runs

        val before = Instant.now()
        newService(likesPushEnabledProvider = { "true" }).toggleLike(userId, postId)
        val after = Instant.now()

        val scheduledFor = notBeforeSlot.captured.toInstant()
        assertTrue(scheduledFor.isAfter(before.plusSeconds(50)), "expected ~60s out, was ${scheduledFor}")
        assertTrue(scheduledFor.isBefore(after.plusSeconds(70)), "expected ~60s out, was ${scheduledFor}")
        assertTrue(scheduledFor.isAfter(after.plusSeconds(1)), "must not be scheduled immediately")
    }

    // ---------- LIKES category activation behind flag (plan §18, step 5.7) ----------

    @Test
    fun `flag off never enqueues an outbox row, even with likes enabled and active devices`() = runTest {
        val userId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        coEvery { likeDao.hasUserLikedPost(userId, postId) } returns false
        coEvery { likeDao.getLikeCount(postId) } returns 1L
        stubCameraPost(postId, ownerId)
        coEvery { likeNotificationCursorDao.find(postId, userId) } returns null
        every {
            notificationEventService.recordLike(any(), any(), any(), any(), any(), any())
        } returns UUID.randomUUID()
        coEvery { notificationPrefsDao.get(ownerId) } returns fakePrefs(ownerId, likesEnabled = true)
        coEvery { userDeviceDao.findActiveByUser(ownerId) } returns listOf(
            UserDevice(
                id = UUID.randomUUID(),
                userId = ownerId,
                deviceId = "device-flag-off",
                fcmToken = "token-flag-off",
                firebaseProject = FirebaseProject.DEBUG,
                platform = DevicePlatform.ANDROID,
                appVersion = "1.0.0",
                timezone = null,
                locale = null,
                isActive = true,
            ),
        )

        newService(likesPushEnabledProvider = { null }).toggleLike(userId, postId)

        coVerify(exactly = 0) { notificationOutboxDao.enqueue(any(), any(), any(), any()) }
    }

    @Test
    fun `flag on but likes preference off never enqueues an outbox row`() = runTest {
        val userId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        coEvery { likeDao.hasUserLikedPost(userId, postId) } returns false
        coEvery { likeDao.getLikeCount(postId) } returns 1L
        stubCameraPost(postId, ownerId)
        coEvery { likeNotificationCursorDao.find(postId, userId) } returns null
        every {
            notificationEventService.recordLike(any(), any(), any(), any(), any(), any())
        } returns UUID.randomUUID()
        coEvery { notificationPrefsDao.get(ownerId) } returns fakePrefs(ownerId, likesEnabled = false)

        newService(likesPushEnabledProvider = { "true" }).toggleLike(userId, postId)

        coVerify(exactly = 0) { notificationOutboxDao.enqueue(any(), any(), any(), any()) }
        coVerify(exactly = 1) { notificationPrefsDao.get(ownerId) }
    }

    @Test
    fun `flag on and likes preference on enqueues one outbox row per active device`() = runTest {
        val userId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val notificationId = UUID.randomUUID()
        coEvery { likeDao.hasUserLikedPost(userId, postId) } returns false
        coEvery { likeDao.getLikeCount(postId) } returns 1L
        stubCameraPost(postId, ownerId)
        coEvery { likeNotificationCursorDao.find(postId, userId) } returns null
        every {
            notificationEventService.recordLike(any(), any(), any(), any(), any(), any())
        } returns notificationId
        coEvery { notificationPrefsDao.get(ownerId) } returns fakePrefs(ownerId, likesEnabled = true)
        val deviceA = UserDevice(
            id = UUID.randomUUID(), userId = ownerId, deviceId = "device-a", fcmToken = "token-a",
            firebaseProject = FirebaseProject.DEBUG, platform = DevicePlatform.ANDROID,
            appVersion = "1.0.0", timezone = null, locale = null, isActive = true,
        )
        val deviceB = UserDevice(
            id = UUID.randomUUID(), userId = ownerId, deviceId = "device-b", fcmToken = "token-b",
            firebaseProject = FirebaseProject.DEBUG, platform = DevicePlatform.ANDROID,
            appVersion = "1.0.0", timezone = null, locale = null, isActive = true,
        )
        coEvery { userDeviceDao.findActiveByUser(ownerId) } returns listOf(deviceA, deviceB)

        newService(likesPushEnabledProvider = { "true" }).toggleLike(userId, postId)

        coVerify(exactly = 1) { notificationOutboxDao.enqueue(notificationId, deviceA.id, any(), isNull()) }
        coVerify(exactly = 1) { notificationOutboxDao.enqueue(notificationId, deviceB.id, any(), isNull()) }
    }

    @Test
    fun `no active devices means no outbox rows, even when flag and preference are both on`() = runTest {
        val userId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        coEvery { likeDao.hasUserLikedPost(userId, postId) } returns false
        coEvery { likeDao.getLikeCount(postId) } returns 1L
        stubCameraPost(postId, ownerId)
        coEvery { likeNotificationCursorDao.find(postId, userId) } returns null
        every {
            notificationEventService.recordLike(any(), any(), any(), any(), any(), any())
        } returns UUID.randomUUID()
        coEvery { notificationPrefsDao.get(ownerId) } returns fakePrefs(ownerId, likesEnabled = true)
        coEvery { userDeviceDao.findActiveByUser(ownerId) } returns emptyList()

        newService(likesPushEnabledProvider = { "true" }).toggleLike(userId, postId)

        coVerify(exactly = 0) { notificationOutboxDao.enqueue(any(), any(), any(), any()) }
    }

    // ---------- getLikeStatus ----------

    @Test
    fun `getLikeStatus returns count and liked=false when userId is null`() = runTest {
        val dao = mockk<ILikeDAO>()
        val postId = UUID.randomUUID()
        coEvery { dao.getLikeCount(postId) } returns 7L

        val result = newService(dao).getLikeStatus(postId, userId = null)

        assertFalse(result.liked)
        assertEquals(7L, result.count)
        coVerify(exactly = 0) { dao.hasUserLikedPost(any(), any()) }
    }

    @Test
    fun `getLikeStatus returns liked=true when user has liked`() = runTest {
        val dao = mockk<ILikeDAO>()
        val userId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        coEvery { dao.getLikeCount(postId) } returns 3L
        coEvery { dao.hasUserLikedPost(userId, postId) } returns true

        val result = newService(dao).getLikeStatus(postId, userId)

        assertTrue(result.liked)
        assertEquals(3L, result.count)
    }

    @Test
    fun `getLikeStatus returns liked=false when user has not liked`() = runTest {
        val dao = mockk<ILikeDAO>()
        val userId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        coEvery { dao.getLikeCount(postId) } returns 3L
        coEvery { dao.hasUserLikedPost(userId, postId) } returns false

        val result = newService(dao).getLikeStatus(postId, userId)

        assertFalse(result.liked)
        assertEquals(3L, result.count)
    }

    @Test
    fun `getLikeStatus returns count=0 for post without likes`() = runTest {
        val dao = mockk<ILikeDAO>()
        val postId = UUID.randomUUID()
        coEvery { dao.getLikeCount(postId) } returns 0L

        val result = newService(dao).getLikeStatus(postId, userId = null)

        assertEquals(0L, result.count)
        assertFalse(result.liked)
    }
}
