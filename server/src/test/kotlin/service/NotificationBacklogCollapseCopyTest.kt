package service

import com.revio.server.features.notification.NotificationCategory
import com.revio.server.features.notification.OutboxNotificationInfo
import com.revio.server.features.notification.buildCollapsedCopy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Pure unit coverage for buildCollapsedCopy — the combined title/body rendered for a collapsed
 * backlog send (plan §14 / §18 step 5.5). No DB: same reasoning as NotificationCommentCopyTest's
 * renderCommentCopy coverage.
 */
class NotificationBacklogCollapseCopyTest {

    private fun infoOf(category: NotificationCategory) = OutboxNotificationInfo(
        recipientId = UUID.randomUUID(),
        category = category,
        title = "irrelevant",
        body = "irrelevant",
        deepLink = null,
        postId = null,
        commentId = null,
        challengeId = null,
        enqueuedDeltaPoints = null,
    )

    @Test
    fun `only likes renders a plural likes summary`() {
        val (title, body) = buildCollapsedCopy(List(3) { infoOf(NotificationCategory.LIKES) })
        assertEquals("While you were away", title)
        assertEquals("You have 3 likes.", body)
    }

    @Test
    fun `a single like uses singular wording`() {
        val (_, body) = buildCollapsedCopy(listOf(infoOf(NotificationCategory.LIKES)))
        assertEquals("You have 1 like.", body)
    }

    @Test
    fun `likes and comments together are joined with 'and'`() {
        val infos = listOf(
            infoOf(NotificationCategory.LIKES),
            infoOf(NotificationCategory.LIKES),
            infoOf(NotificationCategory.COMMENTS),
        )
        val (_, body) = buildCollapsedCopy(infos)
        assertEquals("You have 2 likes and 1 comment.", body)
    }

    @Test
    fun `a category outside likes and comments falls back to a generic update count`() {
        val infos = listOf(infoOf(NotificationCategory.REMINDERS), infoOf(NotificationCategory.DISCOVERY))
        val (_, body) = buildCollapsedCopy(infos)
        assertEquals("You have 2 updates.", body)
    }
}
