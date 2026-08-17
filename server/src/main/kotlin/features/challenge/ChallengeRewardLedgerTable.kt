package com.revio.server.features.challenge

import com.revio.server.features.user.UserTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

/**
 * Audit trail for every challenge reward grant/revoke. nominal_delta is the reward as configured
 * (+/- reward_points); applied_delta is what actually happened to spot_score after the floor-at-0
 * clamp, so `spot_score == SUM(posts.points) + SUM(challenge applied_delta) + SUM(early spotter
 * applied_delta)` stays a verifiable invariant (see
 * [com.revio.server.features.user.EarlySpotterBonusLedgerTable] for the early spotter term).
 * idempotency_key absorbs retries: it is built from (challenge, user, kind, award_epoch), so the
 * same logical grant/revoke can never be double-applied.
 */
object ChallengeRewardLedgerTable : UUIDTable("challenge_reward_ledger") {
    val userId = uuid("user_id").references(UserTable.id, onDelete = ReferenceOption.CASCADE)
    val challengeId = uuid("challenge_id").references(ChallengeTable.id, onDelete = ReferenceOption.CASCADE)
    val kind = enumerationByName("kind", 16, LedgerEntryKind::class)
    val reason = enumerationByName("reason", 32, LedgerReason::class)
    val nominalDelta = integer("nominal_delta")
    val appliedDelta = integer("applied_delta")
    val awardEpoch = integer("award_epoch")
    val idempotencyKey = varchar("idempotency_key", 128).uniqueIndex()
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)
}
