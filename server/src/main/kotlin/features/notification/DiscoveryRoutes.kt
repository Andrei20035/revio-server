package com.revio.server.features.notification

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject

@Serializable
data class DiscoveryRunResultDTO(
    val evaluated: Int,
    val sent: Int,
    val skipped: Int,
)

private fun DiscoveryRunResult.toDto() = DiscoveryRunResultDTO(evaluated, sent, skipped)

/**
 * Cron entry point for the discovery job (plan §18, step 6.3), run 3×/week by the external
 * scheduler. `X-Cron-Secret`-gated, same pattern as
 * [com.revio.server.features.challenge.challengeAdminRoutes]' `/finalize-due` and
 * [com.revio.server.features.leaderboard.adminLeaderboardRoutes]' `/snapshot/today` — the caller
 * has no user session, so this is not behind the admin JWT realm. Fail-closed on an unset/blank
 * `CRON_SECRET`.
 */
fun Route.discoveryRoutes(
    cronSecretProvider: () -> String? = { System.getenv("CRON_SECRET") },
) {
    val discoveryJob: IDiscoveryJob by application.inject()

    route("/internal/notifications") {
        post("/discovery") {
            val secret = call.request.headers["X-Cron-Secret"]
            val expectedSecret = cronSecretProvider()
            if (expectedSecret.isNullOrBlank() || secret != expectedSecret) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing cron secret"))
                return@post
            }

            val result = discoveryJob.run()
            call.respond(HttpStatusCode.OK, result.toDto())
        }
    }
}
