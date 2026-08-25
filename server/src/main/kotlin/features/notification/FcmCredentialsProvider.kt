package com.revio.server.features.notification

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

private val logger = LoggerFactory.getLogger("com.revio.server.features.notification.FcmCredentialsProvider")

private const val FCM_MESSAGING_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"

/** Env var holding the raw service-account JSON for [project]'s Firebase project. */
private fun envVarFor(project: FirebaseProject): String = when (project) {
    FirebaseProject.DEBUG -> "FCM_SA_JSON_DEBUG"
    FirebaseProject.RELEASE -> "FCM_SA_JSON_RELEASE"
}

interface IFcmCredentialsProvider {
    /**
     * A fresh OAuth2 access token for [project]'s Firebase service account (minted/refreshed via
     * [GoogleCredential]), or null if that project's env var is unset/blank, the JSON fails to
     * parse, or the token refresh call fails. Never throws.
     */
    suspend fun getAccessToken(project: FirebaseProject): String?
}

/**
 * Loads the two Firebase projects' service-account credentials from env (G15 — debug and release
 * use separate Firebase projects) and mints FCM HTTP v1 OAuth2 access tokens via
 * [GoogleCredential], the same google-api-client/google-oauth-client pair already used for Google
 * Sign-In verification (see [com.revio.server.features.auth.GoogleTokenVerifierImpl]) — no
 * Firebase Admin SDK dependency added.
 *
 * A project with a missing or invalid env var is simply left unconfigured: logged once at
 * construction, [getAccessToken] returns null for it, and the app keeps starting normally. This
 * class does not send any push notifications itself — see the plan's Faza 3 for that.
 *
 * [envValue] is injectable (default [System.getenv]) so tests can exercise the missing/malformed
 * paths without mutating real process environment variables.
 */
class FcmCredentialsProvider(
    envValue: (String) -> String? = System::getenv,
) : IFcmCredentialsProvider {

    private val credentials: Map<FirebaseProject, GoogleCredential> =
        FirebaseProject.entries.mapNotNull { project ->
            loadCredential(project, envValue)?.let { project to it }
        }.toMap()

    override suspend fun getAccessToken(project: FirebaseProject): String? {
        val credential = credentials[project] ?: return null
        return try {
            if (credential.refreshToken()) credential.accessToken else null
        } catch (e: Exception) {
            logger.warn("Failed to refresh FCM OAuth2 token for project={}: {}", project, e.message)
            null
        }
    }

    private fun loadCredential(project: FirebaseProject, envValue: (String) -> String?): GoogleCredential? {
        val envVar = envVarFor(project)
        val json = envValue(envVar)
        if (json.isNullOrBlank()) {
            logger.warn("FCM disabled for project={}: env var {} is not set", project, envVar)
            return null
        }
        return try {
            GoogleCredential
                .fromStream(
                    ByteArrayInputStream(json.toByteArray(StandardCharsets.UTF_8)),
                    NetHttpTransport(),
                    GsonFactory.getDefaultInstance(),
                )
                .createScoped(listOf(FCM_MESSAGING_SCOPE))
        } catch (e: Exception) {
            // e.message may echo back a fragment of malformed JSON structure (e.g. a missing
            // field name) but never the credential values themselves — never log `json` itself.
            logger.warn("FCM disabled for project={}: failed to load credentials from {}: {}", project, envVar, e.message)
            null
        }
    }
}
