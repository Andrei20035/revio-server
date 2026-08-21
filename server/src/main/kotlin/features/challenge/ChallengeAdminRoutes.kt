package com.revio.server.features.challenge

import com.revio.server.core.serialization.InstantSerializer
import com.revio.server.core.serialization.UUIDSerializer
import com.revio.server.core.util.getUuidClaim
import com.revio.server.core.util.toUuidOrNull
import com.revio.server.features.post.IPostDAO
import com.revio.server.features.post.PostSource
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeParseException
import java.util.UUID

private val logger = LoggerFactory.getLogger("com.revio.server.features.challenge.ChallengeAdminRoutes")

@Serializable
data class CreateChallengeAdminRequest(
    val title: String,
    val description: String? = null,
    @Serializable(with = UUIDSerializer::class) val targetFamilyId: UUID,
    val requiredPosts: Int,
    val rewardPoints: Int,
    /** Local (no offset) ISO-8601 date-time, e.g. "2026-08-08T09:00:00" — the admin's wall clock. */
    val startsAtLocal: String,
    val endsAtLocal: String,
    /** IANA zone, e.g. "Europe/Bucharest". Validated strictly — see ChallengeService.createDraft. */
    val timezone: String,
)

@Serializable
data class UpdateChallengeTitleRequest(
    val title: String,
    val description: String? = null,
)

/** [confirmChallengeId] must repeat the path id — an explicit confirmation barrier for a retroactive mass revoke (plan §2.6b). */
@Serializable
data class RevokeAllRequest(
    @Serializable(with = UUIDSerializer::class) val confirmChallengeId: UUID,
)

@Serializable
data class RevokeResultDTO(val revokedCount: Int)

@Serializable
data class FinalizationResultDTO(val grantedCount: Int, val revokedCount: Int)

private fun FinalizationResult.toDTO() = FinalizationResultDTO(grantedCount = grantedCount, revokedCount = revokedCount)

@Serializable
data class FinalizeDueResultDTO(
    val finalizedChallenges: Int,
    val grantedRewards: Int,
    val revokedRewards: Int,
)

/** [evaluated] is false only when no challenge was active at the post's creation time — nothing to reconcile. */
@Serializable
data class ReconcilePostResultDTO(
    val evaluated: Boolean,
    val eligible: Boolean? = null,
    val contributionCount: Int? = null,
    val rewardGranted: Boolean? = null,
)

private fun ContributionEvaluation?.toReconcileDTO() = if (this == null) {
    ReconcilePostResultDTO(evaluated = false)
} else {
    ReconcilePostResultDTO(
        evaluated = true,
        eligible = eligible,
        contributionCount = contributionCount,
        rewardGranted = rewardGranted,
    )
}

/** One entry of [FinalizationHealthDTO.stuckChallenges] — plan §9-K3. */
@Serializable
data class StuckChallengeDTO(
    @Serializable(with = UUIDSerializer::class) val id: UUID,
    @Serializable(with = InstantSerializer::class) val endsAt: Instant,
)

@Serializable
data class FinalizationHealthDTO(val stuckChallenges: List<StuckChallengeDTO>)

/** Keyset cursor for GET /admin/challenges — mirrors FeedCursorDTO's shape. */
@Serializable
data class ChallengeListCursorDTO(
    @Serializable(with = InstantSerializer::class) val lastCreatedAt: Instant,
    @Serializable(with = UUIDSerializer::class) val lastChallengeId: UUID,
)

@Serializable
data class ChallengeAdminPageDTO(
    val challenges: List<ChallengeAdminDTO>,
    val nextCursor: ChallengeListCursorDTO? = null,
    val hasMore: Boolean,
)

@Serializable
data class ChallengeAdminDTO(
    @Serializable(with = UUIDSerializer::class) val id: UUID,
    val title: String,
    val description: String?,
    @Serializable(with = UUIDSerializer::class) val targetFamilyId: UUID,
    val requiredPosts: Int,
    val rewardPoints: Int,
    @Serializable(with = InstantSerializer::class) val startsAt: Instant,
    @Serializable(with = InstantSerializer::class) val endsAt: Instant,
    val adminTimezone: String,
    val status: String,
    @Serializable(with = UUIDSerializer::class) val createdBy: UUID?,
    @Serializable(with = InstantSerializer::class) val createdAt: Instant,
    @Serializable(with = InstantSerializer::class) val updatedAt: Instant,
    @Serializable(with = InstantSerializer::class) val publishedAt: Instant?,
    @Serializable(with = InstantSerializer::class) val cancelledAt: Instant?,
    /** Nullable so an older client (built before this field existed) still deserializes this response. */
    @Serializable(with = InstantSerializer::class) val finalizedAt: Instant? = null,
)

