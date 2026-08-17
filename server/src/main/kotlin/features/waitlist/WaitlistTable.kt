package com.revio.server.features.waitlist

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

/**
 * Local copy of the Supabase `waitlist_signups` table (see V31). [id] is the Supabase row id, not
 * locally generated — this makes upsert-by-id naturally idempotent. [emailNormalized] is a
 * DB-generated column (`lower(trim(email))`) and is the only column auth lookups query against.
 *
 * No FK to `users` and no reservation columns: waitlist membership never reserves an Early
 * Spotter number and never decides who becomes one — see
 * [com.revio.server.features.user.UserDao.createUser] for that allocation.
 */
object WaitlistTable : UUIDTable("waitlist_signups") {
    val email = text("email")
    val emailNormalized = text("email_normalized").databaseGenerated()
    val username = text("username").nullable()
    val platform = text("platform").nullable()
    val country = text("country").nullable()
    val sourceCreatedAt = timestampWithTimeZone("source_created_at")
    val sourceUpdatedAt = timestampWithTimeZone("source_updated_at").nullable()
    val syncedAt = timestampWithTimeZone("synced_at").defaultExpression(CurrentTimestampWithTimeZone)
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestampWithTimeZone)
}
