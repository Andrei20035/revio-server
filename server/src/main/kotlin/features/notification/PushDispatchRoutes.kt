package com.revio.server.features.notification

import com.revio.server.config.NotificationMetrics
import com.revio.server.config.renderNotificationMetrics
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject

@Serializable
data class TestPushRequestDTO(
    val fcmToken: String,
    val firebaseProject: FirebaseProject,
    val title: String,
    val body: String,
    val data: Map<String, String> = emptyMap(),
    val priority: FcmPriority = FcmPriority.NORMAL,
    val ttlSeconds: Long? = null,
)

@Serializable
data class TestPushResultDTO(
    val outcome: String,
    val fcmMessageId: String? = null,
    val httpStatus: Int? = null,
    val terminalReason: String? = null,
)

private fun FcmSendResult.toDto(): TestPushResultDTO = when (this) {
    is FcmSendResult.Accepted -> TestPushResultDTO(outcome = "ACCEPTED", fcmMessageId = fcmMessageId)
    is FcmSendResult.Retriable -> TestPushResultDTO(outcome = "RETRIABLE", httpStatus = httpStatus)
    is FcmSendResult.Terminal -> TestPushResultDTO(outcome = "TERMINAL", terminalReason = reason.name)
    is FcmSendResult.Unknown -> TestPushResultDTO(outcome = "UNKNOWN", httpStatus = httpStatus)
    is FcmSendResult.Unconfigured -> TestPushResultDTO(outcome = "UNCONFIGURED")
}

/**
 * Manual test-send endpoint for Faza 3 (plan §18, step 3.4): lets a developer push a single FCM
 * message end-to-end — to a real device, on either Firebase project — without any dispatcher,
 * outbox, or aggregation involved. Human-operated (`X-Admin-Token`), same shape as
 * [com.revio.server.features.leaderboard.adminLeaderboardRoutes]' `/snapshot` route, deliberately
 * distinct from the `X-Cron-Secret`-protected automated endpoints the plan lists for the
 * dispatcher/aggregate/discovery/inactivity jobs.
 */
fun Route.pushDispatchRoutes(
    adminTokenProvider: () -> String? = { System.getenv("ADMIN_PUSH_TEST_TOKEN") },
) {
    val pushDispatchService: IPushDispatchService by application.inject()
    val outboxDao: INotificationOutboxDAO by application.inject()

    route("/internal/notifications") {
        /**
         * Plain-text render of plan §16's notification metrics (pas 7.3) — `outbox.queue_depth`
         * and `outbox.age_p50/p95` (enqueue -> accepted) among them, per that step's acceptance
         * criterion that both be visible. Same `X-Admin-Token` gate as `/test-send`, not the
         * `X-Metrics-Secret` used by [com.revio.server.config.configureMetrics]'s generic
         * per-route `/metrics` — this is notification-pipeline-specific and lives with the rest
         * of this feature's internal routes.
         */
        get("/metrics") {
            val token = call.request.headers["X-Admin-Token"]
            val expectedToken = adminTokenProvider()
            if (expectedToken.isNullOrBlank() || token != expectedToken) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing admin token"))
                return@get
            }
            val queueDepth = outboxDao.countQueued()
            call.respondText(renderNotificationMetrics(NotificationMetrics.snapshot(), queueDepth), ContentType.Text.Plain)
        }

        post("/test-send") {
            val token = call.request.headers["X-Admin-Token"]
            val expectedToken = adminTokenProvider()
            if (expectedToken.isNullOrBlank() || token != expectedToken) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing admin token"))
                return@post
            }

            val request = call.receive<TestPushRequestDTO>()
            val result = pushDispatchService.send(
                project = request.firebaseProject,
                fcmToken = request.fcmToken,
                title = request.title,
                body = request.body,
                data = request.data,
                priority = request.priority,
                ttlSeconds = request.ttlSeconds,
            )
            call.respond(HttpStatusCode.OK, result.toDto())
        }
    }
}
