package contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Field-by-field contract test (pas 4.5) between server DTOs and their Android mirrors, for the
 * P0 path (auth request/response, post creation, feed). A source-text parser, not real
 * reflection: the two repos are separate Gradle projects (JVM vs Android) with no shared
 * classpath, so this reads each `.kt` file as text and compares enum-value / field-name sets.
 * That is exactly the level C7 (AuthErrorCode missing a value on Android) drifted at — this test
 * makes that kind of drift fail a build instead of surviving until someone notices in production.
 *
 * Assumes both repos are checked out side by side (`.../revio-workspace/{revio-server,
 * revio-android}/`), as the workspace's own CLAUDE.md describes for every local dev checkout.
 * When the Android repo isn't found — a server-only checkout, or a CI job that only clones this
 * repo — every test here is marked *aborted* (via [assumeTrue], not a silent pass) rather than
 * failing the build over an environment it can't do anything about.
 */
class DtoContractTest {

    private val androidRoot: File? = File("../../revio-android").takeIf { it.exists() }

    private fun android(path: String): File {
        assumeTrue(androidRoot != null, "revio-android not found next to revio-server — skipping contract test")
        return androidRoot!!.resolve(path)
    }

    /** Names of the constants inside `enum class $enumName { ... }` in [file]. */
    private fun extractEnumValues(file: File, enumName: String): Set<String> {
        val text = file.readText()
        val match = Regex("""enum class\s+$enumName\b[^{]*\{""").find(text)
            ?: error("enum class $enumName not found in ${file.path}")
        val afterBrace = text.substring(match.range.last + 1)
        val body = afterBrace.substring(0, afterBrace.indexOf('}'))
        return Regex("""\b([A-Z][A-Z0-9_]*)\b""").findAll(body).map { it.groupValues[1] }.toSet()
    }

    /** Names of every `val fieldName: ...` in `(data )class $className(...)`'s primary constructor. */
    private fun extractDataClassFields(file: File, className: String): Set<String> {
        val text = file.readText()
        val match = Regex("""class\s+$className\b[^{(]*\(""").find(text)
            ?: error("class $className not found in ${file.path}")
        var depth = 1
        var i = match.range.last + 1
        while (depth > 0 && i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
        }
        val constructorBody = text.substring(match.range.last + 1, i - 1)
        return Regex("""val\s+(\w+)\s*:""").findAll(constructorBody).map { it.groupValues[1] }.toSet()
    }

    @Test
    fun `AuthErrorCode has the same values on server and Android`() {
        val serverValues = extractEnumValues(
            File("src/main/kotlin/core/error/AuthErrorCode.kt"),
            "AuthErrorCode",
        )
        val androidValues = extractEnumValues(
            android("app/src/main/java/com/revio/social/data/remote/dto/auth/AuthError.kt"),
            "AuthErrorCode",
        )
        assertEquals(serverValues, androidValues)
    }

    @Test
    fun `LoginRequest has the same fields on server and Android`() {
        val serverValues = extractDataClassFields(
            File("src/main/kotlin/features/auth/dto/LoginRequest.kt"),
            "LoginRequest",
        )
        val androidValues = extractDataClassFields(
            android("app/src/main/java/com/revio/social/data/remote/dto/auth/LoginRequest.kt"),
            "LoginRequest",
        )
        assertEquals(serverValues, androidValues)
    }

    @Test
    fun `RegisterRequest has the same fields on server and Android`() {
        val serverValues = extractDataClassFields(
            File("src/main/kotlin/features/auth/dto/RegisterRequest.kt"),
            "RegisterRequest",
        )
        val androidValues = extractDataClassFields(
            android("app/src/main/java/com/revio/social/data/remote/dto/auth/RegisterRequest.kt"),
            "RegisterRequest",
        )
        assertEquals(serverValues, androidValues)
    }

    @Test
    fun `AuthResponse has the same fields on server and Android`() {
        val serverValues = extractDataClassFields(
            File("src/main/kotlin/features/auth/dto/AuthResponse.kt"),
            "AuthResponse",
        )
        val androidValues = extractDataClassFields(
            android("app/src/main/java/com/revio/social/data/remote/dto/auth/AuthResponse.kt"),
            "AuthResponse",
        )
        assertEquals(serverValues, androidValues)
    }

    @Test
    fun `CreatePostResponse has the same fields on server and Android`() {
        val serverValues = extractDataClassFields(
            File("src/main/kotlin/features/post/dto/CreatePostResponse.kt"),
            "CreatePostResponse",
        )
        val androidValues = extractDataClassFields(
            android("app/src/main/java/com/revio/social/data/remote/dto/post/CreatePostResponse.kt"),
            "CreatePostResponse",
        )
        assertEquals(serverValues, androidValues)
    }

    @Test
    fun `server PostDTO and Android FeedPostDto have the same fields`() {
        val serverValues = extractDataClassFields(
            File("src/main/kotlin/features/post/dto/PostDTO.kt"),
            "PostDTO",
        )
        val androidValues = extractDataClassFields(
            android("app/src/main/java/com/revio/social/data/remote/dto/post/FeedPostDto.kt"),
            "FeedPostDto",
        )
        assertEquals(serverValues, androidValues)
    }
}
