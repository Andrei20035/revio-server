package features.like

import com.revio.server.features.post.PostTable
import com.revio.server.features.user.UserTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * See V38__like_notification_participation.sql for the full rationale — the "announced likers
 * cursor" behind the 60-minute like-notification aggregation window and the unlike/re-like rule
 * from plan §8.1 / §18 step 5.2.
 */
object LikeNotificationParticipationTable : UUIDTable("notification_like_participation") {
    val postId = uuid("post_id").references(PostTable.id, onDelete = ReferenceOption.CASCADE)
    val likerId = uuid("liker_id").references(UserTable.id, onDelete = ReferenceOption.CASCADE)
    val windowStartedAt = timestamp("window_started_at")
    val committed = bool("committed").default(false)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)

    init {
        uniqueIndex(postId, likerId)
    }
}
