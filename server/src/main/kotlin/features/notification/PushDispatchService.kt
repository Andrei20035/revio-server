package com.revio.server.features.notification

import com.revio.server.config.NotificationMetrics
import io.ktor.client.HttpClient
import io.ktor.client.engine.apache.Apache
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** FCM's own project id for each of the two Firebase projects (G15 — see plan §2). */
private fun projectIdFor(project: FirebaseProject): String = when (project) {
    FirebaseProject.DEBUG -> "revio-debug-47037"
    FirebaseProject.RELEASE -> "carspotter-f2b68"
}

/** `android.priority` for the FCM v1 payload — drives system tray delivery priority, not push urgency wording. */
enum class FcmPriority {
    NORMAL,
    HIGH,
}

/** Why FCM rejected the message outright — both are terminal, never retried (plan §14). */
enum class FcmTerminalReason {
    /** The token is no longer valid (app uninstalled, data cleared, or FCM registration expired). */
    UNREGISTERED,

    /** The request itself was malformed — a bug in the payload we sent, retrying changes nothing. */
    INVALID_ARGUMENT,
}

/**
 * The outcome of a single FCM HTTP v1 send call, already classified per plan §14's status
 * vocabulary. Does not itself decide retry/backoff policy or touch `notification_outbox` — that
 * bookkeeping belongs to the dispatcher loop (plan §18, step 3.5/3.6), which is expected to
 * consume this result.
 */
sealed class FcmSendResult {
    /** FCM accepted the message ("accepted by FCM" in plan §14 — not proof of delivery). */
    data class Accepted(val fcmMessageId: String) : FcmSendResult()

    /** A transient failure (5xx, 429) — worth retrying with backoff. [retryAfterSeconds] mirrors FCM's `Retry-After` header, if present. */
    data class Retriable(val httpStatus: Int, val retryAfterSeconds: Long? = null) : FcmSendResult()

    /** A permanent failure — retrying would never succeed. */
    data class Terminal(val reason: FcmTerminalReason) : FcmSendResult()

    /** An FCM response that doesn't match any of the above — surfaced as-is rather than guessed at. */
    data class Unknown(val httpStatus: Int, val body: String) : FcmSendResult()

    /** [project] has no usable service-account credentials (see [FcmCredentialsProvider]) — no HTTP call was made. */
    data class Unconfigured(val project: FirebaseProject) : FcmSendResult()
}

interface IPushDispatchService {
    /**
     * Sends one FCM HTTP v1 data message to [fcmToken] on [project]. Title and body travel in
     * `data` so Android always builds the notification itself with consistent icons and channels.
     *
     * [ttlSeconds], when set, is passed through as the FCM message's own `android.ttl` so FCM
     * itself won't deliver something past its freshness window (plan §14's "Freshness TTL").
     */
    suspend fun send(
        project: FirebaseProject,
        fcmToken: String,
        title: String,
        body: String,
        data: Map<String, String> = emptyMap(),
        priority: FcmPriority = FcmPriority.NORMAL,
        ttlSeconds: Long? = null,
    ): FcmSendResult
}

@Serializable
private data class FcmSendRequest(val message: FcmMessage)

@Serializable
private data class FcmMessage(
    val token: String,
    val data: Map<String, String>,
    val android: FcmAndroidConfig? = null,
)

@Serializable
private data class FcmAndroidConfig(val priority: String, val ttl: String? = null)

@Serializable
private data class FcmSendResponse(val name: String)

@Serializable
private data class FcmErrorEnvelope(val error: FcmErrorBody)

@Serializable
private data class FcmErrorBody(
    val code: Int,
    val message: String = "",
    val status: String? = null,
    val details: List<FcmErrorDetail> = emptyList(),
)

@Serializable
private data class FcmErrorDetail(
    @SerialName("@type") val type: String? = null,
    val errorCode: String? = null,
)

/**
 * Real FCM HTTP v1 caller — no Firebase Admin SDK, same google-api-client/ktor-client pair
 * already used elsewhere in this codebase (see [FcmCredentialsProvider], [ISupabaseWaitlistClient]).
 */
class PushDispatchService(
    private val credentialsProvider: IFcmCredentialsProvider,
    private val httpClient: HttpClient = HttpClient(Apache),
) : IPushDispatchService {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun send(
        project: FirebaseProject,
        fcmToken: String,
        title: String,
        body: String,
        data: Map<String, String>,
        priority: FcmPriority,
        ttlSeconds: Long?,
    ): FcmSendResult {
        val accessToken = credentialsProvider.getAccessToken(project)
            ?: return FcmSendResult.Unconfigured(project)

        val requestBody = FcmSendRequest(
            message = FcmMessage(
                token = fcmToken,
                data = buildMap {
                    putAll(data)
                    put("title", title)
                    put("body", body)
                },
                android = FcmAndroidConfig(
                    priority = if (priority == FcmPriority.HIGH) "high" else "normal",
                    ttl = ttlSeconds?.let { "${it}s" },
                ),
            ),
        )

        val url = "https://fcm.googleapis.com/v1/projects/${projectIdFor(project)}/messages:send"
        NotificationMetrics.outboxSent()
        val startNanos = System.nanoTime()
        val response = httpClient.post(url) {
            headers { append(HttpHeaders.Authorization, "Bearer $accessToken") }
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(FcmSendRequest.serializer(), requestBody))
        }
        NotificationMetrics.fcmLatency((System.nanoTime() - startNanos) / 1_000_000)

        return classifyResponse(
            json = json,
            isSuccess = response.status.isSuccess(),
            statusCode = response.status.value,
            retryAfterHeader = response.headers[HttpHeaders.RetryAfter],
            responseText = response.bodyAsText(),
        )
    }
}

/**
 * Pure response -> [FcmSendResult] mapping, pulled out of [PushDispatchService.send] so the
 * classification rules (plan §14's status vocabulary) can be unit-tested directly against
 * canned FCM response bodies, without a real HTTP round trip.
 */
internal fun classifyResponse(
    json: Json,
    isSuccess: Boolean,
    statusCode: Int,
    retryAfterHeader: String?,
    responseText: String,
): FcmSendResult {
    if (isSuccess) {
        val parsed = runCatching { json.decodeFromString(FcmSendResponse.serializer(), responseText) }.getOrNull()
        return if (parsed != null) {
            FcmSendResult.Accepted(parsed.name)
        } else {
            FcmSendResult.Unknown(statusCode, responseText)
        }
    }

    val errorCode = runCatching { json.decodeFromString(FcmErrorEnvelope.serializer(), responseText) }
        .getOrNull()
        ?.error
        ?.details
        ?.firstOrNull { it.errorCode != null }
        ?.errorCode

    return when (errorCode) {
        "UNREGISTERED" -> FcmSendResult.Terminal(FcmTerminalReason.UNREGISTERED)
        "INVALID_ARGUMENT" -> FcmSendResult.Terminal(FcmTerminalReason.INVALID_ARGUMENT)
        else -> when {
            statusCode == 429 || statusCode >= 500 -> {
                FcmSendResult.Retriable(statusCode, retryAfterHeader?.toLongOrNull())
            }
            statusCode == 404 -> FcmSendResult.Terminal(FcmTerminalReason.UNREGISTERED)
            statusCode == 400 -> FcmSendResult.Terminal(FcmTerminalReason.INVALID_ARGUMENT)
            else -> FcmSendResult.Unknown(statusCode, responseText)
        }
    }
}
