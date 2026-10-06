/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.domain.repository

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class OriginalApkStagingTest {
    private val dir = Files.createTempDirectory("morphe-original-apk").toFile()
    private val target = dir.resolve("com.example_1.0_original.apk")

    private fun stagedCopies() = dir.listFiles { file -> file.name.endsWith(STAGING_SUFFIX) }.orEmpty()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `failed staging cleanup preserves primary error and reports residue`() {
        val staging = dir.resolve("failed.apk$STAGING_SUFFIX").apply { writeText("partial") }
        val primary = IllegalStateException("primary failure")

        cleanupStagingAfterFailure(staging, primary) { false }

        assertTrue(staging.exists())
        assertEquals(1, primary.suppressed.size)
        assertTrue(primary.suppressed.single().message.orEmpty().contains(staging.path))
    }

    @Test
    fun `a completed copy replaces the archive that was there`() {
        target.writeText("old archive")
        val source = dir.resolve("merged.apk").apply { writeText("new archive") }

        copyThroughStaging(source, target)

        assertEquals("new archive", target.readText())
        assertTrue(stagedCopies().isEmpty())
    }

    @Test
    fun `a copy that fails leaves the previous archive in place`() {
        target.writeText("old archive")
        val unreadableSource = dir.resolve("missing.apk")

        assertFails { copyThroughStaging(unreadableSource, target) }

        assertEquals("old archive", target.readText())
        assertTrue(stagedCopies().isEmpty())
    }

    @Test
    fun `an empty source never replaces an existing archive`() {
        target.writeText("old archive")
        val emptySource = dir.resolve("empty.apk").apply { createNewFile() }

        assertFails { copyThroughStaging(emptySource, target) }

        assertEquals("old archive", target.readText())
        assertTrue(stagedCopies().isEmpty())
    }

    @Test
    fun `an empty source cannot create a retained archive`() {
        val emptySource = dir.resolve("empty.apk").apply { createNewFile() }

        assertFails { copyThroughStaging(emptySource, target) }

        assertTrue(!target.exists())
        assertTrue(stagedCopies().isEmpty())
    }

    @Test
    fun `a copy that fails without an archive to replace leaves nothing behind`() {
        val unreadableSource = dir.resolve("missing.apk")

        assertFails { copyThroughStaging(unreadableSource, target) }

        assertTrue(!target.exists())
        assertTrue(stagedCopies().isEmpty())
    }
}
