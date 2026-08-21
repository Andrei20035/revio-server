package service

import com.revio.server.core.storage.IStorageService
import com.revio.server.features.car_model.ICarModelDAO
import com.revio.server.features.challenge.IChallengeProgressService
import com.revio.server.features.post.IPostDAO
import com.revio.server.features.post.IPostRemovalDAO
import com.revio.server.features.post.InsertedPost
import com.revio.server.features.post.PostCreationException
import com.revio.server.features.post.PostServiceImpl
import com.revio.server.features.post.PostSource
import com.revio.server.features.post.dto.CreatePostDTO
import com.revio.server.features.scoring.IScoringDao
import com.revio.server.features.scoring.IScoringService
import features.comment.ICommentDAO
import features.like.ILikeDAO
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Failure injection per etapa a `PostServiceImpl.createPost` (pas 3.4a) — confirmă sau infirmă
 * C5: dacă `postDao.insert` reușește dar o etapă ulterioară eșuează, rândul din DB rămâne
 * comis (mock-ul DAO nu poate fi "rollback-uit" din exterior — tranzacția lui e proprie), în
 * timp ce serviciul aruncă totuși o excepție către client. Un retry de client ar produce deci
 * o postare duplicat.
 */
class PostServiceTest {

    private val postDao = mockk<IPostDAO>()
    private val storageService = mockk<IStorageService>(relaxed = true)
    private val carModelDao = mockk<ICarModelDAO>(relaxed = true)
    private val likeDao = mockk<ILikeDAO>(relaxed = true)
    private val commentDao = mockk<ICommentDAO>(relaxed = true)
    private val scoringService = mockk<IScoringService>()
    private val scoringDao = mockk<IScoringDao>(relaxed = true)
    private val challengeProgressService = mockk<IChallengeProgressService>()
    private val postRemovalDao = mockk<IPostRemovalDAO>(relaxed = true)

    private val service = PostServiceImpl(
        postDao = postDao,
        storageService = storageService,
        carModelDao = carModelDao,
        likeDao = likeDao,
        commentDao = commentDao,
        scoringService = scoringService,
        scoringDao = scoringDao,
        challengeProgressService = challengeProgressService,
        postRemovalDao = postRemovalDao,
    )

    private val authorId = UUID.randomUUID()
    private val postId = UUID.randomUUID()

    private fun validRequest(source: PostSource = PostSource.GALLERY, carModelId: UUID? = null) = CreatePostDTO(
        authorId = authorId,
        carModelId = carModelId,
        customBrand = if (carModelId == null) "Toyota" else null,
        customModel = if (carModelId == null) "Corolla" else null,
        latitude = null,
        longitude = null,
        town = null,
        country = null,
        caption = null,
        imageBytes = byteArrayOf(1, 2, 3),
        contentType = "image/jpeg",
        source = source,
    )

    // ---------- 1. Validare ----------

    @Test
    fun `validation failure never touches storage, insert, or scoring`() = runTest {
        val invalid = validRequest().copy(contentType = "application/pdf")

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { service.createPost(invalid) }
        }

        coVerify(exactly = 0) { storageService.uploadImage(any(), any(), any()) }
        coVerify(exactly = 0) { postDao.insert(any()) }
        coVerify(exactly = 0) { scoringService.onPostCreated(any(), any(), any(), any(), any()) }
    }

    // ---------- 2. Storage ----------

    @Test
    fun `storage failure propagates before insert is ever attempted`() = runTest {
        coEvery { storageService.uploadImage(any(), any(), any()) } throws RuntimeException("R2 unreachable")

        assertThrows(RuntimeException::class.java) {
            runBlocking { service.createPost(validRequest()) }
        }

        coVerify(exactly = 0) { postDao.insert(any()) }
    }

    // ---------- 3. Insert ----------

    @Test
    fun `insert failure deletes the uploaded image and never calls scoring — no duplicate risk`() = runTest {
        coEvery { postDao.insert(any()) } throws RuntimeException("DB unavailable")

        val ex = assertThrows(PostCreationException::class.java) {
            runBlocking { service.createPost(validRequest()) }
        }
        assertEquals("Failed to create post for user $authorId", ex.message)

        coVerify(exactly = 1) { storageService.deleteImage(any()) }
        coVerify(exactly = 0) { scoringService.onPostCreated(any(), any(), any(), any(), any()) }
    }

    // ---------- 4. Scoring (C5 — fixed by pas 3.4b) ----------

    @Test
    fun `scoring failure is best-effort — post creation still succeeds, no retry, no duplicate`() = runTest {
        coEvery { postDao.insert(any()) } returns InsertedPost(id = postId, createdAt = Instant.now())
        coEvery { scoringService.onPostCreated(any(), any(), any(), any(), any()) } throws RuntimeException("scoring DB down")

        val result = service.createPost(validRequest())

        // The row postDao.insert already committed is never undone, and a scoring failure no
        // longer turns it into a client-visible failure: the client gets its success response and
        // has no reason to retry, so the duplicate-post risk from C5 cannot occur.
        assertEquals(postId, result)
        coVerify(exactly = 1) { postDao.insert(any()) }
        coVerify(exactly = 0) { storageService.deleteImage(any()) }
    }

    // ---------- 5. Challenge ----------

    @Test
    fun `challenge evaluation failure is swallowed — post creation still succeeds`() = runTest {
        val carModelId = UUID.randomUUID()
        coEvery { carModelDao.exists(carModelId) } returns true
        coEvery { postDao.insert(any()) } returns InsertedPost(id = postId, createdAt = Instant.now())
        coEvery { scoringService.onPostCreated(any(), any(), any(), any(), any()) } returns Unit
        coEvery {
            challengeProgressService.evaluatePostForActiveChallenge(any(), any(), any(), any())
        } throws RuntimeException("challenge lookup failed")

        val result = service.createPost(validRequest(source = PostSource.CAMERA, carModelId = carModelId))

        assertEquals(postId, result)
        coVerify(exactly = 0) { storageService.deleteImage(any()) }
    }
}
