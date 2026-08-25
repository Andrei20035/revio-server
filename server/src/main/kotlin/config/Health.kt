package com.revio.server.config

import com.revio.server.features.notification.FirebaseProject
import com.revio.server.features.notification.IFcmCredentialsProvider
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.jetbrains.exposed.sql.transactions.transaction
import org.koin.ktor.ext.inject
import java.io.File

/** Above this aggregate 5xx rate (pas 3.6), `/health` reports unhealthy. */
private const val SERVER_ERROR_RATE_THRESHOLD = 0.10

private data class HealthCheckResult(val name: String, val healthy: Boolean, val detail: String? = null)

/** A trivial round-trip query — fails if the pool can't reach Postgres. */
private fun checkDatabase(): HealthCheckResult = try {
    transaction { exec("SELECT 1") }
    HealthCheckResult("database", healthy = true)
} catch (e: Exception) {
    HealthCheckResult("database", healthy = false, detail = e.message)
}

/**
 * Storage has no connectivity probe on [com.revio.server.core.storage.IStorageService] (adding
 * one is out of scope here — pas 3.8a lists only this new file), so this checks what's reachable
 * without one: for the local backend, that the upload directory exists and is writable; for R2,
 * that the required configuration is present (same env vars as [com.revio.server.core.storage.R2Config.fromEnv]).
 * Neither performs an actual network round-trip to R2.
 */
private fun checkStorage(): HealthCheckResult {
    val provider = System.getenv("STORAGE_PROVIDER") ?: "local"
    return when (provider) {
        "r2" -> {
            val required = listOf("R2_ACCOUNT_ID", "R2_BUCKET", "R2_ACCESS_KEY_ID", "R2_SECRET_ACCESS_KEY", "R2_PUBLIC_BASE_URL")
            val missing = required.filter { System.getenv(it).isNullOrBlank() }
            if (missing.isEmpty()) {
                HealthCheckResult("storage", healthy = true)
            } else {
                HealthCheckResult("storage", healthy = false, detail = "missing env: ${missing.joinToString()}")
            }
        }
        else -> {
            val dir = File(System.getenv("LOCAL_STORAGE_BASE_DIR") ?: "uploads")
            if (dir.exists() && dir.canWrite()) {
                HealthCheckResult("storage", healthy = true)
            } else {
                HealthCheckResult("storage", healthy = false, detail = "local storage dir not writable: ${dir.path}")
            }
        }
    }
}

private fun checkServerErrorRate(): HealthCheckResult {
    val rate = overallServerErrorRate()
    return if (rate > SERVER_ERROR_RATE_THRESHOLD) {
        HealthCheckResult("5xx_rate", healthy = false, detail = "%.2f%% exceeds %.0f%% threshold".format(rate * 100, SERVER_ERROR_RATE_THRESHOLD * 100))
    } else {
        HealthCheckResult("5xx_rate", healthy = true)
    }
}

/**
 * `/health` — 200 while the 5xx rate (pas 3.6), the database, and storage are all fine; 503 the
 * moment any one of them isn't, so `curl -f` (or any uptime check treating non-2xx as failure)
 * fails correctly.
 */
fun Application.configureHealth() {
    val fcmCredentialsProvider: IFcmCredentialsProvider by inject()

    routing {
        get("/health") {
            val checks = listOf(checkServerErrorRate(), checkDatabase(), checkStorage())
            val failing = checks.filterNot { it.healthy }
            if (failing.isEmpty()) {
                call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
            } else {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    mapOf(
                        "status" to "unhealthy",
                        "failing" to failing.map { mapOf("check" to it.name, "detail" to it.detail) },
                    )
                )
            }
        }

        // Separate from `/health` on purpose: FCM isn't used to send anything yet (see the
        // push-notifications plan's Faza 3), so an unconfigured project here must never flip the
        // app's own liveness/readiness signal to unhealthy. Always 200 — the body carries the
        // per-project status. Reveals only which of the two known projects (DEBUG/RELEASE)
        // successfully minted a token; the reason for a failure is in the server logs
        // (FcmCredentialsProvider), never here, and neither ever includes the credential itself.
        get("/health/fcm") {
            val statuses = FirebaseProject.entries.associate { project ->
                project.name to mapOf("healthy" to (fcmCredentialsProvider.getAccessToken(project) != null))
            }
            call.respond(HttpStatusCode.OK, mapOf("fcm" to statuses))
        }
    }
}
