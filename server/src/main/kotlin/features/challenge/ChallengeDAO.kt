package com.revio.server.features.challenge

import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insertReturning
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

interface IChallengeDAO {
    /** Inserts a new challenge in DRAFT. Returns its id. */
    suspend fun insert(
        title: String,
        description: String?,
        targetFamilyId: UUID,
        requiredPosts: Int,
        rewardPoints: Int,
        startsAt: Instant,
        endsAt: Instant,
        adminTimezone: String,
        createdBy: UUID?,
    ): UUID

    suspend fun findById(id: UUID): Challenge?

    /** The SCHEDULED challenge whose [starts_at, ends_at) window contains [now], if any. */
    suspend fun findActive(now: Instant): Challenge?

    /**
     * The SCHEDULED challenge active at [now], or if none, the soonest upcoming SCHEDULED one
     * (starts_at > now). Null if neither exists. Only one challenge may be SCHEDULED with an
     * overlapping window at a time, so ordering by starts_at ASC among non-ended SCHEDULED rows
     * always yields the active one first when there is one, otherwise the next upcoming one.
     */
    suspend fun findCurrentOrNext(now: Instant): Challenge?

    /**
     * SCHEDULED challenges whose window has ended but haven't been finalized yet
     * (`ends_at <= now`, `finalized_at IS NULL`), oldest-ended first — the set the finalization
     * job works through. DRAFT, CANCELLED, still-active, and already-finalized challenges are
     * never candidates.
     */
    suspend fun findDueForFinalization(now: Instant, limit: Int): List<Challenge>

    /**
     * Marks [id] finalized at [finalizedAt], scoped to `finalized_at IS NULL` in the UPDATE
     * itself so a concurrent finalize is a harmless no-op rather than overwriting an
     * already-set timestamp. Returns the number of rows updated (0 or 1).
     */
    suspend fun markFinalized(id: UUID, finalizedAt: Instant): Int

    /**
     * SCHEDULED challenges whose window has just opened (`starts_at <= now`, `ends_at > now`)
     * and haven't had their "challenge is live" push fan-out run yet (`notified_started_at IS
     * NULL`), soonest-started first — the set [com.revio.server.features.notification.ChallengeStartJob]
     * works through. DRAFT, CANCELLED, not-yet-started, already-ended, and already-notified
     * challenges are never candidates. Mirrors [findDueForFinalization]'s shape.
     */
    suspend fun findDueForStartNotification(now: Instant, limit: Int): List<Challenge>

    /**
     * Marks [id]'s "challenge is live" fan-out as done at [notifiedAt], scoped to
     * `notified_started_at IS NULL` in the UPDATE itself so a concurrent/retried run is a
     * harmless no-op rather than overwriting an already-set timestamp. Returns the number of
     * rows updated (0 or 1). Mirrors [markFinalized]'s shape.
     */
    suspend fun markStartNotified(id: UUID, notifiedAt: Instant): Int

    suspend fun updateStatus(id: UUID, status: ChallengeStatus, publishedAt: Instant? = null, cancelledAt: Instant? = null): Int

    /** Updates only the fields still editable once a challenge is SCHEDULED (title/description). */
    suspend fun updateEditableFields(id: UUID, title: String, description: String?): Int

    /**
     * Replaces every configuration field of a DRAFT challenge — the same set [insert] accepts.
     * Scoped to `id = [id] AND status = 'DRAFT'` in the UPDATE itself (not a separate
     * find-then-update), so a challenge published concurrently between the caller's status check
     * and this call is left untouched rather than silently edited. Returns 0 in that case (or if
     * [id] doesn't exist) — the caller (ChallengeService.updateDraft) distinguishes the two by
     * re-reading the row.
     */
    suspend fun updateDraftFields(
        id: UUID,
        title: String,
        description: String?,
        targetFamilyId: UUID,
        requiredPosts: Int,
        rewardPoints: Int,
        startsAt: Instant,
        endsAt: Instant,
        adminTimezone: String,
    ): Int

    /**
     * Keyset-paginated list of challenges, newest first (createdAt DESC, id DESC as tiebreaker —
     * mirrors PostDAO.listFeed). [effectiveStatusFilter], if given, is translated into the SQL
     * WHERE clause using [now] rather than applied after the fact: EffectiveChallengeStatus isn't
     * a persisted column (see effectiveStatus() in ChallengeService), so a Kotlin-side post-filter
     * would silently return fewer than [limit] rows on a page even though more matching rows
     * exist beyond the cursor. The branches below must stay in lockstep with effectiveStatus()'s
     * truth table — a mismatch there is a real correctness bug, not just a style issue.
     */
    suspend fun listAll(
        limit: Int,
        cursorCreatedAt: Instant?,
        cursorId: UUID?,
        effectiveStatusFilter: EffectiveChallengeStatus?,
        now: Instant,
    ): List<Challenge>
}

