package service

import com.revio.server.core.storage.IStorageService
import com.revio.server.features.car_model.ICarModelDAO
import com.revio.server.features.challenge.IChallengeProgressService
import com.revio.server.features.post.IPostDAO
import com.revio.server.features.post.IPostRemovalDAO
import com.revio.server.features.post.Post
import com.revio.server.features.post.PostServiceImpl
import com.revio.server.features.post.PostSource
import com.revio.server.features.post.PostVehicleLockedException
import com.revio.server.features.post.dto.UpdatePostRequest
import com.revio.server.features.scoring.IScoringDao
import com.revio.server.features.scoring.IScoringService
import features.comment.ICommentDAO
import features.like.ILikeDAO
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

/**
 * PostService.updatePostAsAuthor's vehicle lock: once a post has ever contributed to a challenge
 * (IChallengeProgressService.hasContributions), its carModelId/customBrand/customModel can no
 * longer change — see the plan's §5/§6/§9-Pas3. Covers rows 1-9 of the plan's §7 test matrix.
 */
class PostServiceVehicleLockTest {

    private val authorId = UUID.randomUUID()
    private val postId = UUID.randomUUID()
    private val originalCarModelId = UUID.randomUUID()
    private val otherCarModelId = UUID.randomUUID()

    private fun service(
        challengeProgressService: IChallengeProgressService,
        postDao: IPostDAO,
    ) = PostServiceImpl(
        postDao = postDao,
        storageService = mockk<IStorageService>(relaxed = true),
        carModelDao = mockk<ICarModelDAO>(relaxed = true).also { coEvery { it.exists(any()) } returns true },
        likeDao = mockk<ILikeDAO>(relaxed = true),
        commentDao = mockk<ICommentDAO>(relaxed = true),
        scoringService = mockk<IScoringService>(relaxed = true),
        scoringDao = mockk<IScoringDao>(relaxed = true),
        challengeProgressService = challengeProgressService,
        postRemovalDao = mockk<IPostRemovalDAO>(relaxed = true),
    )

    private fun catalogPost(carModelId: UUID = originalCarModelId, brand: String = "volkswagen", model: String = "golf r") = Post(
        id = postId,
        userId = authorId,
        username = "alice",
        carModelId = carModelId,
        brand = brand,
        model = model,
        imageKey = "posts/test.jpg",
        source = PostSource.CAMERA,
        points = 10,
        createdAt = Instant.now(),
    )

    private fun customPost(brand: String = "opel", model: String = "corsa") = Post(
        id = postId,
        userId = authorId,
        username = "alice",
        carModelId = null,
        brand = brand,
        model = model,
        imageKey = "posts/test.jpg",
        source = PostSource.CAMERA,
        points = 10,
        createdAt = Instant.now(),
    )

    private fun postDaoWith(post: Post): IPostDAO {
        val postDao = mockk<IPostDAO>()
        coEvery { postDao.findById(postId) } returns post
        coEvery { postDao.updateById(any(), any(), any(), any(), any()) } returns 1
        return postDao
    }

    private fun challengeService(hasContributions: Boolean): IChallengeProgressService {
        val service = mockk<IChallengeProgressService>()
        coEvery { service.hasContributions(postId) } returns hasContributions
        return service
    }

    // ---------- 1. no contribution -> vehicle editable ----------

    @Test
    fun `row 1 - post without contribution allows changing the vehicle`() = runTest {
        val postDao = postDaoWith(catalogPost())
        val challengeProgressService = challengeService(hasContributions = false)

        service(challengeProgressService, postDao).updatePostAsAuthor(
            postId, authorId, UpdatePostRequest(carModelId = otherCarModelId),
        )

        coVerify(exactly = 1) { postDao.updateById(postId, otherCarModelId, null, null, null) }
    }

    // ---------- 2/3/8/9. contribution present (active or ended/finalized) -> blocked ----------

    @Test
    fun `row 2,3,8,9 - post with a contribution blocks a vehicle change regardless of challenge status`() = runTest {
        val postDao = postDaoWith(catalogPost())
        val challengeProgressService = challengeService(hasContributions = true)

        assertThrows<PostVehicleLockedException> {
            kotlinx.coroutines.runBlocking {
                service(challengeProgressService, postDao).updatePostAsAuthor(
                    postId, authorId, UpdatePostRequest(carModelId = otherCarModelId),
                )
            }
        }
        coVerify(exactly = 0) { postDao.updateById(any(), any(), any(), any(), any()) }
    }

