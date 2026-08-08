package service

import com.revio.server.core.storage.IStorageService
import com.revio.server.core.storage.StoredImage
import com.revio.server.features.auth.session.ISessionService
import com.revio.server.features.car_model.ICarModelDAO
import com.revio.server.features.challenge.IChallengeProgressService
import com.revio.server.features.moderation.IAdminAuditLogDAO
import com.revio.server.features.moderation.IAdminUserQueryDAO
import com.revio.server.features.moderation.IBanDAO
import com.revio.server.features.moderation.IModerationViolationDAO
import com.revio.server.features.moderation.ModerationService
import com.revio.server.features.moderation.OrphanedStorageObjectDAO
import com.revio.server.features.notification.INotificationService
import com.revio.server.features.post.IPostDAO
import com.revio.server.features.post.IPostRemovalDAO
import com.revio.server.features.post.IPostService
import com.revio.server.features.post.Post
import com.revio.server.features.post.PostRemovalOutcome
import com.revio.server.features.post.PostServiceImpl
import com.revio.server.features.post.PostSource
import com.revio.server.features.scoring.IScoringDao
import com.revio.server.features.scoring.IScoringService
import features.comment.ICommentDAO
import features.like.ILikeDAO
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import testutils.TestDatabaseFactory
import java.time.Instant
import java.util.UUID

