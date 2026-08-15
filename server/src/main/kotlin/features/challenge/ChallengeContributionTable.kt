package com.revio.server.features.challenge

import com.revio.server.features.car_model.CarModelTable
import com.revio.server.features.post.PostTable
import com.revio.server.features.user.UserTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

/**
 * Source of truth for challenge progress: one row per (challenge, post). The UNIQUE(challenge_id,
 * post_id) constraint is what prevents the same post from ever being counted twice, including
 * under concurrent evaluation. car_model_id is copied at contribution time for the audit trail
 * (see [ContributionSummary]) — PostService.updatePostAsAuthor no longer allows a later model
 * edit on a contributing post at all, so this snapshot and the post's own car_model_id will
 * always agree in practice, but the copy is kept as the read path's own source of truth rather
 * than joining back to posts.
 */
object ChallengeContributionTable : UUIDTable("challenge_contributions") {
    val challengeId = uuid("challenge_id").references(ChallengeTable.id, onDelete = ReferenceOption.CASCADE)
    val userId = uuid("user_id").references(UserTable.id, onDelete = ReferenceOption.CASCADE)
    val postId = uuid("post_id").references(PostTable.id, onDelete = ReferenceOption.CASCADE)
    val carModelId = uuid("car_model_id").references(CarModelTable.id, onDelete = ReferenceOption.RESTRICT)
    val postCreatedAt = timestampWithTimeZone("post_created_at")
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)

    init {
        uniqueIndex(challengeId, postId)
    }
}
