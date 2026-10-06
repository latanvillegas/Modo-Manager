package app.morphe.manager.patcher

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SessionScratchTest {
    @Test
    fun `second scratch allocation failure removes first directory`() {
        val root = createTempDirectory("session-scratch-test-").toFile()
        val patcherRoot = File(root, "patcher")
        val workspaceRoot = File(root, "workspace")
        var first: File? = null
        var calls = 0

        try {
            assertFailsWith<IllegalStateException> {
                SessionScratch.create(patcherRoot, workspaceRoot) { parent, prefix ->
                    calls++
                    if (calls == 2) error("workspace allocation failed")
                    createTempDirectory(parent.toPath(), prefix).toFile().also { first = it }
                }
            }

            assertFalse(first!!.exists(), "first scratch directory leaked after second allocation failed")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `cleanup failure is suppressed on second scratch allocation failure`() {
        val root = createTempDirectory("session-scratch-cleanup-test-").toFile()
        val patcherRoot = File(root, "patcher")
        val workspaceRoot = File(root, "workspace")
        var calls = 0

        try {
            val error = assertFailsWith<IllegalStateException> {
                SessionScratch.create(
                    patcherCacheRoot = patcherRoot,
                    fileWorkspaceRoot = workspaceRoot,
                    createTempDirectory = { parent, prefix ->
                        calls++
                        if (calls == 2) error("workspace allocation failed")
                        createTempDirectory(parent.toPath(), prefix).toFile()
                    },
                    deleteRecursively = { false },
                )
            }

            assertEquals("workspace allocation failed", error.message)
            assertEquals(1, error.suppressed.size)
            assertTrue(
                error.suppressed.single().message.orEmpty().contains(
                    "Could not remove patcher scratch after failed session allocation"
                )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `sessions receive isolated scratch directories`() {
        val root = createTempDirectory("session-scratch-isolation-").toFile()
        val patcherRoot = File(root, "patcher")
        val workspaceRoot = File(root, "workspace")

        try {
            val first = SessionScratch.create(patcherRoot, workspaceRoot)
            val second = SessionScratch.create(patcherRoot, workspaceRoot)

            assertNotEquals(first.patcherTemp, second.patcherTemp)
            assertNotEquals(first.fileWorkspace, second.fileWorkspace)

            first.deleteRecursively()
            assertTrue(second.patcherTemp.isDirectory)
            assertTrue(second.fileWorkspace.isDirectory)

            second.deleteRecursively()
        } finally {
            root.deleteRecursively()
        }
    }
}
