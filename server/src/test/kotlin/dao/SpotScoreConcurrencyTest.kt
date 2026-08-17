package dao

import com.revio.server.features.post.PostSource
import com.revio.server.features.post.PostTable
import com.revio.server.features.scoring.ScoringDaoImpl
import com.revio.server.features.user.UserDao
import com.revio.server.features.user.UserTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import testutils.CommentTestSeed
import testutils.TestDatabaseFactory
import java.util.UUID

/**
 * Guards against the read-modify-write race this fix replaces: two concurrent increments to the
 * same user's spot_score (or the same post's points) must both land, not lose one to a
 * last-writer-wins overwrite. Uses real threads (Dispatchers.IO), not runTest's virtual-time
 * dispatcher — see the "H. concurrency" section of ChallengeProgressDaoTest for the same pattern.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SpotScoreConcurrencyTest {

    private val userDao = UserDao()
    private val scoringDao = ScoringDaoImpl()

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

    private fun spotScore(userId: UUID): Int = transaction {
        UserTable.select(UserTable.spotScore)
            .where { UserTable.id eq userId }
            .single()[UserTable.spotScore]
    }

    private fun postPoints(postId: UUID): Int = transaction {
        PostTable.select(PostTable.points)
            .where { PostTable.id eq postId }
            .single()[PostTable.points]
    }

    private fun seedCameraPost(ownerUserId: UUID): UUID = transaction {
        PostTable.insert {
            it[PostTable.userId] = ownerUserId
            it[PostTable.imageKey] = "posts/test.jpg"
            it[PostTable.customBrand] = "bmw"
            it[PostTable.customModel] = "m3"
            it[PostTable.postSource] = PostSource.CAMERA.name
        }[PostTable.id].value
    }

    @Test
    fun `4 concurrent incrementSpotScore calls all land`() {
        val user = CommentTestSeed.seedUser()

        runBlocking(Dispatchers.IO) {
            (1..4).map {
                async(Dispatchers.IO) { userDao.incrementSpotScore(user.userId, 1) }
            }.awaitAll()
        }

        assertEquals(4, spotScore(user.userId))
    }

    @Test
    fun `4 concurrent applyEngagementPoints calls all land on both post points and spot_score`() {
        val owner = CommentTestSeed.seedUser(username = "owner")
        val postId = seedCameraPost(owner.userId)

        runBlocking(Dispatchers.IO) {
            (1..4).map {
                async(Dispatchers.IO) { scoringDao.applyEngagementPoints(owner.userId, postId, 1) }
            }.awaitAll()
        }

        assertEquals(4, postPoints(postId))
        assertEquals(4, spotScore(owner.userId))
    }
}
