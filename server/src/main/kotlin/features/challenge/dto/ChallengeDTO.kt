package com.revio.server.features.challenge.dto

import com.revio.server.core.serialization.InstantSerializer
import com.revio.server.core.serialization.UUIDSerializer
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

/** Public-facing challenge config — no admin/audit fields (status, createdBy, timestamps). */
@Serializable
data class ChallengeDTO(
    @Serializable(with = UUIDSerializer::class) val id: UUID,
    val title: String,
    val description: String?,
    val targetFamilyBrand: String,
    val targetFamilyName: String,
    val requiredPosts: Int,
    val rewardPoints: Int,
    @Serializable(with = InstantSerializer::class) val startsAt: Instant,
    @Serializable(with = InstantSerializer::class) val endsAt: Instant,
    /** Nullable so an older client (built before this field existed) still deserializes this
     * response. `GET /challenges/{id}` has no other way to carry the challenge's own lifecycle
     * status — unlike `/current` and `/me`, which already send it as a sibling field. */
    val effectiveStatus: String? = null,
)

/** The viewer's own progress on one challenge. */
@Serializable
data class ChallengeProgressDTO(
    val contributionCount: Int,
    val rewardState: String,
    /** Nullable so an older client (built before this field existed) still deserializes this response. */
    val participantState: String? = null,
)

@Serializable
data class ChallengeContributionDTO(
    @Serializable(with = UUIDSerializer::class) val postId: UUID,
    @Serializable(with = InstantSerializer::class) val createdAt: Instant,
    /** Nullable so an older client (built before this field existed) still deserializes this response. */
    val imageUrl: String? = null,
    val carBrand: String? = null,
    val carModel: String? = null,
)

/** Response for GET /challenges/current. All fields null when no challenge is scheduled at all. */
@Serializable
data class CurrentChallengeDTO(
    val challenge: ChallengeDTO?,
    val progress: ChallengeProgressDTO?,
    /** Nullable so an older client (built before this field existed) still deserializes this response. */
    val effectiveStatus: String? = null,
)

/** Response for GET /challenges/{id}/progress. */
@Serializable
data class ChallengeProgressDetailDTO(
    val progress: ChallengeProgressDTO,
    val contributions: List<ChallengeContributionDTO>,
)

/** The caller's lifetime challenge participation — the summary strip in "My Challenges". */
@Serializable
data class ChallengeSummaryDTO(
    val joinedCount: Int,
    val completedCount: Int,
    val pointsEarned: Int,
    val totalContributions: Int,
)

/** One row of GET /challenges/me: a challenge the caller participated in, plus their progress on it. */
@Serializable
data class ChallengeHistoryItemDTO(
    val challenge: ChallengeDTO,
    val effectiveStatus: String,
    val progress: ChallengeProgressDTO,
)

/**
 * Response for GET /challenges/me. [summary] is present only on the first page (no cursor given)
 * — recomputing it on every subsequent page would be wasted work the client already has the
 * answer for.
 */
@Serializable
data class MyChallengesDTO(
    val summary: ChallengeSummaryDTO?,
    val challenges: List<ChallengeHistoryItemDTO>,
    val hasMore: Boolean,
    @Serializable(with = InstantSerializer::class) val nextCursorEndsAt: Instant?,
    @Serializable(with = UUIDSerializer::class) val nextCursorId: UUID?,
)
