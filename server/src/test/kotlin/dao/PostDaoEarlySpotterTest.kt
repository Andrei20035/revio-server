package dao

import com.revio.server.features.post.PostDAO
import com.revio.server.features.post.PostTable
import com.revio.server.features.user.UserDao
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import testutils.TestDatabaseFactory
import testutils.UserTestSeed
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostDaoEarlySpotterTest {

    private val postDAO = PostDAO()
    private val userDAO = UserDao()

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

    private fun seedPost(authorId: UUID): UUID = transaction {
        PostTable.insert {
            it[PostTable.userId] = authorId
            it[PostTable.imageKey] = "posts/test.jpg"
            it[PostTable.carModelId] = null
            it[PostTable.customBrand] = "BMW"
            it[PostTable.customModel] = "M3"
        }[PostTable.id].value
    }

    // --- Test 9a: post cu autor early spotter → feed DTO contine flagurile ---

    @Test
    fun `feed post from early spotter author carries authorIsEarlySpotter true and number`() {
        val cred = UserTestSeed.seedAuthCredential("early@example.com")
        val earlyUserId = kotlinx.coroutines.runBlocking {
            userDAO.createUser(UserTestSeed.buildUser(cred.authCredentialId, username = "earlyauthor")).userId
        }
        seedPost(earlyUserId)

        val feed = kotlinx.coroutines.runBlocking {
            postDAO.listFeed(limit = 10, cursorCreatedAt = null, cursorPostId = null, excludeUserId = null)
        }

        assertEquals(1, feed.size)
        val post = feed.first()
        assertTrue(post.authorIsEarlySpotter)
        assertEquals(1, post.authorEarlySpotterNumber)
    }

    // --- Test 9b: post cu autor non-early-spotter → feed DTO contine false/null ---

    @Test
    fun `feed post from non-early-spotter author carries authorIsEarlySpotter false and null number`() {
        val cred = UserTestSeed.seedAuthCredential("regular@example.com")
        val regularUserId = UserTestSeed.seedUser(cred.authCredentialId, username = "regularauthor")
        seedPost(regularUserId)

        val feed = kotlinx.coroutines.runBlocking {
            postDAO.listFeed(limit = 10, cursorCreatedAt = null, cursorPostId = null, excludeUserId = null)
        }

        assertEquals(1, feed.size)
        val post = feed.first()
        assertFalse(post.authorIsEarlySpotter)
        assertNull(post.authorEarlySpotterNumber)
    }

    // --- Test 9c: feed mixt – valorile sunt corecte per autor ---

    @Test
    fun `feed with mixed authors carries correct early spotter flags per post`() {
        val earlyCred = UserTestSeed.seedAuthCredential("early2@example.com")
        val earlyUserId = kotlinx.coroutines.runBlocking {
            userDAO.createUser(UserTestSeed.buildUser(earlyCred.authCredentialId, username = "earlyauthor2")).userId
        }
        val regularCred = UserTestSeed.seedAuthCredential("regular2@example.com")
        val regularUserId = UserTestSeed.seedUser(regularCred.authCredentialId, username = "regularauthor2")

        seedPost(earlyUserId)
        seedPost(regularUserId)

        val feed = kotlinx.coroutines.runBlocking {
            postDAO.listFeed(limit = 10, cursorCreatedAt = null, cursorPostId = null, excludeUserId = null)
        }

        assertEquals(2, feed.size)
        val byAuthor = feed.associateBy { it.username }

        val earlyPost = byAuthor["earlyauthor2"]!!
        assertTrue(earlyPost.authorIsEarlySpotter)
        assertEquals(1, earlyPost.authorEarlySpotterNumber)

        val regularPost = byAuthor["regularauthor2"]!!
        assertFalse(regularPost.authorIsEarlySpotter)
        assertNull(regularPost.authorEarlySpotterNumber)
    }
}