/**
 * Image cleanup after a post removal is best-effort (PostService.kt: `removePost`'s
 * `runCatching { storageService.deleteImage(...) }`): a failed delete must not undo the
 * already-committed removal, and must not be silently lost — it is queued in
 * orphaned_storage_objects for [ModerationService.retryOrphanedStorage] to pick up later.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModerationStorageCleanupTest {

    private class FakeStorageService(var failing: Boolean) : IStorageService {
        val deleteAttempts = mutableListOf<String>()

        override suspend fun uploadImage(bytes: ByteArray, objectKey: String, contentType: String): StoredImage =
            throw NotImplementedError("not used by these tests")

        override suspend fun deleteImage(objectKey: String) {
            deleteAttempts += objectKey
            if (failing) throw RuntimeException("storage unavailable")
        }

        override fun normalizeObjectKey(pathOrUrl: String): String = pathOrUrl
        override fun resolveUrl(objectKey: String): String = "http://fake-storage/$objectKey"
    }

    private val postId = UUID.randomUUID()
    private val authorId = UUID.randomUUID()
    private val imageKey = "posts/orphan-cleanup-test.jpg"

    @BeforeAll
    fun setup() = TestDatabaseFactory.start()

    @AfterAll
    fun tearDown() = TestDatabaseFactory.stop()

    @BeforeEach
    fun clean() = TestDatabaseFactory.cleanDatabase()

    private fun seededPost() = Post(
        id = postId,
        userId = authorId,
        username = "alice",
        carModelId = null,
        brand = "volkswagen",
        model = "golf r",
        imageKey = imageKey,
        source = PostSource.CAMERA,
        points = 10,
        createdAt = Instant.now(),
    )

    private fun postService(storageService: IStorageService, orphanedStorageDao: OrphanedStorageObjectDAO): PostServiceImpl {
        val postDao = mockk<IPostDAO>()
        coEvery { postDao.findById(postId) } returns seededPost()

        val challengeProgressService = mockk<IChallengeProgressService>()
        every { challengeProgressService.contributionRevokePolicy() } returns { true }

        val postRemovalDao = mockk<IPostRemovalDAO>()
        coEvery { postRemovalDao.removePostAtomically(any(), any()) } returns
            PostRemovalOutcome(deletedRows = 1, reversals = emptyList())

        return PostServiceImpl(
            postDao = postDao,
            storageService = storageService,
            carModelDao = mockk<ICarModelDAO>(relaxed = true),
            likeDao = mockk<ILikeDAO>(relaxed = true),
            commentDao = mockk<ICommentDAO>(relaxed = true),
            scoringService = mockk<IScoringService>(relaxed = true),
            scoringDao = mockk<IScoringDao>(relaxed = true),
            challengeProgressService = challengeProgressService,
            postRemovalDao = postRemovalDao,
            orphanedStorageDao = orphanedStorageDao,
        )
    }

    // ---------- deleteImage failure is queued, not lost or propagated ----------

    @Test
    fun `a failed image delete does not undo the removal and is queued for retry`() = runTest {
        val orphanedStorageDao = OrphanedStorageObjectDAO()
        val storage = FakeStorageService(failing = true)

        // Must not throw: the post removal transaction already committed, so a storage failure
        // afterwards is best-effort cleanup, not a reason to fail the caller's request.
        postService(storage, orphanedStorageDao).removePostAsModerator(postId, UUID.randomUUID())

        assertEquals(listOf(imageKey), storage.deleteAttempts)
        val queued = orphanedStorageDao.listAll()
        assertEquals(listOf(imageKey), queued, "The failed key must be queued for a later retry")
    }

    @Test
    fun `a successful image delete queues nothing`() = runTest {
        val orphanedStorageDao = OrphanedStorageObjectDAO()
        val storage = FakeStorageService(failing = false)

        postService(storage, orphanedStorageDao).removePostAsModerator(postId, UUID.randomUUID())

        assertEquals(listOf(imageKey), storage.deleteAttempts)
        assertTrue(orphanedStorageDao.listAll().isEmpty())
    }

    // ---------- ModerationService.retryOrphanedStorage drains the queue ----------

    @Test
    fun `retrying a queued object that now succeeds removes it from the queue`() = runTest {
        val orphanedStorageDao = OrphanedStorageObjectDAO()
        orphanedStorageDao.recordFailure(imageKey, "storage unavailable")
        assertEquals(listOf(imageKey), orphanedStorageDao.listAll(), "Fixture must start with one queued object")

        val storage = FakeStorageService(failing = false)
        val moderationService = ModerationService(
            postService = mockk<IPostService>(relaxed = true),
            notificationService = mockk<INotificationService>(relaxed = true),
            storageService = storage,
            orphanedStorageDao = orphanedStorageDao,
            adminUserQueryDao = mockk<IAdminUserQueryDAO>(relaxed = true),
            moderationViolationDao = mockk<IModerationViolationDAO>(relaxed = true),
            banDao = mockk<IBanDAO>(relaxed = true),
            adminAuditLogDao = mockk<IAdminAuditLogDAO>(relaxed = true),
            sessionService = mockk<ISessionService>(relaxed = true),
        )

        val result = moderationService.retryOrphanedStorage()

        assertEquals(1, result.attempted)
        assertEquals(1, result.succeeded)
        assertTrue(orphanedStorageDao.listAll().isEmpty(), "A successful retry must drain the queue")
    }

    @Test
    fun `retrying a queued object that keeps failing stays in the queue`() = runTest {
        val orphanedStorageDao = OrphanedStorageObjectDAO()
        orphanedStorageDao.recordFailure(imageKey, "storage unavailable")

        val storage = FakeStorageService(failing = true)
        val moderationService = ModerationService(
            postService = mockk<IPostService>(relaxed = true),
            notificationService = mockk<INotificationService>(relaxed = true),
            storageService = storage,
            orphanedStorageDao = orphanedStorageDao,
            adminUserQueryDao = mockk<IAdminUserQueryDAO>(relaxed = true),
            moderationViolationDao = mockk<IModerationViolationDAO>(relaxed = true),
            banDao = mockk<IBanDAO>(relaxed = true),
            adminAuditLogDao = mockk<IAdminAuditLogDAO>(relaxed = true),
            sessionService = mockk<ISessionService>(relaxed = true),
        )

        val result = moderationService.retryOrphanedStorage()

        assertEquals(1, result.attempted)
        assertEquals(0, result.succeeded)
        assertEquals(listOf(imageKey), orphanedStorageDao.listAll(), "A repeat failure must stay queued")
    }
}
