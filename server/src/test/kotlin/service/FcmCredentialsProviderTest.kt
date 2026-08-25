package service

import com.revio.server.features.notification.FcmCredentialsProvider
import com.revio.server.features.notification.FirebaseProject
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Verifies 1.9's "never crashes, never leaks a secret" contract for [FcmCredentialsProvider].
 * Genuinely minting a real access token would need a live Firebase service account and a network
 * call to Google — out of reach (and inappropriate) for an automated test, so this exercises only
 * the paths that must degrade gracefully: missing env var, and malformed JSON. Env values are
 * supplied via the injectable `envValue` constructor parameter rather than real process env vars.
 */
class FcmCredentialsProviderTest {

    @Test
    fun `getAccessToken returns null when the project's env var is not set`() = runTest {
        val provider = FcmCredentialsProvider(envValue = { null })

        assertNull(provider.getAccessToken(FirebaseProject.DEBUG))
    }

    @Test
    fun `getAccessToken returns null when the project's env var is blank`() = runTest {
        val provider = FcmCredentialsProvider(envValue = { "" })

        assertNull(provider.getAccessToken(FirebaseProject.DEBUG))
    }

    @Test
    fun `getAccessToken returns null for malformed JSON instead of throwing`() = runTest {
        val provider = FcmCredentialsProvider(envValue = { "this is not json" })

        assertNull(provider.getAccessToken(FirebaseProject.DEBUG))
    }

    @Test
    fun `constructing the provider with no projects configured does not throw`() {
        assertDoesNotThrow { FcmCredentialsProvider(envValue = { null }) }
    }

    @Test
    fun `one project missing its env var does not affect the other`() = runTest {
        val provider = FcmCredentialsProvider(envValue = { key ->
            when (key) {
                "FCM_SA_JSON_DEBUG" -> null
                "FCM_SA_JSON_RELEASE" -> "also not json"
                else -> null
            }
        })

        assertNull(provider.getAccessToken(FirebaseProject.DEBUG))
        assertNull(provider.getAccessToken(FirebaseProject.RELEASE))
    }
}
