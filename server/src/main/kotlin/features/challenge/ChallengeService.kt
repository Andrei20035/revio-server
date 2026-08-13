package com.revio.server.features.challenge

import com.revio.server.core.util.requireValidZone
import com.revio.server.features.car_family.ICarFamilyDAO
import org.jetbrains.exposed.exceptions.ExposedSQLException
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

/** Postgres SQLSTATE for an exclusion constraint violation (excl_challenges_no_overlap, V24). */
private const val POSTGRES_EXCLUSION_VIOLATION = "23P01"

/**
 * DRAFT/SCHEDULED/CANCELLED are the only persisted states (ChallengeStatus). ACTIVE/ENDED are
 * derived here from (starts_at, ends_at) at request time, so no scheduler ever flips a challenge
 * live or closed — see the plan's §2.4 for why that's a deliberate choice, not an omission.
 */
enum class EffectiveChallengeStatus {
    DRAFT,
    SCHEDULED,
    ACTIVE,
    ENDED,
    CANCELLED,
}

/**
 * Pure function, kept top-level so it's testable without a database: given a persisted
 * [Challenge] and the current instant, what is its effective lifecycle state.
 */
fun effectiveStatus(challenge: Challenge, now: Instant): EffectiveChallengeStatus = when {
    challenge.status == ChallengeStatus.CANCELLED -> EffectiveChallengeStatus.CANCELLED
    challenge.status == ChallengeStatus.DRAFT -> EffectiveChallengeStatus.DRAFT
    now.isBefore(challenge.startsAt) -> EffectiveChallengeStatus.SCHEDULED
    now.isBefore(challenge.endsAt) -> EffectiveChallengeStatus.ACTIVE
    else -> EffectiveChallengeStatus.ENDED
}

/**
 * Whether the normal cancel() path is allowed right now. Per the plan's §2.6b, cancel is only
 * permitted while the challenge hasn't ended (DRAFT, SCHEDULED, ACTIVE, or already CANCELLED —
 * cancelling twice is a harmless no-op). Once effectively ENDED, only the separate
 * ChallengeService.revokeAll() retroactive-remediation path applies.
 */
fun canCancelNormally(challenge: Challenge, now: Instant): Boolean =
    effectiveStatus(challenge, now) != EffectiveChallengeStatus.ENDED

/**
 * A participant's derived state, orthogonal to [EffectiveChallengeStatus] — the user-facing
 * counterpart of "has this person's reward landed yet". Never persisted; see the plan's §7.2
 * state table for the full derivation.
 */
enum class ParticipantState {
    NOT_STARTED,
    IN_PROGRESS,
    COMPLETED_PENDING,
    REWARDED,
    NOT_COMPLETED,
    REVOKED,
    CANCELLED,
}

/**
 * Pure function, kept top-level so it's testable without a database — same shape as
 * [effectiveStatus]: given a persisted [Challenge], one participant's [ParticipantProgress], and
 * the current instant, what state the UI should show for that participant. See the plan's §7.2.
 */
fun participantState(challenge: Challenge, progress: ParticipantProgress, now: Instant): ParticipantState {
    val completed = progress.contributionCount >= challenge.requiredPosts
    return when {
        effectiveStatus(challenge, now) == EffectiveChallengeStatus.SCHEDULED -> ParticipantState.NOT_STARTED
        effectiveStatus(challenge, now) == EffectiveChallengeStatus.CANCELLED -> ParticipantState.CANCELLED
        progress.rewardState == RewardState.GRANTED -> ParticipantState.REWARDED
        challenge.finalizedAt == null && !completed -> ParticipantState.IN_PROGRESS
        challenge.finalizedAt == null && completed -> ParticipantState.COMPLETED_PENDING
        completed -> ParticipantState.REVOKED
        else -> ParticipantState.NOT_COMPLETED
    }
}

class ChallengeNotFoundException(challengeId: UUID) : RuntimeException("Challenge $challengeId not found")

class ChallengeOverlapException(challengeId: UUID) :
    RuntimeException("Challenge $challengeId overlaps another SCHEDULED challenge's window")

class ChallengeNotEditableException(challengeId: UUID, reason: String) :
    RuntimeException("Challenge $challengeId is not editable: $reason")

class ChallengeAlreadyEndedException(challengeId: UUID) :
    RuntimeException("Challenge $challengeId has already ended; use revokeAll() instead of cancel()")

