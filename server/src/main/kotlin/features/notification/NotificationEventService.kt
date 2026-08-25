package com.revio.server.features.notification

import com.revio.server.config.NotificationMetrics
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

interface INotificationEventService {
    /**
     * Persists (or aggregates into) a social/broadcast notification event for [recipientId], in
     * its own transaction. Pure database write — never dispatches a push; that is
     * [PushDispatchService]'s job, reading from the outbox this event may later feed via a
     * separate enqueue step.
     *
     * Idempotent/aggregating on `(recipientId, dedupeKey)` — [UNIQUE (user_id, dedupe_key)] on
     * `user_notifications`, added by V36__notifications_social.sql. The first call for a given
     * key inserts a new row with `actor_count = 1`. Every subsequent call for the *same* key
     * updates that row in place instead of inserting a duplicate: `actor_count` increments,
     * `last_actor_user_id`/`last_actor_username` move to the latest actor, and `title`/`body`/
     * `deep_link` are overwritten with the values passed in this call — the caller is expected to
     * recompute copy appropriate to the new actor count before calling again (e.g. "Alex liked
     * your spot" -> "Alex and 2 others liked your spot"), this method does not rerender it.
     *
     * @return the id of the (created or updated) `user_notifications` row.
     */
    fun record(
        recipientId: UUID,
        category: NotificationCategory,
        dedupeKey: String,
        actorId: UUID?,
        actorUsername: String?,
        targetType: NotificationTargetType = NotificationTargetType.NONE,
        postId: UUID? = null,
        commentId: UUID? = null,
        title: String,
        body: String,
        deepLink: String? = null,
        /**
         * The leaderboard delta at enqueue time (plan §9 / §18, step 6.5) — only ever set by the
         * inactivity job for a day-7 reminder's leaderboard-conditioned copy. Null (the default)
         * for every other caller/category; stored as-is, never interpreted here.
         */
        enqueuedDeltaPoints: Int? = null,
    ): UUID

    /**
     * Same as [record], but participates in a transaction already open on the calling thread
     * instead of opening its own (mirrors [INotificationDAO.insertInCurrentTransaction]) — for a
     * caller that wants the event write to share a commit boundary with another write it's
     * making (e.g. a scoring update), so a rollback of that outer transaction takes the event
     * down with it too.
     */
    fun recordInCurrentTransaction(
        recipientId: UUID,
        category: NotificationCategory,
        dedupeKey: String,
        actorId: UUID?,
        actorUsername: String?,
        targetType: NotificationTargetType = NotificationTargetType.NONE,
        postId: UUID? = null,
        commentId: UUID? = null,
        title: String,
        body: String,
        deepLink: String? = null,
        enqueuedDeltaPoints: Int? = null,
    ): UUID

    /**
     * Same aggregation mechanics as [record] (upsert on `(recipientId, dedupeKey)`, `actor_count`
     * incrementing per call), specialized for [NotificationCategory.COMMENTS]: instead of taking
     * caller-supplied `title`/`body`, it renders them itself from the row's own actor count —
     * computed and applied inside the same locked read-then-write this method already does, so
     * there's no race between "read the count to decide what copy to render" and "write it"
     * (plan §8.2's copy thresholds; plan §18 step 4.3).
     *
     * [actorUsername] may be null (falls back to "Someone" in the rendered copy). The comment's
     * own text is deliberately not a parameter here at all — it can never end up in the rendered
     * title/body or, downstream, in any push payload field (D7).
     *
     * @return the id of the (created or updated) `user_notifications` row.
     */
    fun recordComment(
        recipientId: UUID,
        dedupeKey: String,
        actorId: UUID?,
        actorUsername: String?,
        postId: UUID? = null,
        commentId: UUID? = null,
        deepLink: String? = null,
    ): UUID

