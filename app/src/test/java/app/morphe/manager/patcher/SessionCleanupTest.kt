package app.morphe.manager.patcher

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionCleanupTest {
    @Test
    fun `construction cleanup failures are suppressed on original error`() {
        val root = Files.createTempDirectory("session-construction-cleanup-test-").toFile()
        val first = root.resolve("patcher").also { it.mkdirs() }
        val second = root.resolve("workspace").also { it.mkdirs() }
        val error = IllegalStateException("patcher construction failed")

        try {
            cleanupAfterConstructionFailure(
                error = error,
                directories = listOf(first, second),
                deleteRecursively = { false },
            )

            assertEquals("patcher construction failed", error.message)
            assertEquals(2, error.suppressed.size)
            assertTrue(error.suppressed[0].message.orEmpty().contains(first.path))
            assertTrue(error.suppressed[1].message.orEmpty().contains(second.path))
        } finally {
            root.deleteRecursively()
        }
    }
}
