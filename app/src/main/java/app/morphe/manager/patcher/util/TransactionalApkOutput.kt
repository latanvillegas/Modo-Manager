package app.morphe.manager.patcher.util

import java.io.File

/** Recovery and commit helpers for a patched APK output. */
object TransactionalApkOutput {
    fun recover(finalOutput: File) {
        val pending = pending(finalOutput)
        val previous = previous(finalOutput)
        pending.delete()

        if (finalOutput.exists()) {
            previous.delete()
            return
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

    fun pending(finalOutput: File) =
        File(finalOutput.parentFile, "${finalOutput.name}.pending")

    fun previous(finalOutput: File) =
        File(finalOutput.parentFile, "${finalOutput.name}.previous")
}
