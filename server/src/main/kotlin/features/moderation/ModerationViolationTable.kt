package com.revio.server.features.moderation

import com.revio.server.features.user.UserTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * One row per post an admin removed. post_id is deliberately NOT a foreign key to posts.id: the
 * removal that creates this row also deletes the post, and every post-referencing table cascades
 * on that delete — a FK here would erase the violation at the exact moment it needs to persist.
 * post_image_key/post_caption are a snapshot taken at removal time so the audit trail survives
 * the post's own deletion. revoked_at/revoked_by mark a soft retraction (see plan §"De discutat" —
 * an admin can withdraw a violation); the row is never hard-deleted.
 */
object ModerationViolationTable : UUIDTable("moderation_violations") {
    val userId = uuid("user_id").references(UserTable.id, onDelete = ReferenceOption.CASCADE)
    val postId = uuid("post_id")
    val postImageKey = text("post_image_key").nullable()
    val postCaption = text("post_caption").nullable()
    val reason = enumerationByName("reason", 40, ModerationReason::class)
    val reasonDetails = text("reason_details").nullable()
    val adminId = uuid("admin_id").references(UserTable.id, onDelete = ReferenceOption.RESTRICT)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val revokedAt = timestamp("revoked_at").nullable()
    val revokedBy = uuid("revoked_by").references(UserTable.id, onDelete = ReferenceOption.SET_NULL).nullable()
}
