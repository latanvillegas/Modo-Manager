package app.morphe.manager.patcher.util

import java.io.File

/** Recovery and commit helpers for a patched APK output. */
object TransactionalApkOutput {
    fun recover(finalOutput: File) {
        val pending = pending(finalOutput)
        val previous = previous(finalOutput)
        pending.delete()

        if (finalOutput.isFile && finalOutput.length() > 0L) {
            previous.delete()
            return
        }
        // A zero-byte final can be left by an interrupted fallback copy. It is not a committed
        // artifact and must never win over the last known-good backup.
        if (finalOutput.exists()) {
            check(finalOutput.delete()) { "Could not remove invalid patched APK before recovery" }
        }
        if (previous.exists()) {
            check(previous.renameTo(finalOutput) || runCatching {
                previous.copyTo(finalOutput, overwrite = false)
                previous.delete()
                true
            }.getOrDefault(false)) {
                "Could not restore previous patched APK"
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
        val backup = previous(finalOutput)
        backup.delete()

        if (finalOutput.exists()) {
            check(finalOutput.renameTo(backup)) {
                "Could not preserve previous output before transactional commit"
            }
        }

        try {
            if (!pendingOutput.renameTo(finalOutput)) {
                pendingOutput.copyTo(finalOutput, overwrite = true)
                pendingOutput.delete()
            }
            backup.delete()
        } catch (error: Throwable) {
            finalOutput.delete()
            if (backup.exists()) {
                val restored = backup.renameTo(finalOutput) || runCatching {
                    backup.copyTo(finalOutput, overwrite = true)
                    backup.delete()
                    true
                }.getOrDefault(false)
                if (!restored) {
                    error.addSuppressed(
                        IllegalStateException("Could not restore previous patched APK after failed commit")
                    )
                }
            }
            throw error
        }
    }

    fun pending(finalOutput: File) =
        File(finalOutput.parentFile, "${finalOutput.name}.pending")

    fun previous(finalOutput: File) =
        File(finalOutput.parentFile, "${finalOutput.name}.previous")
}
