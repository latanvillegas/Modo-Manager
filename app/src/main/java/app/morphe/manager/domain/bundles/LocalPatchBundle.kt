package app.morphe.manager.domain.bundles

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

class LocalPatchBundle(
    name: String,
    uid: Int,
    displayName: String?,
    createdAt: Long?,
    updatedAt: Long?,
    error: Throwable?,
    directory: File,
    enabled: Boolean
) : PatchBundleSource(name, uid, displayName, createdAt, updatedAt, error, directory, enabled) {
    suspend fun replace(
        patches: InputStream,
        totalBytes: Long? = null,
        onProgress: ((bytesRead: Long, totalBytes: Long?) -> Unit)? = null
    ) {
        withContext(Dispatchers.IO) {
            installPatchBundle("Importing patch bundle") { staging ->
                staging.outputStream().use { outputStream ->
                    val buffer = ByteArray(256 * 1024)
                    var readTotal = 0L
                    while (true) {
                        val read = patches.read(buffer)
                        if (read == -1) break
                        outputStream.write(buffer, 0, read)
                        readTotal += read
                        onProgress?.invoke(readTotal, totalBytes)
                    }
                }
            }
        }
    }

    suspend fun replaceFromTempFile(
        tempFile: File,
        totalBytes: Long? = null,
        onProgress: ((bytesRead: Long, totalBytes: Long?) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        replaceBundleFromTempFile(
            tempFile = tempFile,
            target = patchesJarFile,
            validate = { requireNonEmptyBundleFile(it, "Importing patch bundle") }
        ).also { moved ->
            if (moved) onProgress?.invoke(0L, totalBytes)
        }
    }

    override fun copy(
        error: Throwable?,
        name: String,
        displayName: String?,
        createdAt: Long?,
        updatedAt: Long?,
        enabled: Boolean
    ) = LocalPatchBundle(
        name,
        uid,
        displayName,
        createdAt,
        updatedAt,
        error,
        directory,
        enabled
    )
}


internal fun replaceBundleFromTempFile(
    tempFile: File,
    target: File,
    validate: (File) -> Unit,
): Boolean {
    val backup = File(target.parentFile, "${target.name}.import-backup")
    check(target.parentFile?.mkdirs() != false || target.parentFile?.isDirectory == true) {
        "Could not create patch bundle import directory"
    }

    // Reject a bad candidate before touching the installed bundle. Validation may delete the
    // candidate on failure, while the last known-good patches.jar remains byte-for-byte intact.
    validate(tempFile)

    if (backup.exists()) {
        check(backup.delete() || !backup.exists()) {
            "Could not remove stale patch bundle import backup: ${backup.absolutePath}"
        }
    }

    val hadTarget = target.exists()
    if (hadTarget) {
        check(target.renameTo(backup)) {
            "Could not preserve installed patch bundle before import"
        }
    }

    tempFile.setReadOnly()
    if (!tempFile.renameTo(target)) {
        runCatching { tempFile.setWritable(true, true) }
        if (hadTarget) {
            check(backup.renameTo(target)) {
                "Could not restore installed patch bundle after fast import was unavailable"
            }
        }
        return false
    }

    try {
        if (hadTarget) {
            check(backup.delete() || !backup.exists()) {
                "Could not remove patch bundle import backup after successful replacement"
            }
        }
        return true
    } catch (error: Throwable) {
        try {
            runCatching { target.setWritable(true, true) }
            if (target.exists()) {
                check(target.delete() || !target.exists()) {
                    "Could not remove rejected patch bundle before rollback"
                }
            }
            if (hadTarget) {
                check(backup.renameTo(target)) {
                    "Could not restore installed patch bundle after failed import"
                }
            }
        } catch (rollbackError: Throwable) {
            error.addSuppressed(rollbackError)
        }
        throw error
    }
}