class ChallengeDAO : IChallengeDAO {

    override suspend fun insert(
        title: String,
        description: String?,
        targetFamilyId: UUID,
        requiredPosts: Int,
        rewardPoints: Int,
        startsAt: Instant,
        endsAt: Instant,
        adminTimezone: String,
        createdBy: UUID?,
    ): UUID = transaction {
        ChallengeTable.insertReturning(listOf(ChallengeTable.id)) {
            it[ChallengeTable.title] = title
            it[ChallengeTable.description] = description
            it[ChallengeTable.targetFamilyId] = targetFamilyId
            it[ChallengeTable.requiredPosts] = requiredPosts
            it[ChallengeTable.rewardPoints] = rewardPoints
            it[ChallengeTable.startsAt] = startsAt.atOffset(ZoneOffset.UTC)
            it[ChallengeTable.endsAt] = endsAt.atOffset(ZoneOffset.UTC)
            it[ChallengeTable.adminTimezone] = adminTimezone
            it[ChallengeTable.status] = ChallengeStatus.DRAFT
            it[ChallengeTable.createdBy] = createdBy
        }.single()[ChallengeTable.id].value
    }

    override suspend fun findById(id: UUID): Challenge? = transaction {
        ChallengeTable
            .selectAll()
            .where { ChallengeTable.id eq id }
            .singleOrNull()
            ?.toChallenge()
    }

    override suspend fun findActive(now: Instant): Challenge? = transaction {
        val nowOffset = now.atOffset(ZoneOffset.UTC)
        ChallengeTable
            .selectAll()
            .where {
                (ChallengeTable.status eq ChallengeStatus.SCHEDULED) and
                    (ChallengeTable.startsAt lessEq nowOffset) and
                    (ChallengeTable.endsAt greater nowOffset)
            }
            .singleOrNull()
            ?.toChallenge()
    }

    override suspend fun findCurrentOrNext(now: Instant): Challenge? = transaction {
        val nowOffset = now.atOffset(ZoneOffset.UTC)
        ChallengeTable
            .selectAll()
            .where {
                (ChallengeTable.status eq ChallengeStatus.SCHEDULED) and
                    (ChallengeTable.endsAt greater nowOffset)
            }
            .orderBy(ChallengeTable.startsAt to SortOrder.ASC)
            .limit(1)
            .singleOrNull()
            ?.toChallenge()
    }

    override suspend fun findDueForFinalization(now: Instant, limit: Int): List<Challenge> = transaction {
        val nowOffset = now.atOffset(ZoneOffset.UTC)
        ChallengeTable
            .selectAll()
            .where {
                (ChallengeTable.status eq ChallengeStatus.SCHEDULED) and
                    (ChallengeTable.endsAt lessEq nowOffset) and
                    (ChallengeTable.finalizedAt.isNull())
            }
            .orderBy(ChallengeTable.endsAt to SortOrder.ASC)
            .limit(limit)
            .map { it.toChallenge() }
    }

    override suspend fun markFinalized(id: UUID, finalizedAt: Instant): Int = transaction {
        ChallengeTable.update({ (ChallengeTable.id eq id) and (ChallengeTable.finalizedAt.isNull()) }) {
            it[ChallengeTable.finalizedAt] = finalizedAt.atOffset(ZoneOffset.UTC)
        }
    }

    override suspend fun findDueForStartNotification(now: Instant, limit: Int): List<Challenge> = transaction {
        val nowOffset = now.atOffset(ZoneOffset.UTC)
        ChallengeTable
            .selectAll()
            .where {
                (ChallengeTable.status eq ChallengeStatus.SCHEDULED) and
                    (ChallengeTable.startsAt lessEq nowOffset) and
                    (ChallengeTable.endsAt greater nowOffset) and
                    (ChallengeTable.notifiedStartedAt.isNull())
            }
            .orderBy(ChallengeTable.startsAt to SortOrder.ASC)
            .limit(limit)
            .map { it.toChallenge() }
    }

