package architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards against reintroducing a post-edit path that bypasses
 * PostService.updatePostAsAuthor's vehicle lock — the only call site allowed to invoke
 * PostDAO.updateById. That method is where the check against
 * IChallengeProgressService.hasContributions lives (plan §5/§9-Pas3): a second caller could
 * change a post's car model without ever consulting it, silently reopening the exploit where an
 * already-rewarded (or still-active) challenge contribution is retargeted to a different vehicle
 * after the fact.
 */
class PostUpdateVehicleLockGuardTest {

    @Test
    fun `PostDAO updateById appears in exactly one production file`() {
        val sourceRoot = File("src/main/kotlin")
        check(sourceRoot.isDirectory) { "Expected ${sourceRoot.absolutePath} to exist" }

        val matches = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains(".updateById(") }
            .map { it.relativeTo(sourceRoot).path.replace(File.separatorChar, '/') }
            .toList()

        assertEquals(
            listOf("features/post/PostService.kt"),
            matches,
            "PostDAO.updateById must only be called from PostService.updatePostAsAuthor, which enforces the vehicle lock; found it in: $matches",
        )
    }
}
