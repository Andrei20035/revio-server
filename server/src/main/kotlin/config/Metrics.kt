package com.revio.server.config

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/** How many recent latency samples per route+method are kept for the p95 estimate. */
private const val LATENCY_SAMPLE_CAPACITY = 512

/** UUID-shaped path segments are collapsed to `{id}` so per-resource routes (e.g. `/posts/<uuid>`) don't each get their own unbounded registry entry. */
private val uuidSegment = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

private fun normalizeRoute(path: String): String =
    path.split("/").joinToString("/") { segment -> if (uuidSegment.matches(segment)) "{id}" else segment }

/** Request count, per-status counts, and a bounded ring buffer of latencies for one (method, route). */
private class RouteMetric {
    val requestCount = AtomicLong(0)
    val statusCounts = ConcurrentHashMap<Int, AtomicLong>()
    private val latencySamplesMs = AtomicLongArray(LATENCY_SAMPLE_CAPACITY)
    private val nextSampleIndex = AtomicLong(0)

    fun record(status: Int, durationMs: Long) {
        requestCount.incrementAndGet()
        statusCounts.getOrPut(status) { AtomicLong(0) }.incrementAndGet()
        val slot = (nextSampleIndex.getAndIncrement() % LATENCY_SAMPLE_CAPACITY).toInt()
        latencySamplesMs.set(slot, durationMs)
    }

    fun snapshotLatenciesMs(): List<Long> {
        val n = minOf(nextSampleIndex.get(), LATENCY_SAMPLE_CAPACITY.toLong()).toInt()
        return (0 until n).map { latencySamplesMs.get(it) }
    }
}

private val registry = ConcurrentHashMap<String, RouteMetric>()

private fun percentile(sortedAscending: List<Long>, p: Double): Long {
    if (sortedAscending.isEmpty()) return 0
    val index = (p * (sortedAscending.size - 1)).toInt().coerceIn(0, sortedAscending.size - 1)
    return sortedAscending[index]
}

private fun renderMetrics(): String {
    val sb = StringBuilder()
    registry.toSortedMap().forEach { (key, metric) ->
        val total = metric.requestCount.get()
        val serverErrors = metric.statusCounts.entries
            .filter { (status, _) -> status in 500..599 }
            .sumOf { (_, count) -> count.get() }
        val rate5xx = if (total > 0) serverErrors.toDouble() / total else 0.0
        val p95Ms = percentile(metric.snapshotLatenciesMs().sorted(), 0.95)
        sb.appendLine(
            "route=\"$key\" requests=$total 5xx_rate=${"%.4f".format(rate5xx)} p95_ms=$p95Ms"
        )
    }
    return sb.toString()
}

/** Aggregate 5xx rate across every route currently in the registry — feeds pas 3.8a's `/health`. */
fun overallServerErrorRate(): Double {
    var total = 0L
    var serverErrors = 0L
    registry.values.forEach { metric ->
        total += metric.requestCount.get()
        serverErrors += metric.statusCounts.entries
            .filter { (status, _) -> status in 500..599 }
            .sumOf { (_, count) -> count.get() }
    }
    return if (total > 0) serverErrors.toDouble() / total else 0.0
}

private fun requireMetricsSecret(): String =
    System.getProperty("METRICS_SECRET")
        ?: System.getenv("METRICS_SECRET")
        ?: error("METRICS_SECRET environment variable is not set")

/**
 * Records per (method, route) request count, status-code breakdown, and a bounded latency sample
 * for a p95 estimate — see [RouteMetric]. Exposes the aggregate as plain text on `/metrics`,
 * gated by a shared secret (`X-Metrics-Secret` header, never a query parameter — this endpoint
 * must never end up in an access log per pas 3.2b).
 */
fun Application.configureMetrics(metricsSecret: String = requireMetricsSecret()) {
    intercept(ApplicationCallPipeline.Monitoring) {
        val startNanos = System.nanoTime()
        try {
            proceed()
        } finally {
            val durationMs = (System.nanoTime() - startNanos) / 1_000_000
            val method = call.request.httpMethod.value
            val route = normalizeRoute(call.request.path())
            val status = call.response.status()?.value ?: 0
            registry.getOrPut("$method $route") { RouteMetric() }.record(status, durationMs)
        }
    }

    routing {
        get("/metrics") {
            if (call.request.headers["X-Metrics-Secret"] != metricsSecret) {
                call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
                return@get
            }
            call.respondText(renderMetrics(), ContentType.Text.Plain)
        }
    }
}