    override suspend fun markStartNotified(id: UUID, notifiedAt: Instant): Int = transaction {
        ChallengeTable.update({ (ChallengeTable.id eq id) and (ChallengeTable.notifiedStartedAt.isNull()) }) {
            it[ChallengeTable.notifiedStartedAt] = notifiedAt.atOffset(ZoneOffset.UTC)
        }
    }

    override suspend fun updateStatus(id: UUID, status: ChallengeStatus, publishedAt: Instant?, cancelledAt: Instant?): Int = transaction {
        ChallengeTable.update({ ChallengeTable.id eq id }) {
            it[ChallengeTable.status] = status
            it[ChallengeTable.updatedAt] = Instant.now().atOffset(ZoneOffset.UTC)
            if (publishedAt != null) it[ChallengeTable.publishedAt] = publishedAt.atOffset(ZoneOffset.UTC)
            if (cancelledAt != null) it[ChallengeTable.cancelledAt] = cancelledAt.atOffset(ZoneOffset.UTC)
        }
    }

    override suspend fun updateEditableFields(id: UUID, title: String, description: String?): Int = transaction {
        ChallengeTable.update({ ChallengeTable.id eq id }) {
            it[ChallengeTable.title] = title
            it[ChallengeTable.description] = description
            it[ChallengeTable.updatedAt] = Instant.now().atOffset(ZoneOffset.UTC)
        }
    }

    override suspend fun updateDraftFields(
        id: UUID,
        title: String,
        description: String?,
        targetFamilyId: UUID,
        requiredPosts: Int,
        rewardPoints: Int,
        startsAt: Instant,
        endsAt: Instant,
        adminTimezone: String,
    ): Int = transaction {
        ChallengeTable.update({ (ChallengeTable.id eq id) and (ChallengeTable.status eq ChallengeStatus.DRAFT) }) {
            it[ChallengeTable.title] = title
            it[ChallengeTable.description] = description
            it[ChallengeTable.targetFamilyId] = targetFamilyId
            it[ChallengeTable.requiredPosts] = requiredPosts
            it[ChallengeTable.rewardPoints] = rewardPoints
            it[ChallengeTable.startsAt] = startsAt.atOffset(ZoneOffset.UTC)
            it[ChallengeTable.endsAt] = endsAt.atOffset(ZoneOffset.UTC)
            it[ChallengeTable.adminTimezone] = adminTimezone
            it[ChallengeTable.updatedAt] = Instant.now().atOffset(ZoneOffset.UTC)
        }
    }

    override suspend fun listAll(
        limit: Int,
        cursorCreatedAt: Instant?,
        cursorId: UUID?,
        effectiveStatusFilter: EffectiveChallengeStatus?,
        now: Instant,
    ): List<Challenge> = transaction {
        val query = ChallengeTable.selectAll()

        if (cursorCreatedAt != null && cursorId != null) {
            val cursorOffset = cursorCreatedAt.atOffset(ZoneOffset.UTC)
            query.andWhere {
                (ChallengeTable.createdAt less cursorOffset) or
                    ((ChallengeTable.createdAt eq cursorOffset) and (ChallengeTable.id less cursorId))
            }
        }

        if (effectiveStatusFilter != null) {
            val nowOffset = now.atOffset(ZoneOffset.UTC)
            query.andWhere {
                when (effectiveStatusFilter) {
                    EffectiveChallengeStatus.DRAFT -> ChallengeTable.status eq ChallengeStatus.DRAFT
                    EffectiveChallengeStatus.CANCELLED -> ChallengeTable.status eq ChallengeStatus.CANCELLED
                    EffectiveChallengeStatus.SCHEDULED ->
                        (ChallengeTable.status eq ChallengeStatus.SCHEDULED) and (ChallengeTable.startsAt greater nowOffset)
                    EffectiveChallengeStatus.ACTIVE ->
                        (ChallengeTable.status eq ChallengeStatus.SCHEDULED) and
                            (ChallengeTable.startsAt lessEq nowOffset) and
                            (ChallengeTable.endsAt greater nowOffset)
                    EffectiveChallengeStatus.ENDED ->
                        (ChallengeTable.status eq ChallengeStatus.SCHEDULED) and (ChallengeTable.endsAt lessEq nowOffset)
                }
            }
        }

        query
            .orderBy(ChallengeTable.createdAt to SortOrder.DESC, ChallengeTable.id to SortOrder.DESC)
            .limit(limit)
            .map { it.toChallenge() }
    }
}