/** One page of [IChallengeService.listChallenges], keyset-paginated — mirrors FeedResponseDTO's shape. */
data class ChallengePage(
    val challenges: List<Challenge>,
    val hasMore: Boolean,
    val nextCursorCreatedAt: Instant?,
    val nextCursorId: UUID?,
)

interface IChallengeService {
    /**
     * Creates a new challenge in DRAFT. [startsAtLocal]/[endsAtLocal] are the admin's local
     * wall-clock values; [timezone] is validated strictly (rejects, never falls back to UTC —
     * see [requireValidZone]) and used to convert them to absolute UTC instants via
     * [LocalDateTime.atZone], which is DST-correct (unlike a fixed offset).
     */
    suspend fun createDraft(
        title: String,
        description: String?,
        targetFamilyId: UUID,
        requiredPosts: Int,
        rewardPoints: Int,
        startsAtLocal: LocalDateTime,
        endsAtLocal: LocalDateTime,
        timezone: String,
        createdBy: UUID?,
    ): Challenge

    suspend fun findById(challengeId: UUID): Challenge?

    /**
     * Like [findById], except a DRAFT challenge is treated as not found. For public-facing
     * single-challenge lookups (e.g. opening a challenge's detail from a user's own history):
     * an unpublished challenge must never be discoverable that way, only [findById] (internal/
     * admin use) sees DRAFT rows.
     */
    suspend fun findPublicById(challengeId: UUID): Challenge?

    /** The SCHEDULED challenge whose window contains [now], if any. */
    suspend fun findActive(now: Instant = Instant.now()): Challenge?

    /** The current or next-upcoming SCHEDULED challenge — see [IChallengeDAO.findCurrentOrNext]. */
    suspend fun findCurrentOrNext(now: Instant = Instant.now()): Challenge?

    /**
     * Title/description are editable in DRAFT and SCHEDULED (not CANCELLED). Every other field
     * is frozen once published — see the plan's §2.6c: editing required_posts/reward_points/the
     * target after publish would make already-contributing users retroactively (in)eligible with
     * nothing to re-evaluate them.
     */
    suspend fun updateTitleAndDescription(challengeId: UUID, title: String, description: String?): Challenge

    /**
     * Replaces every configuration field (title, description, target family, thresholds, window,
     * timezone) of a challenge that is still DRAFT — the same validations as [createDraft], run
     * again against the new values. Throws [ChallengeNotEditableException] once the challenge has
     * left DRAFT (published, even if since cancelled): editing required_posts/reward_points/the
     * target/the window after publish would make already-contributing users retroactively
     * (in)eligible with nothing to re-evaluate them (plan §2.6c) — the same reasoning that keeps
     * [updateTitleAndDescription] from touching those fields post-publish.
     */
    suspend fun updateDraft(
        challengeId: UUID,
        title: String,
        description: String?,
        targetFamilyId: UUID,
        requiredPosts: Int,
        rewardPoints: Int,
        startsAtLocal: LocalDateTime,
        endsAtLocal: LocalDateTime,
        timezone: String,
    ): Challenge

    /** DRAFT -> SCHEDULED. Overlap with another SCHEDULED challenge is rejected (see [ChallengeOverlapException]). */
    suspend fun publish(challengeId: UUID): Challenge

    /**
     * Normal cancellation: only while [canCancelNormally] holds (throws [ChallengeAlreadyEndedException]
     * otherwise), sets status = CANCELLED, then revokes every granted reward via the same mass-revoke
     * engine [revokeAll] uses, tagged CHALLENGE_CANCELLED. Idempotent: cancelling twice is a no-op
     * beyond re-running the (also idempotent) revoke pass. Returns the number of rewards revoked.
     */
    suspend fun cancel(challengeId: UUID): Int

    /**
     * Retroactive remediation for a misconfigured challenge, regardless of ends_at: revokes every
     * granted reward tagged ADMIN_REVOKE_ALL, without touching status — the challenge's history is
     * preserved, only the reward is undone. Idempotent. Returns the number of rewards revoked.
     */
    suspend fun revokeAll(challengeId: UUID): Int

    /**
     * Keyset-paginated list of challenges, newest first. [effectiveStatusFilter], when given,
     * matches the *effective* lifecycle state (see [effectiveStatus]), not the raw persisted
     * column — e.g. filtering by ACTIVE returns SCHEDULED challenges currently inside their
     * window, not every SCHEDULED row. Fetches one extra row to determine [ChallengePage.hasMore],
     * same technique as PostService.listFeed.
     */
    suspend fun listChallenges(
        limit: Int,
        cursorCreatedAt: Instant?,
        cursorId: UUID?,
        effectiveStatusFilter: EffectiveChallengeStatus?,
    ): ChallengePage
}