    // ---------- 4. same selection resubmitted -> permitted no-op on the vehicle ----------

    @Test
    fun `row 4 - resubmitting the same carModelId is a no-op even when the post has a contribution`() = runTest {
        val postDao = postDaoWith(catalogPost())
        val challengeProgressService = challengeService(hasContributions = true)

        service(challengeProgressService, postDao).updatePostAsAuthor(
            postId, authorId, UpdatePostRequest(carModelId = originalCarModelId),
        )

        coVerify(exactly = 1) { postDao.updateById(postId, originalCarModelId, null, null, null) }
        coVerify(exactly = 0) { challengeProgressService.hasContributions(postId) }
    }

    // ---------- 5. caption-only edit, vehicle resubmitted identically -> permitted ----------

    @Test
    fun `row 5 - caption-only change with the vehicle resubmitted identically is permitted`() = runTest {
        val postDao = postDaoWith(catalogPost())
        val challengeProgressService = challengeService(hasContributions = true)

        service(challengeProgressService, postDao).updatePostAsAuthor(
            postId, authorId, UpdatePostRequest(carModelId = originalCarModelId, caption = "new caption"),
        )

        coVerify(exactly = 1) { postDao.updateById(postId, originalCarModelId, null, null, "new caption") }
    }

    // ---------- 6. carModelId -> custom brand/model is a vehicle change -> blocked ----------

    @Test
    fun `row 6 - switching from carModelId to custom brand+model is a vehicle change and is blocked when locked`() = runTest {
        val postDao = postDaoWith(catalogPost())
        val challengeProgressService = challengeService(hasContributions = true)

        assertThrows<PostVehicleLockedException> {
            kotlinx.coroutines.runBlocking {
                service(challengeProgressService, postDao).updatePostAsAuthor(
                    postId, authorId, UpdatePostRequest(customBrand = "Audi", customModel = "RS6"),
                )
            }
        }
        coVerify(exactly = 0) { postDao.updateById(any(), any(), any(), any(), any()) }
    }

    // ---------- 7. custom -> carModelId is permitted (a custom post never has a contribution) ----------

    @Test
    fun `row 7 - switching from custom brand+model to carModelId is permitted`() = runTest {
        val postDao = postDaoWith(customPost())
        val challengeProgressService = challengeService(hasContributions = false)

        service(challengeProgressService, postDao).updatePostAsAuthor(
            postId, authorId, UpdatePostRequest(carModelId = otherCarModelId),
        )

        coVerify(exactly = 1) { postDao.updateById(postId, otherCarModelId, null, null, null) }
    }

    // ---------- caption-only edit with a NO contribution still requires resubmitting the vehicle ----------

    @Test
    fun `hasContributions is only checked when the vehicle actually changes`() = runTest {
        val postDao = postDaoWith(catalogPost())
        val challengeProgressService = challengeService(hasContributions = false)

        service(challengeProgressService, postDao).updatePostAsAuthor(
            postId, authorId, UpdatePostRequest(carModelId = originalCarModelId, caption = "hello"),
        )

        coVerify(exactly = 0) { challengeProgressService.hasContributions(any()) }
    }

    @Test
    fun `changing the vehicle checks hasContributions exactly once`() = runTest {
        val postDao = postDaoWith(catalogPost())
        val challengeProgressService = challengeService(hasContributions = false)

        service(challengeProgressService, postDao).updatePostAsAuthor(
            postId, authorId, UpdatePostRequest(carModelId = otherCarModelId),
        )

        coVerify(exactly = 1) { challengeProgressService.hasContributions(postId) }
    }

    @Test
    fun `PostVehicleLockedException carries the postId`() = runTest {
        val postDao = postDaoWith(catalogPost())
        val challengeProgressService = challengeService(hasContributions = true)

        val exception = assertThrows<PostVehicleLockedException> {
            kotlinx.coroutines.runBlocking {
                service(challengeProgressService, postDao).updatePostAsAuthor(
                    postId, authorId, UpdatePostRequest(carModelId = otherCarModelId),
                )
            }
        }

        assertEquals(postId, exception.postId)
    }
}
