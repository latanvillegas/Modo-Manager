package app.morphe.manager.patcher

import java.io.File
import java.nio.file.Files

/**
 * Scratch directories owned by one patching session.
 *
 * Allocation is transactional: if creating the second directory fails, the first one is removed
 * before the original error is propagated. This matters because a failed Session constructor can
 * never be closed by its caller.
 */
internal data class SessionScratch(
    val patcherTemp: File,
    val fileWorkspace: File,
) {
    fun deleteRecursively() {
        patcherTemp.deleteRecursively()
        fileWorkspace.deleteRecursively()
    }

    companion object {
        fun create(
            patcherCacheRoot: File,
            fileWorkspaceRoot: File,
            createTempDirectory: (File, String) -> File = { root, prefix ->
                Files.createTempDirectory(root.toPath(), prefix).toFile()
            },
        ): SessionScratch {
            ensureDirectory(patcherCacheRoot, "patcher cache")
            val patcherTemp = createTempDirectory(patcherCacheRoot, "patcher-")

            try {
                ensureDirectory(fileWorkspaceRoot, "app cache")
                val fileWorkspace = createTempDirectory(fileWorkspaceRoot, "patch-workspace-")
                return SessionScratch(patcherTemp, fileWorkspace)
            } catch (error: Throwable) {
                patcherTemp.deleteRecursively()
                throw error
            }
        }

        private fun ensureDirectory(directory: File, label: String) {
            check(directory.mkdirs() || directory.isDirectory) {
                "Could not create $label directory: ${directory.path}"
            }
        }
    }
}
