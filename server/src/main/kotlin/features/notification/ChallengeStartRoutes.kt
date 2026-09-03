package com.revio.server.features.notification

import com.revio.server.core.serialization.InstantSerializer
import com.revio.server.core.serialization.UUIDSerializer
import com.revio.server.features.challenge.IChallengeDAO
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Serializable
data class ChallengeStartRunResultDTO(
    val challengesProcessed: Int,
    val notified: Int,
    val skipped: Int,
)

private fun ChallengeStartRunResult.toDto() = ChallengeStartRunResultDTO(challengesProcessed, notified, skipped)

/** One entry of [ChallengeStartHealthDTO.stuckChallenges]. */
@Serializable
data class StuckChallengeStartDTO(
    @Serializable(with = UUIDSerializer::class) val id: UUID,
    @Serializable(with = InstantSerializer::class) val startsAt: Instant,
)

@Serializable
data class ChallengeStartHealthDTO(val stuckChallenges: List<StuckChallengeStartDTO>)

private const val STUCK_CHALLENGE_LIMIT = 100
private val CHALLENGE_START_STUCK_THRESHOLD: Duration = Duration.ofMinutes(30)

/**
 * Cron entry point for the challenge-start job (push-notifications plan, "challenge is live"
 * work), run every 5 minutes by the external scheduler. `X-Cron-Secret`-gated, same pattern as
 * [discoveryRoutes]' `/discovery` and [inactivityRoutes]' `/inactivity` — the caller has no user
 * session, so this is not behind the admin JWT realm. Fail-closed on an unset/blank `CRON_SECRET`.
 */
fun Route.challengeStartRoutes(
    cronSecretProvider: () -> String? = { System.getenv("CRON_SECRET") },
) {
    val challengeStartJob: IChallengeStartJob by application.inject()
    val challengeDao: IChallengeDAO by application.inject()

    route("/internal/notifications") {
        post("/challenge-start") {
            val secret = call.request.headers["X-Cron-Secret"]
            val expectedSecret = cronSecretProvider()
            if (expectedSecret.isNullOrBlank() || secret != expectedSecret) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing cron secret"))
                return@post
            }

            val result = challengeStartJob.run()
            call.respond(HttpStatusCode.OK, result.toDto())
        }

        // Alerting probe (mirrors ChallengeAdminRoutes' /finalization-health): a challenge whose
        // window opened more than STUCK_THRESHOLD ago and still isn't notified means the
        // challenge-start cron isn't running for it. Same X-Cron-Secret gate — the caller is an
        // external monitor, not an admin session. Responds 503 (rather than 200 with a count) so
        // a plain `curl -f` in a scheduled workflow fails loudly and the failed run itself is the
        // alert — see docs/push-notifications.md.
        get("/challenge-start-health") {
            val secret = call.request.headers["X-Cron-Secret"]
            val expectedSecret = cronSecretProvider()
            if (expectedSecret.isNullOrBlank() || secret != expectedSecret) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing cron secret"))
                return@get
            }

            val now = Instant.now()
            val stuck = challengeDao.findDueForStartNotification(now, STUCK_CHALLENGE_LIMIT)
                .filter { it.startsAt.isBefore(now.minus(CHALLENGE_START_STUCK_THRESHOLD)) }
                .map { StuckChallengeStartDTO(id = it.id, startsAt = it.startsAt) }

            val status = if (stuck.isEmpty()) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
            call.respond(status, ChallengeStartHealthDTO(stuck))
        }
    }
}