    /**
     * Withdraws one actor's contribution from a [NotificationCategory.COMMENTS] row identified by
     * `(recipientId, dedupeKey)` — called when a comment is deleted and was that actor's last
     * remaining comment in the aggregation window (plan §18, step 4.4). No-op if no such row
     * exists (e.g. the comment never qualified for one in the first place — self-comment, etc).
     *
     * `actor_count` is decremented and the copy re-rendered for the new count. If the row hasn't
     * been dispatched yet (`push_state == NOT_SENT`) and the new count reaches 0, the row is
     * deleted outright — there is nothing left to aggregate and nothing has been sent yet. Once a
     * row has been dispatched, it is never deleted by this method, even if its count reaches 0:
     * the push already happened, so the inbox keeps a (now-empty) record of it rather than
     * erasing history — only its counter/copy are corrected.
     */
    fun withdrawCommentActor(recipientId: UUID, dedupeKey: String)

    /**
     * Same aggregation mechanics as [record] (upsert on `(recipientId, dedupeKey)`, `actor_count`
     * incrementing per call), specialized for [NotificationCategory.LIKES]: instead of taking
     * caller-supplied `title`/`body`, it renders them itself from the row's own actor count —
     * computed and applied inside the same locked read-then-write this method already does, so
     * there's no race between "read the count to decide what copy to render" and "write it"
     * (plan §8.1's copy thresholds; plan §18 step 5.3).
     *
     * [actorUsername] may be null (falls back to "Someone" in the rendered copy).
     *
     * @return the id of the (created or updated) `user_notifications` row.
     */
    fun recordLike(
        recipientId: UUID,
        dedupeKey: String,
        actorId: UUID?,
        actorUsername: String?,
        postId: UUID? = null,
        deepLink: String? = null,
    ): UUID

    /**
     * Withdraws one actor's contribution from a [NotificationCategory.LIKES] row identified by
     * `(recipientId, dedupeKey)` — the aggregation-side counterpart of an unlike that happens
     * while that liker's contribution is still reversible (plan §18, step 5.2's "unlike before
     * dispatch"; caller — [features.like.LikeService] — is responsible for only calling this
     * when the liker's own participation row is not yet committed). Mirrors
     * [withdrawCommentActor]'s decrement-or-delete-and-rerender shape: `actor_count` is
     * decremented and the copy re-rendered per plan §8.1's thresholds (plan §18 step 5.3) for the
     * new count; if it reaches 0 while `push_state == NOT_SENT` the row is deleted outright
     * instead. No-op if no such row exists.
     */
    fun withdrawLikeActor(recipientId: UUID, dedupeKey: String)
}

/**
 * Comment notification copy per plan §8.2's thresholds: 1 actor names them; 2-4 actors names the
 * latest plus a correctly-pluralized "other(s)" count; 5+ actors switches to a volume-style title
 * with a body. [actorUsername] null falls back to "Someone". Pure function — see
 * PushDispatchService.classifyResponse/NotificationOutboxProcessor.nextRetryDecision for the same
 * reasoning on why this is pulled out for direct unit testing.
 */
internal fun renderCommentCopy(actorCount: Int, actorUsername: String?): Pair<String, String> {
    val name = actorUsername ?: "Someone"
    return when {
        actorCount <= 1 -> "$name commented on your spot" to ""
        actorCount <= 4 -> {
            val others = actorCount - 1
            val othersWord = if (others == 1) "other" else "others"
            "$name and $others $othersWord joined the conversation" to ""
        }
        else -> "Your spot has a conversation going" to "$actorCount people commented."
    }
}

/**
 * Like notification copy per plan §8.1's thresholds (plan §18, step 5.3): 1 liker names them
 * ("liked", singular — never "likes" as a count noun at this threshold); 2-3 likers names the
 * latest plus a correctly-pluralized "other(s)" count; 4+ likers switches to a volume-style title
 * with a body ("N new likes since you posted." — "likes" pluralized against the actual count, so
 * n=1 would read "1 new like" rather than "1 new likes", though this branch is only ever reached
 * at n>=4). [actorUsername] null falls back to "Someone". Pure function — same reasoning as
 * [renderCommentCopy] for why this is pulled out for direct unit testing.
 */
internal fun renderLikeCopy(actorCount: Int, actorUsername: String?): Pair<String, String> {
    val name = actorUsername ?: "Someone"
    return when {
        actorCount <= 1 -> "$name liked your spot" to ""
        actorCount <= 3 -> {
            val others = actorCount - 1
            val othersWord = if (others == 1) "other" else "others"
            "$name and $others $othersWord liked your spot" to ""
        }
        else -> {
            val likeWord = if (actorCount == 1) "like" else "likes"
            "Your spot is getting noticed" to "$actorCount new $likeWord since you posted."
        }
    }
}

