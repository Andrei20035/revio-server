package com.revio.server.features.waitlist

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@Serializable
data class WaitlistSyncResponseDTO(
    val success: Boolean,
    val rowsFetched: Int,
    val inserted: Int,
    val updated: Int,
    val unchanged: Int,
    val conflicted: Int,
    val watermarkBefore: String?,
    val watermarkAfter: String?,
)

private fun WaitlistSyncReport.toDTO() = WaitlistSyncResponseDTO(
    success = success,
    rowsFetched = rowsFetched,
    inserted = inserted,
    updated = updated,
    unchanged = unchanged,
    conflicted = conflicted,
    watermarkBefore = watermarkBefore?.toString(),
    watermarkAfter = watermarkAfter?.toString(),
)

@Serializable
data class WaitlistEventResponseDTO(val applied: Boolean)

@Serializable
data class WaitlistHealthDTO(val lastSyncAt: String?, val rowCount: Long)

/** Payload shape of a Supabase Database Webhook delivery for an INSERT/UPDATE on waitlist_signups. */
@Serializable
private data class WaitlistWebhookPayload(
    val type: String? = null,
    val record: WaitlistWebhookRecord? = null,
)

@Serializable
private data class WaitlistWebhookRecord(
    val id: String,
    val email: String,
    val username: String? = null,
    val platform: String? = null,
    val country: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String? = null,
)

private fun WaitlistWebhookRecord.toUpsertRow() = WaitlistUpsertRow(
    id = UUID.fromString(id),
    email = email,
    username = username,
    platform = platform,
    country = country,
    sourceCreatedAt = OffsetDateTime.parse(createdAt),
    sourceUpdatedAt = updatedAt?.let { OffsetDateTime.parse(it) },
)

/**
 * Internal, secret-gated endpoints for the waitlist sync — same shape as
 * [com.revio.server.features.leaderboard.adminLeaderboardRoutes]. Two distinct secrets:
 * `X-Cron-Secret` (reuses `CRON_SECRET`, already trusted for the internal GitHub Actions cron)
 * for /sync and /health, and a separate `X-Waitlist-Secret` (`WAITLIST_WEBHOOK_SECRET`) for
 * /events, since that one is called by Supabase — a third party — not our own cron.
 *
 * [lastSuccessfulSyncAt] tracks when /sync last completed with [WaitlistSyncReport.success] true.
 * It is deliberately a heartbeat of *sync attempts*, not of data changes: the local copy's own
 * `synced_at` column only advances on rows that were actually inserted or updated, so once the
 * waitlist settles into a low-signup-rate steady state, a run that legitimately found nothing new
 * would look identical to a cron that stopped firing. /health needs to tell those apart.
 */
fun Route.waitlistRoutes(
    cronSecretProvider: () -> String? = { System.getenv("CRON_SECRET") },
    webhookSecretProvider: () -> String? = { System.getenv("WAITLIST_WEBHOOK_SECRET") },
) {
    val waitlistDao: IWaitlistDAO by application.inject()
    val syncService: IWaitlistSyncService by application.inject()

    val lastSuccessfulSyncAt = AtomicReference<OffsetDateTime?>(null)

    route("/internal/waitlist") {
        // Dedicated endpoint for the external (GitHub Actions) sync cron.
        post("/sync") {
            val secret = call.request.headers["X-Cron-Secret"]
            val expectedSecret = cronSecretProvider()
            if (expectedSecret.isNullOrBlank() || secret != expectedSecret) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing cron secret"))
                return@post
            }

            val report = syncService.reconcile()
            if (report.success) {
                lastSuccessfulSyncAt.set(OffsetDateTime.now())
            }
            call.respond(HttpStatusCode.OK, report.toDTO())
        }

        // Supabase Database Webhook target for INSERT/UPDATE on waitlist_signups. Always 200 on
        // a well-formed, correctly-authenticated payload — including a row already known here —
        // so Supabase's webhook delivery never treats an idempotent no-op as a reason to retry.
        post("/events") {
            val secret = call.request.headers["X-Waitlist-Secret"]
            val expectedSecret = webhookSecretProvider()
            if (expectedSecret.isNullOrBlank() || secret != expectedSecret) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing waitlist webhook secret"))
                return@post
            }

            val row = try {
                val payload = call.receive<WaitlistWebhookPayload>()
                val record = payload.record ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Missing record"),
                )
                record.toUpsertRow()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid webhook payload"))
                return@post
            }

            val result = syncService.applyEvent(row)
            val applied = result.inserted > 0 || result.updated > 0
            call.respond(HttpStatusCode.OK, WaitlistEventResponseDTO(applied))
        }

        // Dedicated endpoint for the external (GitHub Actions) hourly health check — a failed
        // workflow run IS the alert, there is no separate notification path.
        get("/health") {
            val secret = call.request.headers["X-Cron-Secret"]
            val expectedSecret = cronSecretProvider()
            if (expectedSecret.isNullOrBlank() || secret != expectedSecret) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing cron secret"))
                return@get
            }

            val lastSync = lastSuccessfulSyncAt.get()
            val rowCount = waitlistDao.countAll()
            val isStale = lastSync == null || lastSync.isBefore(OffsetDateTime.now().minusMinutes(60))

            val status = if (isStale) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK
            call.respond(status, WaitlistHealthDTO(lastSync?.toString(), rowCount))
        }
    }
}
