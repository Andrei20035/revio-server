package com.revio.server.features.notification

import com.revio.server.features.challenge.ChallengeTable
import com.revio.server.features.comment.CommentTable
import com.revio.server.features.post.PostTable
import com.revio.server.features.user.UserTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Which notification-preference bucket a row belongs to (see user_notification_prefs). ACCOUNT
 * covers the pre-existing moderation notifications (post removal, bans, ...); the rest are the
 * social/broadcast categories added by V36__notifications_social.sql, plus CHALLENGES added by
 * V41__notification_challenges_category.sql.
 */
enum class NotificationCategory {
    ACCOUNT,
    LIKES,
    COMMENTS,
    DISCOVERY,
    REMINDERS,
    CHALLENGES,
    ;

    companion object {
        /** Parses a `?category=` query param. @throws IllegalArgumentException on an unknown value. */
        fun fromParam(value: String): NotificationCategory =
            entries.find { it.name == value }
                ?: throw IllegalArgumentException("Invalid category")
    }
}

/** What a notification's deep link points at, if anything. */
enum class NotificationTargetType {
    NONE,
    POST,
    COMMENT,
    CHALLENGE,
}

/** Whether a push was ever attempted for this notification row — distinct from the outbox's own per-device state. */
enum class NotificationPushState {
    NOT_SENT,
    SENT,
    SUPPRESSED,
}

/**
 * Pull-based in-app notifications (no push infrastructure exists yet — the client polls, same as
 * the activity feed). `blocking` marks a notification that must be shown as a dialog the user
 * cannot dismiss without acknowledging, on next app open — used for post-removal and ban notices.
 *
 * Extended by V36__notifications_social.sql to also carry social (likes, comments) and broadcast
 * (discovery, reminders) notifications — see [category], [dedupeKey], and the actor/target
 * columns below. Existing rows all backfill to `category = ACCOUNT` and `dedupeKey = null`.
 */
object NotificationTable : UUIDTable("user_notifications") {
    val userId = uuid("user_id").references(UserTable.id, onDelete = ReferenceOption.CASCADE)
    val type = enumerationByName("type", 32, NotificationType::class)
    val title = text("title")
    val body = text("body")
    val blocking = bool("blocking").default(false)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val readAt = timestamp("read_at").nullable()

    val category = enumerationByName("category", 16, NotificationCategory::class)
        .default(NotificationCategory.ACCOUNT)
    val dedupeKey = varchar("dedupe_key", 200).nullable()
    val targetType = enumerationByName("target_type", 16, NotificationTargetType::class)
        .default(NotificationTargetType.NONE)
    val postId = reference("post_id", PostTable, onDelete = ReferenceOption.SET_NULL).nullable()
    val commentId = reference("comment_id", CommentTable, onDelete = ReferenceOption.SET_NULL).nullable()
    val challengeId = reference("challenge_id", ChallengeTable, onDelete = ReferenceOption.SET_NULL).nullable()
    val actorCount = integer("actor_count").default(1)
    val lastActorUserId = reference("last_actor_user_id", UserTable, onDelete = ReferenceOption.SET_NULL).nullable()
    val lastActorUsername = varchar("last_actor_username", 50).nullable()
    val deepLink = text("deep_link").nullable()
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    val pushState = enumerationByName("push_state", 16, NotificationPushState::class)
        .default(NotificationPushState.NOT_SENT)

    /**
     * The leaderboard delta (`pointsToGuaranteeMoveUp`) at enqueue time, for a day-7 inactivity
     * reminder's leaderboard-conditioned copy only (plan §9 / §18, step 6.5). Null for every
     * other notification. See V40__notifications_enqueued_delta_points.sql.
     */
    val enqueuedDeltaPoints = integer("enqueued_delta_points").nullable()
}