class NotificationEventService : INotificationEventService {

    override fun record(
        recipientId: UUID,
        category: NotificationCategory,
        dedupeKey: String,
        actorId: UUID?,
        actorUsername: String?,
        targetType: NotificationTargetType,
        postId: UUID?,
        commentId: UUID?,
        title: String,
        body: String,
        deepLink: String?,
        enqueuedDeltaPoints: Int?,
    ): UUID = transaction {
        recordInCurrentTransaction(
            recipientId, category, dedupeKey, actorId, actorUsername, targetType, postId, commentId, title, body, deepLink,
            enqueuedDeltaPoints,
        )
    }

    override fun recordInCurrentTransaction(
        recipientId: UUID,
        category: NotificationCategory,
        dedupeKey: String,
        actorId: UUID?,
        actorUsername: String?,
        targetType: NotificationTargetType,
        postId: UUID?,
        commentId: UUID?,
        title: String,
        body: String,
        deepLink: String?,
        enqueuedDeltaPoints: Int?,
    ): UUID = upsert(recipientId, category, dedupeKey, actorId, actorUsername, targetType, postId, commentId, deepLink, enqueuedDeltaPoints) { _ ->
        title to body
    }

    override fun recordComment(
        recipientId: UUID,
        dedupeKey: String,
        actorId: UUID?,
        actorUsername: String?,
        postId: UUID?,
        commentId: UUID?,
        deepLink: String?,
    ): UUID = transaction {
        upsert(
            recipientId = recipientId,
            category = NotificationCategory.COMMENTS,
            dedupeKey = dedupeKey,
            actorId = actorId,
            actorUsername = actorUsername,
            targetType = NotificationTargetType.POST,
            postId = postId,
            commentId = commentId,
            deepLink = deepLink,
            renderCopy = { newActorCount -> renderCommentCopy(newActorCount, actorUsername) },
        )
    }

    override fun recordLike(
        recipientId: UUID,
        dedupeKey: String,
        actorId: UUID?,
        actorUsername: String?,
        postId: UUID?,
        deepLink: String?,
    ): UUID = transaction {
        upsert(
            recipientId = recipientId,
            category = NotificationCategory.LIKES,
            dedupeKey = dedupeKey,
            actorId = actorId,
            actorUsername = actorUsername,
            targetType = NotificationTargetType.POST,
            postId = postId,
            commentId = null,
            deepLink = deepLink,
            renderCopy = { newActorCount -> renderLikeCopy(newActorCount, actorUsername) },
        )
    }

    override fun withdrawCommentActor(recipientId: UUID, dedupeKey: String): Unit = transaction {
        val existing = NotificationTable
            .select(NotificationTable.id, NotificationTable.actorCount, NotificationTable.pushState, NotificationTable.lastActorUsername)
            .where { (NotificationTable.userId eq recipientId) and (NotificationTable.dedupeKey eq dedupeKey) }
            .forUpdate()
            .singleOrNull() ?: return@transaction

        val id = existing[NotificationTable.id].value
        val newActorCount = (existing[NotificationTable.actorCount] - 1).coerceAtLeast(0)
        val pushState = existing[NotificationTable.pushState]

        if (newActorCount == 0 && pushState == NotificationPushState.NOT_SENT) {
            NotificationTable.deleteWhere { NotificationTable.id eq id }
            return@transaction
        }

        val (title, body) = renderCommentCopy(newActorCount, existing[NotificationTable.lastActorUsername])
        NotificationTable.update({ NotificationTable.id eq id }) {
            it[NotificationTable.actorCount] = newActorCount
            it[NotificationTable.title] = title
            it[NotificationTable.body] = body
            it[NotificationTable.updatedAt] = Instant.now()
        }
        Unit
    }