private fun Challenge.toAdminDTO() = ChallengeAdminDTO(
    id = id,
    title = title,
    description = description,
    targetFamilyId = targetFamilyId,
    requiredPosts = requiredPosts,
    rewardPoints = rewardPoints,
    startsAt = startsAt,
    endsAt = endsAt,
    adminTimezone = adminTimezone,
    status = status.name,
    createdBy = createdBy,
    createdAt = createdAt,
    updatedAt = updatedAt,
    publishedAt = publishedAt,
    cancelledAt = cancelledAt,
    finalizedAt = finalizedAt,
)

/**
 * Parses a challenge request's local start/end wall-clock strings, shared by the create (POST)
 * and full-DRAFT-edit (PUT) handlers so the two error messages stay identical.
 */
private fun parseLocalWindow(startsAtLocal: String, endsAtLocal: String): Pair<LocalDateTime, LocalDateTime>? = try {
    LocalDateTime.parse(startsAtLocal) to LocalDateTime.parse(endsAtLocal)
} catch (e: DateTimeParseException) {
    null
}

/**
 * Admin CRUD/lifecycle for challenges. Gated by the "admin" JWT realm (config/Security.kt),
 * which only accepts a token whose isAdmin claim is true — see Etapa 5's UserTable/AuthRoutes
 * changes for how that claim now reflects users.role.
 *
 * Endpoints intentionally NOT included here — no DAO/service support exists yet, tracked as
 * plan gaps (§9.E): aggregate participant/completion stats on the detail response, and
 * GET /admin/challenges/{id}/participants.
 */
