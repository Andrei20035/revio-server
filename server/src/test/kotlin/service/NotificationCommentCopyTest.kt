package service

import com.revio.server.features.notification.renderCommentCopy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure unit coverage for renderCommentCopy — the comment notification copy thresholds from plan
 * §8.2, rendered per plan §18 step 4.3. No DB: same reasoning as PushDispatchServiceTest's
 * classifyResponse coverage.
 */
class NotificationCommentCopyTest {

    @Test
    fun `1 actor names them, empty body`() {
        val (title, body) = renderCommentCopy(actorCount = 1, actorUsername = "Alex")
        assertEquals("Alex commented on your spot", title)
        assertEquals("", body)
    }

    @Test
    fun `2 actors uses singular 'other'`() {
        val (title, body) = renderCommentCopy(actorCount = 2, actorUsername = "Alex")
        assertEquals("Alex and 1 other joined the conversation", title)
        assertEquals("", body)
    }

    @Test
    fun `3 actors uses plural 'others'`() {
        val (title, _) = renderCommentCopy(actorCount = 3, actorUsername = "Alex")
        assertEquals("Alex and 2 others joined the conversation", title)
    }

    @Test
    fun `4 actors (the top of the 2-4 band) uses plural 'others'`() {
        val (title, _) = renderCommentCopy(actorCount = 4, actorUsername = "Alex")
        assertEquals("Alex and 3 others joined the conversation", title)
    }

    @Test
    fun `5 actors switches to the volume title with a body`() {
        val (title, body) = renderCommentCopy(actorCount = 5, actorUsername = "Alex")
        assertEquals("Your spot has a conversation going", title)
        assertEquals("5 people commented.", body)
    }

    @Test
    fun `12 actors still uses the volume title, body reflects the real count`() {
        val (title, body) = renderCommentCopy(actorCount = 12, actorUsername = "Alex")
        assertEquals("Your spot has a conversation going", title)
        assertEquals("12 people commented.", body)
    }

    @Test
    fun `missing username falls back to Someone at 1 actor`() {
        val (title, _) = renderCommentCopy(actorCount = 1, actorUsername = null)
        assertEquals("Someone commented on your spot", title)
    }

    @Test
    fun `missing username falls back to Someone in the 2-4 band too`() {
        val (title, _) = renderCommentCopy(actorCount = 3, actorUsername = null)
        assertEquals("Someone and 2 others joined the conversation", title)
    }

    @Test
    fun `missing username has no effect on the 5+ volume title (it never names anyone)`() {
        val (title, _) = renderCommentCopy(actorCount = 5, actorUsername = null)
        assertEquals("Your spot has a conversation going", title)
    }

    @Test
    fun `every rendered variant stays under the title and body character limits`() {
        val usernames = listOf("Alex", "Bo", "Christopherson", null)
        val counts = listOf(1, 2, 3, 4, 5, 12, 100)
        for (username in usernames) {
            for (count in counts) {
                val (title, body) = renderCommentCopy(count, username)
                assertTrue(title.length <= 55, "title too long ($count actors, username=$username): \"$title\" (${title.length})")
                assertTrue(body.length <= 70, "body too long ($count actors, username=$username): \"$body\" (${body.length})")
            }
        }
    }
}