/** A validated, converted set of the fields shared by [IChallengeService.createDraft] and [IChallengeService.updateDraft]. */
private data class ValidatedChallengeFields(
    val title: String,
    val description: String?,
    val startsAt: Instant,
    val endsAt: Instant,
)

class ChallengeService(
    private val challengeDao: IChallengeDAO,
    private val challengeProgressDao: IChallengeProgressDAO,
    private val carFamilyDao: ICarFamilyDAO,
) : IChallengeService {

    companion object {
        private const val DEFAULT_LIST_LIMIT = 20
        private const val MAX_LIST_LIMIT = 100
    }

    /**
     * Every validation [createDraft] and [updateDraft] share: non-blank title, positive
     * thresholds, an existing target family, a strictly-parsed IANA timezone (never falls back to
     * UTC — see [requireValidZone]), and a window converted to absolute instants via
     * [LocalDateTime.atZone] (DST-correct, unlike a fixed offset) with endsAt after startsAt.
     */
    private suspend fun validateChallengeFields(
        title: String,
        description: String?,
        targetFamilyId: UUID,
        requiredPosts: Int,
        rewardPoints: Int,
        startsAtLocal: LocalDateTime,
        endsAtLocal: LocalDateTime,
        timezone: String,
    ): ValidatedChallengeFields {
        val trimmedTitle = title.trim()
        require(trimmedTitle.isNotEmpty()) { "Title must not be blank" }
        require(requiredPosts > 0) { "requiredPosts must be positive" }
        require(rewardPoints > 0) { "rewardPoints must be positive" }
        require(carFamilyDao.exists(targetFamilyId)) { "targetFamilyId does not exist" }

        val zone = requireValidZone(timezone)
        val startsAt = startsAtLocal.atZone(zone).toInstant()
        val endsAt = endsAtLocal.atZone(zone).toInstant()
        require(endsAt.isAfter(startsAt)) { "endsAt must be after startsAt" }

        return ValidatedChallengeFields(
            title = trimmedTitle,
            description = description?.trim()?.ifEmpty { null },
            startsAt = startsAt,
            endsAt = endsAt,
        )
    }

    override suspend fun createDraft(
        title: String,
        description: String?,
        targetFamilyId: UUID,
        requiredPosts: Int,
        rewardPoints: Int,
        startsAtLocal: LocalDateTime,
        endsAtLocal: LocalDateTime,
        timezone: String,
        createdBy: UUID?,
    ): Challenge {
        val validated = validateChallengeFields(
            title, description, targetFamilyId, requiredPosts, rewardPoints, startsAtLocal, endsAtLocal, timezone,
        )

        val id = challengeDao.insert(
            title = validated.title,
            description = validated.description,
            targetFamilyId = targetFamilyId,
            requiredPosts = requiredPosts,
            rewardPoints = rewardPoints,
            startsAt = validated.startsAt,
            endsAt = validated.endsAt,
            adminTimezone = timezone,
            createdBy = createdBy,
        )
        return challengeDao.findById(id) ?: error("Failed to load challenge $id right after insert")
    }

    override suspend fun updateDraft(
        challengeId: UUID,
        title: String,
        description: String?,
        targetFamilyId: UUID,
        requiredPosts: Int,
        rewardPoints: Int,
        startsAtLocal: LocalDateTime,
        endsAtLocal: LocalDateTime,
        timezone: String,
    ): Challenge {
        val challenge = challengeDao.findById(challengeId) ?: throw ChallengeNotFoundException(challengeId)
        if (challenge.status != ChallengeStatus.DRAFT) {
            throw ChallengeNotEditableException(challengeId, "challenge is not DRAFT (status=${challenge.status})")
        }

        val validated = validateChallengeFields(
            title, description, targetFamilyId, requiredPosts, rewardPoints, startsAtLocal, endsAtLocal, timezone,
        )

        val updatedRows = challengeDao.updateDraftFields(
            id = challengeId,
            title = validated.title,
            description = validated.description,
            targetFamilyId = targetFamilyId,
            requiredPosts = requiredPosts,
            rewardPoints = rewardPoints,
            startsAt = validated.startsAt,
            endsAt = validated.endsAt,
            adminTimezone = timezone,
        )
        if (updatedRows == 0) {
            // Left DRAFT between our check above and the UPDATE (e.g. published concurrently) —
            // updateDraftFields' own WHERE clause is the actual race guard, this just reports it.
            throw ChallengeNotEditableException(challengeId, "challenge is no longer DRAFT")
        }

        return challengeDao.findById(challengeId) ?: throw ChallengeNotFoundException(challengeId)
    }

    override suspend fun findById(challengeId: UUID): Challenge? = challengeDao.findById(challengeId)

    override suspend fun findPublicById(challengeId: UUID): Challenge? =
        challengeDao.findById(challengeId)?.takeIf { it.status != ChallengeStatus.DRAFT }

    override suspend fun findActive(now: Instant): Challenge? = challengeDao.findActive(now)

    override suspend fun findCurrentOrNext(now: Instant): Challenge? = challengeDao.findCurrentOrNext(now)

    override suspend fun updateTitleAndDescription(challengeId: UUID, title: String, description: String?): Challenge {
        val challenge = challengeDao.findById(challengeId) ?: throw ChallengeNotFoundException(challengeId)
        if (challenge.status == ChallengeStatus.CANCELLED) {
            throw ChallengeNotEditableException(challengeId, "challenge is CANCELLED")
        }

        val trimmedTitle = title.trim()
        require(trimmedTitle.isNotEmpty()) { "Title must not be blank" }

        challengeDao.updateEditableFields(challengeId, trimmedTitle, description?.trim()?.ifEmpty { null })
        return challengeDao.findById(challengeId) ?: throw ChallengeNotFoundException(challengeId)
    }

    override suspend fun publish(challengeId: UUID): Challenge {
        val challenge = challengeDao.findById(challengeId) ?: throw ChallengeNotFoundException(challengeId)
        require(challenge.status == ChallengeStatus.DRAFT) { "Only a DRAFT challenge can be published" }

        try {
            challengeDao.updateStatus(challengeId, ChallengeStatus.SCHEDULED, publishedAt = Instant.now())
        } catch (e: ExposedSQLException) {
            if (e.sqlState == POSTGRES_EXCLUSION_VIOLATION) throw ChallengeOverlapException(challengeId)
            throw e
        }

        return challengeDao.findById(challengeId) ?: throw ChallengeNotFoundException(challengeId)
    }

    override suspend fun cancel(challengeId: UUID): Int {
        val challenge = challengeDao.findById(challengeId) ?: throw ChallengeNotFoundException(challengeId)
        val now = Instant.now()
        if (!canCancelNormally(challenge, now)) throw ChallengeAlreadyEndedException(challengeId)

        if (challenge.status != ChallengeStatus.CANCELLED) {
            challengeDao.updateStatus(challengeId, ChallengeStatus.CANCELLED, cancelledAt = now)
        }

        return challengeProgressDao.revokeAllGrantedRewards(challengeId, LedgerReason.CHALLENGE_CANCELLED)
    }

    override suspend fun revokeAll(challengeId: UUID): Int {
        challengeDao.findById(challengeId) ?: throw ChallengeNotFoundException(challengeId)
        return challengeProgressDao.revokeAllGrantedRewards(challengeId, LedgerReason.ADMIN_REVOKE_ALL)
    }

    override suspend fun listChallenges(
        limit: Int,
        cursorCreatedAt: Instant?,
        cursorId: UUID?,
        effectiveStatusFilter: EffectiveChallengeStatus?,
    ): ChallengePage {
        val effectiveLimit = validateListLimit(limit)
        require((cursorCreatedAt == null) == (cursorId == null)) {
            "cursorCreatedAt and cursorId must be provided together"
        }

        // Fetch one extra row to determine whether another page exists, same as PostService.listFeed.
        val rows = challengeDao.listAll(effectiveLimit + 1, cursorCreatedAt, cursorId, effectiveStatusFilter, Instant.now())
        val hasMore = rows.size > effectiveLimit
        val page = if (hasMore) rows.take(effectiveLimit) else rows

        return ChallengePage(
            challenges = page,
            hasMore = hasMore,
            nextCursorCreatedAt = if (hasMore) page.last().createdAt else null,
            nextCursorId = if (hasMore) page.last().id else null,
        )
    }

    private fun validateListLimit(limit: Int): Int {
        if (limit == 0) return DEFAULT_LIST_LIMIT
        require(limit in 1..MAX_LIST_LIMIT) { "limit must be between 1 and $MAX_LIST_LIMIT" }
        return limit
    }
}
