package service

import com.revio.server.core.storage.IStorageService
import com.revio.server.features.car_model.ICarModelDAO
import com.revio.server.features.challenge.IChallengeProgressService
import com.revio.server.features.moderation.ModerationReason
import com.revio.server.features.post.IPostDAO
import com.revio.server.features.post.IPostRemovalDAO
import com.revio.server.features.post.InsertedPost
import com.revio.server.features.post.Post
import com.revio.server.features.post.PostNotFoundException
import com.revio.server.features.post.PostRemovalOutcome
import com.revio.server.features.post.PostServiceImpl
import com.revio.server.features.post.PostSource
import com.revio.server.features.post.dto.CreatePostDTO
import com.revio.server.features.scoring.IScoringDao
import com.revio.server.features.scoring.IScoringService
import features.comment.ICommentDAO
import features.like.ILikeDAO
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

/**
 * The two challenge integration points in PostService fail in deliberately opposite ways.
 *
 * On creation, a challenge failure is best-effort: the post and its normal points are already
 * committed and must stand; the missed contribution is recoverable by re-evaluating the post,
 * which still exists. On removal, it is not: the post row is about to be deleted and
 * challenge_contributions cascades with it, so a swallowed failure would destroy the only
 * evidence needed to revoke the reward. These tests pin both halves of that asymmetry.
 */
class PostServiceChallengeFailureTest {

    private val authorId = UUID.randomUUID()
    private val carModelId = UUID.randomUUID()
    private val postId = UUID.randomUUID()

    private fun service(
        challengeProgressService: IChallengeProgressService,
        postRemovalDao: IPostRemovalDAO,
        postDao: IPostDAO = mockk(relaxed = true),
    ) = PostServiceImpl(
        postDao = postDao,
        storageService = mockk<IStorageService>(relaxed = true),
        carModelDao = mockk<ICarModelDAO>(relaxed = true).also { coEvery { it.exists(any()) } returns true },
        likeDao = mockk<ILikeDAO>(relaxed = true),
        commentDao = mockk<ICommentDAO>(relaxed = true),
        scoringService = mockk<IScoringService>(relaxed = true),
        scoringDao = mockk<IScoringDao>(relaxed = true),
        challengeProgressService = challengeProgressService,
        postRemovalDao = postRemovalDao,
    )

    private fun cameraPostRequest() = CreatePostDTO(
        authorId = authorId,
        carModelId = carModelId,
        customBrand = null,
        customModel = null,
        latitude = null,
        longitude = null,
        town = null,
        country = null,
        caption = null,
        imageBytes = ByteArray(10),
        contentType = "image/jpeg",
        source = PostSource.CAMERA,
        createdAtTimezone = "Europe/Bucharest",
    )

    private fun seededPost() = Post(
        id = postId,
        userId = authorId,
        username = "alice",
        carModelId = carModelId,
        brand = "volkswagen",
        model = "golf r",
        imageKey = "posts/test.jpg",
        source = PostSource.CAMERA,
        points = 10,
        createdAt = Instant.now(),
    )

    // ---------- 4. creation stays best-effort ----------

    @Test
    fun `createPost still succeeds when challenge evaluation fails`() = runTest {
        val postDao = mockk<IPostDAO>()
        coEvery { postDao.insert(any()) } returns InsertedPost(postId, Instant.now())

        val challengeProgressService = mockk<IChallengeProgressService>()
        coEvery {
            challengeProgressService.evaluatePostForActiveChallenge(any(), any(), any(), any())
        } throws IllegalStateException("simulated challenge evaluation failure")

        val created = service(challengeProgressService, mockk(relaxed = true), postDao).createPost(cameraPostRequest())

        assertEquals(postId, created, "A failed challenge evaluation must not fail post creation")
        coVerify(exactly = 1) { challengeProgressService.evaluatePostForActiveChallenge(any(), any(), any(), any()) }
    }

    // ---------- 2. removal does not swallow reconciliation failures ----------

    @Test
    fun `deletePostAsAuthor propagates a removal failure instead of swallowing it`() = runTest {
        val postDao = mockk<IPostDAO>()
        coEvery { postDao.findById(postId) } returns seededPost()

        val challengeProgressService = mockk<IChallengeProgressService>()
        every { challengeProgressService.contributionRevokePolicy() } returns { true }

        val postRemovalDao = mockk<IPostRemovalDAO>()
        coEvery {
            postRemovalDao.removePostAtomically(any(), any())
        } throws IllegalStateException("simulated reconciliation failure")

        assertThrows<IllegalStateException> {
            kotlinx.coroutines.runBlocking {
                service(challengeProgressService, postRemovalDao, postDao).deletePostAsAuthor(postId, authorId)
            }
        }
    }

    @Test
    fun `removePostAsModerator propagates a removal failure instead of swallowing it`() = runTest {
        val postDao = mockk<IPostDAO>()
        coEvery { postDao.findById(postId) } returns seededPost()

        val challengeProgressService = mockk<IChallengeProgressService>()
        every { challengeProgressService.contributionRevokePolicy() } returns { true }

        val postRemovalDao = mockk<IPostRemovalDAO>()
        coEvery {
            postRemovalDao.removePostAtomically(any(), any())
        } throws IllegalStateException("simulated reconciliation failure")

        assertThrows<IllegalStateException> {
            kotlinx.coroutines.runBlocking {
                service(challengeProgressService, postRemovalDao, postDao).removePostAsModerator(postId, UUID.randomUUID())
            }
        }
    }

    @Test
    fun `removePostAsModerator throws PostNotFoundException instead of reporting success when deletedRows is 0`() = runTest {
        val postDao = mockk<IPostDAO>()
        coEvery { postDao.findById(postId) } returns seededPost()

        val challengeProgressService = mockk<IChallengeProgressService>()
        every { challengeProgressService.contributionRevokePolicy() } returns { true }

        val postRemovalDao = mockk<IPostRemovalDAO>()
        coEvery { postRemovalDao.removePostAtomically(any(), any(), any()) } returns
            PostRemovalOutcome(deletedRows = 0, reversals = emptyList())

        assertThrows<PostNotFoundException> {
            kotlinx.coroutines.runBlocking {
                service(challengeProgressService, postRemovalDao, postDao)
                    .removePostAsModerator(postId, UUID.randomUUID(), ModerationReason.SPAM_OR_MISLEADING, null)
            }
        }
    }

    @Test
    fun `deletePostAsAuthor applies the service-owned revoke policy to the removal transaction`() = runTest {
        val postDao = mockk<IPostDAO>()
        coEvery { postDao.findById(postId) } returns seededPost()

        val policy: (Instant) -> Boolean = { true }
        val challengeProgressService = mockk<IChallengeProgressService>()
        every { challengeProgressService.contributionRevokePolicy() } returns policy

        val postRemovalDao = mockk<IPostRemovalDAO>()
        coEvery { postRemovalDao.removePostAtomically(any(), any()) } returns
            PostRemovalOutcome(deletedRows = 1, reversals = emptyList())

        service(challengeProgressService, postRemovalDao, postDao).deletePostAsAuthor(postId, authorId)

        coVerify(exactly = 1) { postRemovalDao.removePostAtomically(postId, policy) }
    }
}
