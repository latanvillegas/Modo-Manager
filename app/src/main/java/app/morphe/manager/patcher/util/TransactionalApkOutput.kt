package app.morphe.manager.patcher.util

import java.io.File

/** Recovery and commit helpers for a patched APK output. */
object TransactionalApkOutput {
    fun recover(finalOutput: File) {
        val pending = pending(finalOutput)
        val previous = previous(finalOutput)
        deleteIfExists(pending, "stale pending patched APK")

        if (finalOutput.isFile && finalOutput.length() > 0L) {
            deleteIfExists(previous, "stale previous patched APK")
            return
        }
        // A zero-byte final can be left by an interrupted fallback copy. It is not a committed
        // artifact and must never win over the last known-good backup.
        if (finalOutput.exists()) {
            check(finalOutput.delete()) { "Could not remove invalid patched APK before recovery" }
        }
        if (previous.exists()) {
            if (!previous.isFile || previous.length() == 0L) {
                check(previous.delete()) { "Could not remove invalid previous patched APK" }
                return
            }
            check(previous.renameTo(finalOutput) || runCatching {
                previous.copyTo(finalOutput, overwrite = false)
                check(previous.delete()) { "Could not remove previous patched APK after recovery copy" }
                true
            }.getOrDefault(false)) {
                "Could not restore previous patched APK"
            }
            check(finalOutput.isFile && finalOutput.length() > 0L) {
                "Restored patched APK is missing or empty"
            }
        }
    }

    /**
     * Atomically as possible promotes [pendingOutput] while preserving the last good output.
     * A failed copy/rename restores the previous artifact before propagating the error.
     */
    fun commit(finalOutput: File, pendingOutput: File) {
        require(pendingOutput.isFile) { "Pending patched APK does not exist" }
        require(pendingOutput.length() > 0L) { "Pending patched APK is empty" }
        if (finalOutput.exists()) {
            require(finalOutput.isFile && finalOutput.length() > 0L) {
                "Existing patched APK output is not a valid regular file: ${finalOutput.path}"
            }
        }
        val backup = previous(finalOutput)
        deleteIfExists(backup, "stale previous patched APK")

        if (finalOutput.exists()) {
            check(finalOutput.renameTo(backup)) {
                "Could not preserve previous output before transactional commit"
            }
        }

        val expectedSize = pendingOutput.length()
        try {
            if (!pendingOutput.renameTo(finalOutput)) {
                pendingOutput.copyTo(finalOutput, overwrite = true)
                check(finalOutput.isFile && finalOutput.length() == expectedSize) {
                    "Committed patched APK size mismatch: expected $expectedSize, got ${finalOutput.length()}"
                }
                check(pendingOutput.delete()) { "Could not remove pending APK after successful copy" }
            }
            check(finalOutput.isFile && finalOutput.length() == expectedSize) {
                "Committed patched APK size mismatch: expected $expectedSize, got ${finalOutput.length()}"
            }
            deleteIfExists(backup, "previous patched APK after successful commit")
        } catch (error: Throwable) {
            if (finalOutput.exists() && !finalOutput.delete()) {
                error.addSuppressed(
                    IllegalStateException("Could not remove failed patched APK before rollback: ${finalOutput.path}")
                )
            }
            if (backup.exists()) {
                val rollbackFailure = runCatching {
                    if (!backup.renameTo(finalOutput)) {
                        backup.copyTo(finalOutput, overwrite = true)
                        check(backup.delete()) {
                            "Could not remove previous patched APK after rollback copy"
                        }
                    }
                    check(finalOutput.isFile && finalOutput.length() > 0L) {
                        "Restored patched APK is missing or empty after failed commit"
                    }
                }.exceptionOrNull()
                if (rollbackFailure != null) {
                    error.addSuppressed(
                        IllegalStateException(
                            "Could not restore previous patched APK after failed commit",
                            rollbackFailure,
                        )
                    )
                }
            }
            throw error
        }
    }

    private fun deleteIfExists(file: File, label: String) {
        if (file.exists()) {
            check(file.delete()) { "Could not remove $label: ${file.path}" }
        }
    }

    fun pending(finalOutput: File) =
        File(finalOutput.parentFile, "${finalOutput.name}.pending")

    fun previous(finalOutput: File) =
        File(finalOutput.parentFile, "${finalOutput.name}.previous")
}
