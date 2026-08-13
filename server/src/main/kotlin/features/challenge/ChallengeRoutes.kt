package com.revio.server.features.challenge

import com.revio.server.core.storage.IStorageService
import com.revio.server.core.util.getUuidClaim
import com.revio.server.core.util.toUuidOrNull
import com.revio.server.features.car_family.CarFamily
import com.revio.server.features.car_family.ICarFamilyService
import com.revio.server.features.challenge.dto.ChallengeContributionDTO
import com.revio.server.features.challenge.dto.ChallengeDTO
import com.revio.server.features.challenge.dto.ChallengeHistoryItemDTO
import com.revio.server.features.challenge.dto.ChallengeProgressDTO
import com.revio.server.features.challenge.dto.ChallengeProgressDetailDTO
import com.revio.server.features.challenge.dto.ChallengeSummaryDTO
import com.revio.server.features.challenge.dto.CurrentChallengeDTO
import com.revio.server.features.challenge.dto.MyChallengesDTO
import io.ktor.http.*
import io.ktor.server.application.Application
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.launch
import org.koin.ktor.ext.inject
import java.time.Instant

/**
 * [family] must already be resolved by the caller — via [com.revio.server.features.car_family.ICarFamilyService.getFamilies],
 * batched across every challenge in the response, not one [com.revio.server.features.car_family.ICarFamilyService.getFamily]
 * call per challenge (see the plan's Pas 3/§9-N+1: `GET /challenges/me` would otherwise turn one
 * page of N challenges into N extra queries).
 */
private fun Challenge.toDTO(family: CarFamily?): ChallengeDTO = ChallengeDTO(
    id = id,
    title = title,
    description = description,
    targetFamilyBrand = family?.brand.orEmpty(),
    targetFamilyName = family?.name.orEmpty(),
    requiredPosts = requiredPosts,
    rewardPoints = rewardPoints,
    startsAt = startsAt,
    endsAt = endsAt,
)

private fun ParticipantProgress.toDTO(challenge: Challenge, now: Instant) = ChallengeProgressDTO(
    contributionCount = contributionCount,
    rewardState = rewardState.name,
    participantState = participantState(challenge, this, now).name,
)

private fun ChallengeUserAggregates.toDTO() = ChallengeSummaryDTO(
    joinedCount = joinedCount,
    completedCount = completedCount,
    pointsEarned = pointsEarned,
    totalContributions = totalContributions,
)

/**
 * Cheap catch-up probe (plan §9-C8): checks the partial index
 * (idx_challenges_pending_finalization, migration V28) for a due challenge and, if one exists,
 * finalizes it on the application's own coroutine scope — fire-and-forget, never awaited, wrapped
 * in runCatching so neither the probe nor the background finalization can ever fail this request.
 * Same best-effort principle as PostService's own challenge evaluation on post creation.
 */
private suspend fun triggerFinalizationCatchUp(
    application: Application,
    challengeDao: IChallengeDAO,
    challengeFinalizationService: IChallengeFinalizationService,
) {
    runCatching {
        val now = Instant.now()
        val due = challengeDao.findDueForFinalization(now, limit = 1)
        if (due.isNotEmpty()) {
            application.launch {
                runCatching {
                    due.forEach { challenge -> challengeFinalizationService.finalize(challenge.id, now) }
                }
            }
        }
    }
}

/**
 * Read-only, user-facing challenge endpoints (plan §5's "Utilizator" table). All instants are
 * UTC ISO-8601 — the client converts to the viewer's own timezone, never the server.
 */
