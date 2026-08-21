package com.revio.server.core.util

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val HMAC_ALGORITHM = "HmacSHA256"

private fun emailHashSecret(): String =
    System.getProperty("JWT_SECRET")
        ?: System.getenv("JWT_SECRET")
        ?: error("JWT_SECRET environment variable is not set")

/**
 * Truncated HMAC-SHA256 of an email — never log emails in clear text (pas 5.5, unifying what was
 * three separate copies of a plain-SHA-256 `hashEmailForLogging` in AuthService, WaitlistLookupService,
 * and AccountDeletionService). Keyed by `JWT_SECRET` — already a required, high-entropy,
 * environment-specific secret — so the hash can't be reversed via a precomputed rainbow table the
 * way a naked, unkeyed SHA-256 could.
 *
 * [email] must already be normalized (`trim().lowercase()`) by the caller: the same address must
 * always produce the same hash, or log lines for the same user stop correlating with each other.
 */
fun hashEmailForLogging(email: String): String {
    val mac = Mac.getInstance(HMAC_ALGORITHM)
    mac.init(SecretKeySpec(emailHashSecret().toByteArray(Charsets.UTF_8), HMAC_ALGORITHM))
    val digest = mac.doFinal(email.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }.take(12)
}
