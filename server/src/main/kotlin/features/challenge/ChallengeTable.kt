package com.revio.server.features.challenge

import com.revio.server.features.car_family.CarFamilyTable
import com.revio.server.features.user.UserTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

/**
 * Global, admin-configured challenge (e.g. a weekend challenge). starts_at/ends_at are absolute
 * UTC instants; admin_timezone is kept only for display/audit in the admin UI and must never be
 * used to evaluate post eligibility (that uses starts_at/ends_at directly).
 */
object ChallengeTable : UUIDTable("challenges") {
    val title = varchar("title", 150)
    val description = text("description").nullable()
    val targetFamilyId = uuid("target_family_id").references(CarFamilyTable.id, onDelete = ReferenceOption.RESTRICT)
    val requiredPosts = integer("required_posts")
    val rewardPoints = integer("reward_points")
    val startsAt = timestampWithTimeZone("starts_at")
    val endsAt = timestampWithTimeZone("ends_at")
    val adminTimezone = varchar("admin_timezone", 64)
    val status = enumerationByName("status", 16, ChallengeStatus::class).default(ChallengeStatus.DRAFT)
    val createdBy = uuid("created_by").references(UserTable.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestampWithTimeZone)
    val publishedAt = timestampWithTimeZone("published_at").nullable()
    val cancelledAt = timestampWithTimeZone("cancelled_at").nullable()
    val finalizedAt = timestampWithTimeZone("finalized_at").nullable()

    /**
     * Set once [com.revio.server.features.notification.ChallengeStartJob]'s "challenge is live"
     * push fan-out has fully completed for this challenge — mirrors [finalizedAt]'s own
     * crash-recovery shape. See V43__challenge_started_notification.sql.
     */
    val notifiedStartedAt = timestampWithTimeZone("notified_started_at").nullable()
}
