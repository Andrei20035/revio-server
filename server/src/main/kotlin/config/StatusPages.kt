package com.revio.server.config

import com.revio.server.core.error.AuthApiError
import com.revio.server.core.error.AuthApiException
import com.revio.server.core.error.AuthErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import org.slf4j.LoggerFactory
import org.slf4j.MDC

private val logger = LoggerFactory.getLogger("com.revio.server.config.StatusPages")

/**
 * Seam for pas 3.7 (error tracking, per 0.4's shortlist). 0.4 deliberately defers the vendor
 * choice between Categoria A (self-hosted, e.g. GlitchTip) and Categoria B (SaaS, e.g. Sentry) —
 * see `revio-server/docs/error-tracking-shortlist.md`. Adopting either means a new dependency and
 * possibly a new `docker-compose.yml` service, neither in scope for this step without that
 * decision. Until it's made, [NoOpErrorReporter] is the active implementation; swapping in a real
 * SDK later only means changing [errorReporter]'s assignment, not this file's exception handling.
 */
interface ErrorReporter {
    /** [tags] always carries `callId` (pas 3.1b/3.2a) so a reported exception can be correlated
     * with the same request's log line and, via `X-Request-Id` (pas 1.8), the client's crash report. */
    fun report(cause: Throwable, tags: Map<String, String>)
}

private object NoOpErrorReporter : ErrorReporter {
    override fun report(cause: Throwable, tags: Map<String, String>) = Unit
}

private val errorReporter: ErrorReporter = NoOpErrorReporter

fun Application.configureAuthStatusPages() {
    install(StatusPages) {
        exception<AuthApiException> { call, cause ->
            call.respond(
                cause.statusCode,
                AuthErrorResponse(
                    error = AuthApiError(
                        code = cause.code,
                        message = cause.message
                    )
                )
            )
        }

        // Catch-all: pas 3.3a — every unhandled exception is logged with its stack trace
        // (callId comes along via MDC, see pas 3.2a) and the client gets a generic 500
        // with no exception details.
        exception<Throwable> { call, cause ->
            logger.error(
                "Unhandled exception on ${call.request.httpMethod.value} ${call.request.path()}",
                cause
            )
            errorReporter.report(cause, mapOf("callId" to (MDC.get(CALL_ID_MDC_KEY) ?: "unknown")))
            call.respondText(
                text = "Internal server error",
                status = HttpStatusCode.InternalServerError
            )
        }
    }
}
