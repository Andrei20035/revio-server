package com.revio.server.features.announcement

import com.revio.server.features.user.UserTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

/** One-time announcement kinds. A row only ever exists for these — see chk_user_announcements_status/key at the DB level for status, not key (key has no DB check, enum is the guard). */
enum class AnnouncementKey {
    EARLY_SPOTTER_WELCOME,
    EARLY_SPOTTER_BONUS,
}

enum class AnnouncementStatus {
    PENDING,
    SEEN,
}

/**
 * One-time, per-user announcements (e.g. Early Spotter welcome/bonus cards). See
 * V32__user_announcements.sql. PRIMARY KEY (user_id, announcement_key) is the idempotency guard —
 * the same key can never be inserted twice for the same user, mirroring
 * [com.revio.server.features.feedback.FeedbackPromptStateTable] and
 * [com.revio.server.features.user.EarlySpotterBonusLedgerTable].
 * [payload] is a JSON-encoded string stored as a plain `text` Postgres column — the project has
 * no exposed-json module dependency, and Exposed's plain text bind does not implicitly cast to
 * jsonb on insert; callers serialize/deserialize explicitly, same pattern as
 * [com.revio.server.features.moderation.AdminAuditLogTable.metadata].
 */
object UserAnnouncementTable : Table("user_announcements") {
    val userId = uuid("user_id").references(UserTable.id, onDelete = ReferenceOption.CASCADE)
    val announcementKey = varchar("announcement_key", 40)
    val status = varchar("status", 20)
    val payload = text("payload").nullable()
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)
    val seenAt = timestampWithTimeZone("seen_at").nullable()

    override val primaryKey = PrimaryKey(userId, announcementKey)
}
