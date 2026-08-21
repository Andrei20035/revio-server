package com.revio.server.config

import io.ktor.server.application.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import org.slf4j.event.Level

/** Prefixes excluded from access logs — high-volume, low-signal (pas 3.2b). */
private val EXCLUDED_LOG_PATH_PREFIXES = listOf("/uploads", "/static")

fun Application.configureMonitoring() {
    install(CallLogging) {
        level = Level.INFO
        filter { call ->
            val path = call.request.path()
            path.startsWith("/") && EXCLUDED_LOG_PATH_PREFIXES.none { path.startsWith(it) }
        }
        // Explicit format using path() (never call.request.uri) so query strings — e.g. an admin
        // search term — never reach the log line (pas 3.2b / G17).
        format { call ->
            val status = call.response.status()?.value ?: "Unhandled"
            val method = call.request.httpMethod.value
            val path = call.request.path()
            "$status: $method - $path"
        }
    }
}
