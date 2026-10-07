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
        val target = patchesJarFile
        val backup = File(target.parentFile, "${target.name}.import-backup")
        target.parentFile?.mkdirs()

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

        // Made read-only before the swap rather than after: a concurrent reader must never see
        // a writable dex container at the installed path.
        tempFile.setReadOnly()
        if (!tempFile.renameTo(target)) {
            runCatching { tempFile.setWritable(true, true) }
            if (hadTarget) {
                check(backup.renameTo(target)) {
                    "Could not restore installed patch bundle after fast import was unavailable"
                }
            }
            return@withContext false
        }

        try {
            requireNonEmptyBundleFile(target, "Importing patch bundle")
            if (hadTarget) {
                check(backup.delete() || !backup.exists()) {
                    "Could not remove patch bundle import backup after successful replacement"
                }
            }
            onProgress?.invoke(0L, totalBytes)
            true
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
