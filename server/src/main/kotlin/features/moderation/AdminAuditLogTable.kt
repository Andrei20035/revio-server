package com.revio.server.features.moderation

import com.revio.server.features.user.UserTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Append-only record of every administrative moderation action (post removal, ban/unban,
 * violation retraction). target_type/target_id are untyped on purpose so one table covers every
 * action kind without a growing set of nullable FK columns. metadata is a JSON-encoded string
 * (the underlying Postgres column is jsonb, see V27__moderation.sql) — kept as `text` here since
 * the project has no exposed-json module dependency; callers serialize/deserialize explicitly.
 */
object AdminAuditLogTable : UUIDTable("admin_audit_log") {
    val adminId = uuid("admin_id").references(UserTable.id, onDelete = ReferenceOption.RESTRICT)
    val action = varchar("action", 40)
    val targetType = varchar("target_type", 20)
    val targetId = uuid("target_id")
    val metadata = text("metadata").nullable()
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
}
