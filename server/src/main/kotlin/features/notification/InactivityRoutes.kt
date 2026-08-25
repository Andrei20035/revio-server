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
data class InactivityRunResultDTO(
    val evaluated: Int,
    val sent: Int,
    val skipped: Int,
)

private fun InactivityRunResult.toDto() = InactivityRunResultDTO(evaluated, sent, skipped)

/**
 * Cron entry point for the inactivity job (plan §18, step 6.4), run daily by the external
 * scheduler. `X-Cron-Secret`-gated, same pattern as [discoveryRoutes]' `/discovery`.
 */
fun Route.inactivityRoutes(
    cronSecretProvider: () -> String? = { System.getenv("CRON_SECRET") },
) {
    val inactivityJob: IInactivityJob by application.inject()

    route("/internal/notifications") {
        post("/inactivity") {
            val secret = call.request.headers["X-Cron-Secret"]
            val expectedSecret = cronSecretProvider()
            if (expectedSecret.isNullOrBlank() || secret != expectedSecret) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing cron secret"))
                return@post
            }

            val result = inactivityJob.run()
            call.respond(HttpStatusCode.OK, result.toDto())
        }
    }
}
