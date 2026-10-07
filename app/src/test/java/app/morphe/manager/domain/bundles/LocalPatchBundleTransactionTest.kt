package app.morphe.manager.domain.bundles

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalPatchBundleTransactionTest {
    private val dir = Files.createTempDirectory("morphe-local-bundle").toFile()
    private val target = dir.resolve("patches.jar")

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `valid replacement commits and removes backup`() {
        target.writeText("old bundle")
        val candidate = dir.resolve("candidate.jar").apply { writeText("new bundle") }

        val moved = replaceBundleFromTempFile(candidate, target) {
            check(it.readText() == "new bundle")
        }

        assertTrue(moved)
        assertEquals("new bundle", target.readText())
        assertFalse(candidate.exists())
        assertFalse(dir.resolve("patches.jar.import-backup").exists())
    }

    @Test
    fun `rejected replacement restores previous bundle`() {
        target.writeText("old bundle")
        val candidate = dir.resolve("candidate.jar").apply { writeText("invalid bundle") }

        val error = assertFailsWith<IllegalStateException> {
            replaceBundleFromTempFile(candidate, target) {
                error("rejected candidate")
            }
        }

        assertTrue(error.message.orEmpty().contains("rejected candidate"))
        assertEquals("old bundle", target.readText())
        assertFalse(dir.resolve("patches.jar.import-backup").exists())
    }

    @Test
    fun `rejected first install leaves no installed bundle`() {
        val candidate = dir.resolve("candidate.jar").apply { writeText("invalid bundle") }

        assertFailsWith<IllegalStateException> {
            replaceBundleFromTempFile(candidate, target) {
                error("rejected candidate")
            }
        }

        assertFalse(target.exists())
        assertFalse(dir.resolve("patches.jar.import-backup").exists())
    }
}
