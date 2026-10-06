package app.morphe.manager.patcher

import android.content.Context
import app.morphe.manager.R
import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.apk.ApkUtils.applyTo
import app.morphe.patcher.patch.Patch
import app.morphe.patcher.patch.PatchResult
import app.morphe.manager.patcher.Session.Companion.component1
import app.morphe.manager.patcher.Session.Companion.component2
import app.morphe.manager.patcher.logger.Logger
import app.morphe.manager.ui.model.State
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal typealias PatchList = List<Patch<*>>

class Session(
    cacheDir: String,
    frameworkDir: String,
    private val androidContext: Context,
    private val logger: Logger,
    private val input: File,
    private val onPatchCompleted: suspend (String) -> Unit,
    private val onProgress: (name: String?, state: State?, message: String?) -> Unit
) : Closeable {
    private fun updateProgress(name: String? = null, state: State? = null, message: String? = null) =
        onProgress(name, state, message)

    private val tempRoot = File(cacheDir).also {
        check(it.mkdirs() || it.isDirectory) { "Could not create patcher cache directory: ${it.path}" }
    }
    private val tempDir = Files.createTempDirectory(tempRoot.toPath(), "patcher-").toFile()

    // Scratch space for patches must be private to this Session too. A shared patch-workspace
    // lets concurrent workers overwrite each other's files even when their APK inputs differ.
    private val fileWorkspaceRoot = androidContext.cacheDir.also {
        check(it.mkdirs() || it.isDirectory) { "Could not create app cache directory: ${it.path}" }
    }
    private val fileWorkspace =
        Files.createTempDirectory(fileWorkspaceRoot.toPath(), "patch-workspace-").toFile()

    private val patcher = Patcher(
        PatcherConfig(
            apkFile = input,
            temporaryFilesPath = tempDir,
            frameworkFileDirectory = frameworkDir,
            fileWorkspacePath = fileWorkspace
        )
    )

    private suspend fun Patcher.applyPatchesVerbose(selectedPatches: PatchList) {
        updateProgress(state = State.RUNNING)

        this().collect { (patch, exception) ->
            if (patch !in selectedPatches) return@collect

            if (exception != null) {
                updateProgress(
                    name = androidContext.getString(R.string.failed_to_execute_patch, patch.name),
                    state = State.FAILED,
                    message = exception.stackTraceToString()
                )

                logger.error("${patch.name} failed:")
                logger.error(exception.stackTraceToString())
                throw exception
            }

            onPatchCompleted(patch.name.orEmpty())

            logger.info("${patch.name} succeeded")
        }

        updateProgress(
            state = State.COMPLETED,
            name = androidContext.resources.getQuantityString(
                R.plurals.patches_executed,
                selectedPatches.size,
                selectedPatches.size.toString()
            )
        )
    }

    suspend fun run(output: File, selectedPatches: PatchList) {
        updateProgress(state = State.COMPLETED) // Unpacking

        java.util.logging.Logger.getLogger("").apply {
            handlers.forEach {
                it.close()
                removeHandler(it)
            }

            addHandler(logger.handler)
        }

        with(patcher) {
            this += selectedPatches.toSet()

            logger.info("Applying patches...")
            applyPatchesVerbose(selectedPatches.sortedBy { it.name })
        }

        logger.info("Writing patched files...")
        val result = withContext(Dispatchers.Default) {
            // patcher.get() writes dex files, then encodes resources, so run on default pool
            // instead of main thread.
            patcher.get()
        }

        val patched = tempDir.resolve("result.apk")
        withContext(Dispatchers.IO) {
            Files.copy(input.toPath(), patched.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }

        withContext(Dispatchers.Default) {
            // Run on default pool instead of I/O since we're processing large files in our own code
            result.applyTo(patched)
        }

        logger.info("Patched apk saved to $patched")

        withContext(Dispatchers.IO) {
            Files.move(patched.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        updateProgress(state = State.COMPLETED) // Saving
    }

    override fun close() {
        try {
            // Let the patcher release anything it still owns before its scratch directories vanish.
            patcher.close()
        } finally {
            if (!tempDir.deleteRecursively() && tempDir.exists()) {
                logger.warn("Failed to delete patcher session temp directory: ${tempDir.absolutePath}")
            }
            if (!fileWorkspace.deleteRecursively() && fileWorkspace.exists()) {
                logger.warn("Failed to delete patcher file workspace: ${fileWorkspace.absolutePath}")
            }
        }
    }

    companion object {
        operator fun PatchResult.component1() = patch
        operator fun PatchResult.component2() = exception
    }
}
