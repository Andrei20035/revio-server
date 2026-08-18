package com.revio.server.features.waitlist

import io.ktor.client.HttpClient
import io.ktor.client.engine.apache.Apache
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.OffsetDateTime
import java.util.UUID

@Serializable
private data class SupabaseWaitlistRecordDto(
    val id: String,
    val email: String,
    val username: String? = null,
    val platform: String? = null,
    val country: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String? = null,
)

private fun SupabaseWaitlistRecordDto.toUpsertRow() = WaitlistUpsertRow(
    id = UUID.fromString(id),
    email = email,
    username = username,
    platform = platform,
    country = country,
    sourceCreatedAt = OffsetDateTime.parse(createdAt),
    sourceUpdatedAt = updatedAt?.let { OffsetDateTime.parse(it) },
)

interface ISupabaseWaitlistClient {
    /**
     * Fetches one page of `waitlist_signups` rows from Supabase's PostgREST endpoint, ordered by
     * `updated_at` ascending. When [since] is non-null, only rows with `updated_at` strictly
     * greater than it are returned; when null, every row is returned (the initial import).
     * Single attempt — no retry, no backoff; that lives in [WaitlistSyncService], which is the
     * layer that can decide what "give up" means for a reconciliation run.
     */
    suspend fun fetchPage(since: OffsetDateTime?, offset: Int, limit: Int): List<WaitlistUpsertRow>

    /**
     * Point lookup for [WaitlistLookupService]'s live-lookup fallback: fetches at most one row
     * whose `email` case-insensitively matches [normalizedEmail] (already trim+lowercased by the
     * caller). Supabase's raw table has no generated `email_normalized` column of its own — this
     * uses PostgREST's `ilike` filter (without wildcards, so it behaves as an exact
     * case-insensitive match) as the closest available equivalent. Single attempt, no retry —
     * the caller applies its own timeout and circuit breaker.
     */
    suspend fun fetchByEmail(normalizedEmail: String): WaitlistUpsertRow?
}

/**
 * Real PostgREST-backed implementation. [supabaseUrlProvider] and [serviceRoleKeyProvider] are
 * injectable lambdas (same pattern as [com.revio.server.features.leaderboard.AdminLeaderboardRoutes]'s
 * `cronSecretProvider`) so tests never need `SUPABASE_URL`/`SUPABASE_SERVICE_ROLE_KEY` set — and so
 * this class can be constructed without touching the environment until a request is actually made.
 * The service role key is read only from env, is attached only as request headers, and is never
 * logged or otherwise surfaced — it must never reach the Android app or version control.
 */
class SupabaseWaitlistClient(
    private val supabaseUrlProvider: () -> String? = { System.getenv("SUPABASE_URL") },
    private val serviceRoleKeyProvider: () -> String? = { System.getenv("SUPABASE_SERVICE_ROLE_KEY") },
    private val httpClient: HttpClient = HttpClient(Apache),
) : ISupabaseWaitlistClient {

    private val json = Json { ignoreUnknownKeys = true }

    private fun requireBaseUrl(): String = supabaseUrlProvider()?.trimEnd('/')
        ?: throw IllegalStateException("SUPABASE_URL is not set")

    private fun requireServiceRoleKey(): String = serviceRoleKeyProvider()
        ?: throw IllegalStateException("SUPABASE_SERVICE_ROLE_KEY is not set")

    override suspend fun fetchPage(since: OffsetDateTime?, offset: Int, limit: Int): List<WaitlistUpsertRow> {
        val baseUrl = requireBaseUrl()
        val serviceRoleKey = requireServiceRoleKey()

        val response = httpClient.get("$baseUrl/rest/v1/waitlist_signups") {
            headers {
                append("apikey", serviceRoleKey)
                append(HttpHeaders.Authorization, "Bearer $serviceRoleKey")
            }
            parameter("select", "id,email,username,platform,country,created_at,updated_at")
            parameter("order", "updated_at.asc")
            parameter("limit", limit.toString())
            parameter("offset", offset.toString())
            if (since != null) {
                parameter("updated_at", "gt.$since")
            }
        }

        if (!response.status.isSuccess()) {
            throw IllegalStateException("Supabase waitlist fetch failed with status ${response.status}")
        }

        val records = json.decodeFromString<List<SupabaseWaitlistRecordDto>>(response.bodyAsText())
        return records.map { it.toUpsertRow() }
    }

    override suspend fun fetchByEmail(normalizedEmail: String): WaitlistUpsertRow? {
        val baseUrl = requireBaseUrl()
        val serviceRoleKey = requireServiceRoleKey()

        val response = httpClient.get("$baseUrl/rest/v1/waitlist_signups") {
            headers {
                append("apikey", serviceRoleKey)
                append(HttpHeaders.Authorization, "Bearer $serviceRoleKey")
            }
            parameter("select", "id,email,username,platform,country,created_at,updated_at")
            parameter("email", "ilike.$normalizedEmail")
            parameter("limit", "1")
        }

        if (!response.status.isSuccess()) {
            throw IllegalStateException("Supabase waitlist lookup failed with status ${response.status}")
        }

        val records = json.decodeFromString<List<SupabaseWaitlistRecordDto>>(response.bodyAsText())
        return records.firstOrNull()?.toUpsertRow()
    }
}