    override fun withdrawLikeActor(recipientId: UUID, dedupeKey: String): Unit = transaction {
        val existing = NotificationTable
            .select(NotificationTable.id, NotificationTable.actorCount, NotificationTable.pushState, NotificationTable.lastActorUsername)
            .where { (NotificationTable.userId eq recipientId) and (NotificationTable.dedupeKey eq dedupeKey) }
            .forUpdate()
            .singleOrNull() ?: return@transaction

        val id = existing[NotificationTable.id].value
        val newActorCount = (existing[NotificationTable.actorCount] - 1).coerceAtLeast(0)
        val pushState = existing[NotificationTable.pushState]

        if (newActorCount == 0 && pushState == NotificationPushState.NOT_SENT) {
            NotificationTable.deleteWhere { NotificationTable.id eq id }
            return@transaction
        }

        val (title, body) = renderLikeCopy(newActorCount, existing[NotificationTable.lastActorUsername])
        NotificationTable.update({ NotificationTable.id eq id }) {
            it[NotificationTable.actorCount] = newActorCount
            it[NotificationTable.title] = title
            it[NotificationTable.body] = body
            it[NotificationTable.updatedAt] = Instant.now()
        }
        Unit
    }

    /**
     * Shared upsert mechanics for every `record*` variant: locks the existing row (if any) for
     * `(recipientId, dedupeKey)`, computes the actor count the write will end up with, lets
     * [renderCopy] turn that count into title/body, then applies either an update or an insert.
     * Rendering happens strictly after the count is known and strictly before the write, all
     * inside the same lock, so there is no window where a concurrent caller could observe or
     * apply a stale count.
     */
    private fun upsert(
        recipientId: UUID,
        category: NotificationCategory,
        dedupeKey: String,
        actorId: UUID?,
        actorUsername: String?,
        targetType: NotificationTargetType,
        postId: UUID?,
        commentId: UUID?,
        deepLink: String?,
        enqueuedDeltaPoints: Int? = null,
        renderCopy: (newActorCount: Int) -> Pair<String, String>,
    ): UUID {
        val now = Instant.now()

        val existing = NotificationTable
            .select(NotificationTable.id, NotificationTable.actorCount)
            .where { (NotificationTable.userId eq recipientId) and (NotificationTable.dedupeKey eq dedupeKey) }
            .forUpdate()
            .singleOrNull()

        val newActorCount = (existing?.get(NotificationTable.actorCount) ?: 0) + 1
        val (title, body) = renderCopy(newActorCount)

        if (existing != null) {
            val id = existing[NotificationTable.id].value
            NotificationTable.update({ NotificationTable.id eq id }) {
                it[NotificationTable.actorCount] = newActorCount
                it[NotificationTable.lastActorUserId] = actorId
                it[NotificationTable.lastActorUsername] = actorUsername
                it[NotificationTable.targetType] = targetType
                it[NotificationTable.postId] = postId
                it[NotificationTable.commentId] = commentId
                it[NotificationTable.title] = title
                it[NotificationTable.body] = body
                it[NotificationTable.deepLink] = deepLink
                it[NotificationTable.enqueuedDeltaPoints] = enqueuedDeltaPoints
                it[NotificationTable.updatedAt] = now
            }
            return id
        }

        NotificationMetrics.eventCreated(category.name.lowercase())
        return NotificationTable.insert {
            it[NotificationTable.userId] = recipientId
            it[NotificationTable.type] = NotificationType.SOCIAL
            it[NotificationTable.category] = category
            it[NotificationTable.dedupeKey] = dedupeKey
            it[NotificationTable.targetType] = targetType
            it[NotificationTable.postId] = postId
            it[NotificationTable.commentId] = commentId
            it[NotificationTable.actorCount] = newActorCount
            it[NotificationTable.lastActorUserId] = actorId
            it[NotificationTable.lastActorUsername] = actorUsername
            it[NotificationTable.enqueuedDeltaPoints] = enqueuedDeltaPoints
            it[NotificationTable.title] = title
            it[NotificationTable.body] = body
            it[NotificationTable.deepLink] = deepLink
            it[NotificationTable.blocking] = false
            it[NotificationTable.createdAt] = now
            it[NotificationTable.updatedAt] = now
        }[NotificationTable.id].value
    }
}