fun Route.challengeRoutes() {
    val challengeService: IChallengeService by application.inject()
    val challengeProgressService: IChallengeProgressService by application.inject()
    val carFamilyService: ICarFamilyService by application.inject()
    val storageService: IStorageService by application.inject()
    val challengeDao: IChallengeDAO by application.inject()
    val challengeFinalizationService: IChallengeFinalizationService by application.inject()

    route("/challenges") {
        authenticate("jwt") {
            get("/current") {
                triggerFinalizationCatchUp(call.application, challengeDao, challengeFinalizationService)

                val userId = call.getUuidClaim("userId")
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid userId claim"))

                val now = Instant.now()
                val challenge = challengeService.findCurrentOrNext(now)
                if (challenge == null) {
                    call.respond(HttpStatusCode.OK, CurrentChallengeDTO(challenge = null, progress = null))
                    return@get
                }

                val progress = challengeProgressService.getUserProgress(challenge.id, userId)
                val family = carFamilyService.getFamilies(setOf(challenge.targetFamilyId))[challenge.targetFamilyId]
                call.respond(
                    HttpStatusCode.OK,
                    CurrentChallengeDTO(
                        challenge = challenge.toDTO(family),
                        progress = progress.toDTO(challenge, now),
                        effectiveStatus = effectiveStatus(challenge, now).name,
                    ),
                )
            }

            get("/me") {
                triggerFinalizationCatchUp(call.application, challengeDao, challengeFinalizationService)

                val userId = call.getUuidClaim("userId")
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid userId claim"))

                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 0
                val cursorEndsAt = call.request.queryParameters["cursorEndsAt"]?.let {
                    runCatching { Instant.parse(it) }.getOrElse {
                        return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid cursorEndsAt"))
                    }
                }
                val cursorId = call.request.queryParameters["cursorId"]?.let {
                    it.toUuidOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid cursorId"))
                }
                val filter = call.request.queryParameters["filter"]?.let { raw ->
                    runCatching { ChallengeHistoryFilter.valueOf(raw.uppercase()) }.getOrElse {
                        return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid filter"))
                    }
                }

                try {
                    val now = Instant.now()
                    val page = challengeProgressService.listUserHistory(userId, limit, cursorEndsAt, cursorId, filter)
                    // Summary only on the first page — see MyChallengesDTO's KDoc.
                    val summary = if (cursorEndsAt == null && cursorId == null) {
                        challengeProgressService.getUserAggregates(userId).toDTO()
                    } else {
                        null
                    }

                    val families = carFamilyService.getFamilies(page.items.map { it.challenge.targetFamilyId }.toSet())

                    call.respond(
                        HttpStatusCode.OK,
                        MyChallengesDTO(
                            summary = summary,
                            challenges = page.items.map { item ->
                                ChallengeHistoryItemDTO(
                                    challenge = item.challenge.toDTO(families[item.challenge.targetFamilyId]),
                                    effectiveStatus = item.effectiveStatus.name,
                                    progress = item.progress.toDTO(item.challenge, now),
                                )
                            },
                            hasMore = page.hasMore,
                            nextCursorEndsAt = page.nextCursorEndsAt,
                            nextCursorId = page.nextCursorId,
                        ),
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                }
            }

            get("/{id}") {
                val id = call.parameters["id"].toUuidOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid id"))

                // findPublicById, not findById: a DRAFT challenge (never published) must 404 here,
                // the same as one that doesn't exist at all — see IChallengeService's KDoc.
                val challenge = challengeService.findPublicById(id)
                    ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Challenge not found"))

                val family = carFamilyService.getFamilies(setOf(challenge.targetFamilyId))[challenge.targetFamilyId]
                call.respond(HttpStatusCode.OK, challenge.toDTO(family))
            }

            get("/{id}/progress") {
                val id = call.parameters["id"].toUuidOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid id"))
                val userId = call.getUuidClaim("userId")
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid userId claim"))

                val challenge = challengeService.findPublicById(id)
                    ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Challenge not found"))

                val progress = challengeProgressService.getUserProgress(id, userId)
                val contributions = challengeProgressService.listUserContributions(id, userId)

                call.respond(
                    HttpStatusCode.OK,
                    ChallengeProgressDetailDTO(
                        progress = progress.toDTO(challenge, Instant.now()),
                        contributions = contributions.map {
                            ChallengeContributionDTO(
                                postId = it.postId,
                                createdAt = it.createdAt,
                                imageUrl = it.imageKey?.let { key -> storageService.resolveUrl(key) },
                                carBrand = it.carBrand,
                                carModel = it.carModel,
                            )
                        },
                    ),
                )
            }
        }
    }
}