fun Route.challengeAdminRoutes(
    cronSecretProvider: () -> String? = { System.getenv("CRON_SECRET") },
) {
    val challengeService: IChallengeService by application.inject()
    val challengeFinalizationService: IChallengeFinalizationService by application.inject()
    val challengeDao: IChallengeDAO by application.inject()
    val challengeProgressService: IChallengeProgressService by application.inject()
    val postDao: IPostDAO by application.inject()

    route("/admin/challenges") {
        authenticate("admin") {
            post {
                val req = try {
                    call.receive<CreateChallengeAdminRequest>()
                } catch (e: BadRequestException) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body"))
                }

                val (startsAtLocal, endsAtLocal) = parseLocalWindow(req.startsAtLocal, req.endsAtLocal)
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid startsAtLocal or endsAtLocal"))

                val createdBy = call.getUuidClaim("userId")

                try {
                    val challenge = challengeService.createDraft(
                        title = req.title,
                        description = req.description,
                        targetFamilyId = req.targetFamilyId,
                        requiredPosts = req.requiredPosts,
                        rewardPoints = req.rewardPoints,
                        startsAtLocal = startsAtLocal,
                        endsAtLocal = endsAtLocal,
                        timezone = req.timezone,
                        createdBy = createdBy,
                    )
                    call.respond(HttpStatusCode.Created, challenge.toAdminDTO())
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                }
            }

            // Keyset-paginated list, filterable by effective status (plan §9-E5). ?status, when
            // present, must name one of EffectiveChallengeStatus's values (DRAFT/SCHEDULED/
            // ACTIVE/ENDED/CANCELLED) — not the persisted ChallengeStatus, which can't tell
            // ACTIVE from a not-yet-started SCHEDULED challenge.
            get {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 0
                val cursorCreatedAt = call.request.queryParameters["cursorCreatedAt"]?.let {
                    runCatching { Instant.parse(it) }.getOrElse {
                        return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid cursorCreatedAt"))
                    }
                }
                val cursorId = call.request.queryParameters["cursorId"]?.let {
                    it.toUuidOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid cursorId"))
                }
                val statusFilter = call.request.queryParameters["status"]?.let { raw ->
                    runCatching { EffectiveChallengeStatus.valueOf(raw.uppercase()) }.getOrElse {
                        return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid status"))
                    }
                }

                try {
                    val page = challengeService.listChallenges(limit, cursorCreatedAt, cursorId, statusFilter)
                    call.respond(
                        HttpStatusCode.OK,
                        ChallengeAdminPageDTO(
                            challenges = page.challenges.map { it.toAdminDTO() },
                            nextCursor = if (page.hasMore) {
                                ChallengeListCursorDTO(page.nextCursorCreatedAt!!, page.nextCursorId!!)
                            } else {
                                null
                            },
                            hasMore = page.hasMore,
                        ),
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                }
            }

            // Full-field edit of a DRAFT challenge — every field createDraft accepts, revalidated
            // the same way (plan §9-E3). PUT (full replace), not PATCH: distinct from the
            // title/description-only edit below, which also applies once SCHEDULED.
            put("/{id}") {
                val id = call.parameters["id"].toUuidOrNull()
                    ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid id"))

                val req = try {
                    call.receive<CreateChallengeAdminRequest>()
                } catch (e: BadRequestException) {
                    return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body"))
                }

                val (startsAtLocal, endsAtLocal) = parseLocalWindow(req.startsAtLocal, req.endsAtLocal)
                    ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid startsAtLocal or endsAtLocal"))

                try {
                    val challenge = challengeService.updateDraft(
                        challengeId = id,
                        title = req.title,
                        description = req.description,
                        targetFamilyId = req.targetFamilyId,
                        requiredPosts = req.requiredPosts,
                        rewardPoints = req.rewardPoints,
                        startsAtLocal = startsAtLocal,
                        endsAtLocal = endsAtLocal,
                        timezone = req.timezone,
                    )
                    call.respond(HttpStatusCode.OK, challenge.toAdminDTO())
                } catch (e: ChallengeNotFoundException) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Challenge not found"))
                } catch (e: ChallengeNotEditableException) {
                    call.respond(HttpStatusCode.Conflict, mapOf("error" to (e.message ?: "Challenge is not editable")))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                }
            }

            get("/{id}") {
                val id = call.parameters["id"].toUuidOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid id"))

                val challenge = challengeService.findById(id)
                    ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Challenge not found"))

                call.respond(HttpStatusCode.OK, challenge.toAdminDTO())
            }

            patch("/{id}") {
                val id = call.parameters["id"].toUuidOrNull()
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid id"))

                val req = try {
                    call.receive<UpdateChallengeTitleRequest>()
                } catch (e: BadRequestException) {
                    return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body"))
                }

                try {
                    val challenge = challengeService.updateTitleAndDescription(id, req.title, req.description)
                    call.respond(HttpStatusCode.OK, challenge.toAdminDTO())
                } catch (e: ChallengeNotFoundException) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Challenge not found"))
                } catch (e: ChallengeNotEditableException) {
                    call.respond(HttpStatusCode.Conflict, mapOf("error" to (e.message ?: "Challenge is not editable")))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                }
            }

            post("/{id}/publish") {
                val id = call.parameters["id"].toUuidOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid id"))

                try {
                    val challenge = challengeService.publish(id)
                    call.respond(HttpStatusCode.OK, challenge.toAdminDTO())
                } catch (e: ChallengeNotFoundException) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Challenge not found"))
                } catch (e: ChallengeOverlapException) {
                    call.respond(
                        HttpStatusCode.Conflict,
                        mapOf("error" to (e.message ?: "Challenge overlaps another scheduled challenge")),
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                }
            }

            post("/{id}/cancel") {
                val id = call.parameters["id"].toUuidOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid id"))

                try {
                    val revokedCount = challengeService.cancel(id)
                    call.respond(HttpStatusCode.OK, RevokeResultDTO(revokedCount))
                } catch (e: ChallengeNotFoundException) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Challenge not found"))
                } catch (e: ChallengeAlreadyEndedException) {
                    call.respond(
                        HttpStatusCode.Conflict,
                        mapOf("error" to "Challenge has already ended; use /revoke-all instead"),
                    )
                }
            }

            // Retroactive remediation for a misconfigured challenge, regardless of ends_at (plan §2.6b).
            // Requires the path id to be repeated in the body as an explicit confirmation barrier.
            post("/{id}/revoke-all") {
                val id = call.parameters["id"].toUuidOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid id"))

                val req = try {
                    call.receive<RevokeAllRequest>()
                } catch (e: BadRequestException) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body"))
                }

                if (req.confirmChallengeId != id) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "confirmChallengeId must match the challenge id in the path"),
                    )
                }

                try {
                    val revokedCount = challengeService.revokeAll(id)
                    call.respond(HttpStatusCode.OK, RevokeResultDTO(revokedCount))
                } catch (e: ChallengeNotFoundException) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Challenge not found"))
                }
            }

            // Manual/debug finalize — the same engine the cron sweep and the lazy catch-up probe
            // use (plan §9-C). Idempotent: finalizing an already-finalized challenge, or one that
            // isn't due yet (still DRAFT/CANCELLED/active), is a harmless no-op reported as a
            // zero-count 200 — only a nonexistent challenge id is an error.
            post("/{id}/finalize") {
                val id = call.parameters["id"].toUuidOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid id"))

                challengeService.findById(id)
                    ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Challenge not found"))

                val result = challengeFinalizationService.finalize(id) ?: FinalizationResult(grantedCount = 0, revokedCount = 0)
                call.respond(HttpStatusCode.OK, result.toDTO())
            }

            // Recovery path for the best-effort challenge evaluation at post creation (pas 5.10):
            // PostService.createPost never fails post creation over a challenge-evaluation error
            // (plan §4.3), which means a transient failure there is otherwise unrecoverable — see
            // PostService.kt's "recoverable later via the admin reconcile endpoint" comment, which
            // predates this endpoint actually existing. Re-runs the exact same evaluation the post
            // would have gotten at creation time; a harmless no-op if it already succeeded.
            post("/reconcile-post/{postId}") {
                val postId = call.parameters["postId"].toUuidOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid postId"))

                val post = postDao.findById(postId)
                    ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Post not found"))

                val carModelId = post.carModelId
                if (post.source != PostSource.CAMERA || carModelId == null) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Post is not eligible for challenge contribution (not a camera post with a car model)"),
                    )
                }

                val evaluation = challengeProgressService.evaluatePostForActiveChallenge(
                    userId = post.userId,
                    postId = post.id,
                    carModelId = carModelId,
                    postCreatedAt = post.createdAt,
                )
                call.respond(HttpStatusCode.OK, evaluation.toReconcileDTO())
            }
        }

        // Dedicated endpoint for the external cron sweep (plan §9-C6) — the authoritative trigger
        // for finalization, alongside the manual /{id}/finalize above and the later lazy catch-up
        // probe. Gated by a cron secret header, not the "admin" JWT realm: the caller has no user
        // session. Fail-closed on an unset/blank CRON_SECRET — same pattern as
        // AdminLeaderboardRoutes.kt's /snapshot/today.
        post("/finalize-due") {
            val secret = call.request.headers["X-Cron-Secret"]
            val expectedSecret = cronSecretProvider()
            if (expectedSecret.isNullOrBlank() || secret != expectedSecret) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing cron secret"))
                return@post
            }

            val now = Instant.now()
            var finalizedChallenges = 0
            var grantedRewards = 0
            var revokedRewards = 0
            for (challenge in challengeDao.findDueForFinalization(now, FINALIZE_DUE_LIMIT)) {
                val result = try {
                    challengeFinalizationService.finalize(challenge.id, now) ?: continue
                } catch (e: Exception) {
                    logger.error("Finalization failed for challenge {}, sweep continues", challenge.id, e)
                    continue
                }
                finalizedChallenges++
                grantedRewards += result.grantedCount
                revokedRewards += result.revokedCount
            }

            call.respond(HttpStatusCode.OK, FinalizeDueResultDTO(finalizedChallenges, grantedRewards, revokedRewards))
        }

        // Alerting probe for plan §9-K3: a challenge whose window ended more than
        // STUCK_THRESHOLD ago and still isn't finalized means the finalize-due cron (or the lazy
        // catch-up probe) isn't running for it. Same X-Cron-Secret gate as /finalize-due — the
        // caller is an external monitor, not an admin session. Responds 503 (rather than 200 with
        // a count) specifically so a plain `curl -f` in a scheduled workflow fails loudly and the
        // failed run itself is the alert — see docs/challenge-finalization.md.
        get("/finalization-health") {
            val secret = call.request.headers["X-Cron-Secret"]
            val expectedSecret = cronSecretProvider()
            if (expectedSecret.isNullOrBlank() || secret != expectedSecret) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing cron secret"))
                return@get
            }

            val now = Instant.now()
            val stuck = challengeDao.findDueForFinalization(now, FINALIZE_DUE_LIMIT)
                .filter { it.endsAt.isBefore(now.minus(STUCK_THRESHOLD)) }
                .map { StuckChallengeDTO(id = it.id, endsAt = it.endsAt) }

            val status = if (stuck.isEmpty()) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
            call.respond(status, FinalizationHealthDTO(stuck))
        }
    }
}

private const val FINALIZE_DUE_LIMIT = 100
private val STUCK_THRESHOLD: Duration = Duration.ofHours(6)
