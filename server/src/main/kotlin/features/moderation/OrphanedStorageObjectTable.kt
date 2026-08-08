package com.revio.server.features.moderation

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Retry queue for storage objects whose deletion failed after a moderation takedown had already
 * committed (see PostService.removePost's best-effort `deleteImage` cleanup). Keyed on the
 * object key itself, not a UUID id, so a repeated failed attempt is a plain upsert.
 */
object OrphanedStorageObjectTable : Table("orphaned_storage_objects") {
    val objectKey = text("object_key")
    val attempts = integer("attempts").default(1)
    val lastError = text("last_error").nullable()
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val lastAttemptAt = timestamp("last_attempt_at").defaultExpression(CurrentTimestamp)

    override val primaryKey = PrimaryKey(objectKey)
}
