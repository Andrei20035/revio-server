package com.revio.server.features.user

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

/** Why an early_spotter_bonus_ledger entry was written. Only one value exists: the bonus is never revoked. */
enum class EarlySpotterBonusReason {
    EARLY_SPOTTER_GRANTED,
}

/**
 * Audit trail and idempotency guard for the one-time 300-point Early Spotter bonus.
 * UNIQUE(user_id) is the primary guard — a user can never receive the bonus twice.
 * idempotency_key gives the same absorb-the-retry guarantee as
 * [com.revio.server.features.challenge.ChallengeRewardLedgerTable], built from user_id alone
 * since the bonus never repeats or gets revoked (no award_epoch, unlike the challenge ledger).
 */
object EarlySpotterBonusLedgerTable : UUIDTable("early_spotter_bonus_ledger") {
    val userId = uuid("user_id").references(UserTable.id, onDelete = ReferenceOption.CASCADE).uniqueIndex()
    val earlySpotterNumber = integer("early_spotter_number")
    val nominalDelta = integer("nominal_delta")
    val appliedDelta = integer("applied_delta")
    val reason = enumerationByName("reason", 32, EarlySpotterBonusReason::class)
    val idempotencyKey = varchar("idempotency_key", 128).uniqueIndex()
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)
}
