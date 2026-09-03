package com.revio.server.features.challenge

import org.jetbrains.exposed.sql.ResultRow
import java.time.Instant
import java.util.UUID

data class Challenge(
    val id: UUID,
    val title: String,
    val description: String?,
    val targetFamilyId: UUID,
    val requiredPosts: Int,
    val rewardPoints: Int,
    val startsAt: Instant,
    val endsAt: Instant,
    val adminTimezone: String,
    val status: ChallengeStatus,
    val createdBy: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val publishedAt: Instant?,
    val cancelledAt: Instant?,
    val finalizedAt: Instant?,
    val notifiedStartedAt: Instant?,
)

fun ResultRow.toChallenge(): Challenge = Challenge(
    id = this[ChallengeTable.id].value,
    title = this[ChallengeTable.title],
    description = this[ChallengeTable.description],
    targetFamilyId = this[ChallengeTable.targetFamilyId],
    requiredPosts = this[ChallengeTable.requiredPosts],
    rewardPoints = this[ChallengeTable.rewardPoints],
    startsAt = this[ChallengeTable.startsAt].toInstant(),
    endsAt = this[ChallengeTable.endsAt].toInstant(),
    adminTimezone = this[ChallengeTable.adminTimezone],
    status = this[ChallengeTable.status],
    createdBy = this[ChallengeTable.createdBy],
    createdAt = this[ChallengeTable.createdAt].toInstant(),
    updatedAt = this[ChallengeTable.updatedAt].toInstant(),
    publishedAt = this[ChallengeTable.publishedAt]?.toInstant(),
    cancelledAt = this[ChallengeTable.cancelledAt]?.toInstant(),
    finalizedAt = this[ChallengeTable.finalizedAt]?.toInstant(),
    notifiedStartedAt = this[ChallengeTable.notifiedStartedAt]?.toInstant(),
)
