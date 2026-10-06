package app.morphe.manager.patcher.util

import app.morphe.manager.patcher.logger.Logger
import app.morphe.manager.patcher.patch.PatchBundle
import java.io.File

/**
 * Applies bundle-declared native payloads to an already prepared mono-APK.
 *
 * This contains no application-specific rules: selection, target entry and both hashes come
 * exclusively from the selected patch bundles. It intentionally runs before Session so the
 * patcher's final APK writer can restore native-library alignment.
 */
object NativePayloadApplier {
    data class Selection(
        val bundle: PatchBundle,
        val patchNames: Set<String>,
    )

    suspend fun apply(
        apkFile: File,
        selections: Collection<Selection>,
        workspace: File,
        logger: Logger,
    ): List<PatchBundle.NativePayload> {
        val selected = selections.flatMap { selection ->
            selection.bundle.nativePayloadsFor(selection.patchNames)
                .map { payload -> selection.bundle to payload }
        }
        val duplicateTargets = selected.groupBy { (_, payload) -> payload.apkEntry }
            .filterValues { it.size > 1 }
            .keys
        require(duplicateTargets.isEmpty()) {
            "Selected native payloads conflict on APK entries: ${duplicateTargets.sorted().joinToString(",")}"
        }

        if (selected.isEmpty()) return emptyList()

        val payloadDir = workspace.resolve("native-payloads").also {
            check(it.mkdirs() || it.isDirectory) { "Could not create native payload workspace" }
        }
        selected.forEach { (bundle, payload) ->
            val extracted = bundle.extractNativePayload(payload, payloadDir)
            NativeLibStripper.replaceNativeLibrary(
                apkFile,
                NativeLibStripper.NativeReplacement(
                    apkEntry = payload.apkEntry,
                    payload = extracted,
                    expectedOriginalSha256 = payload.originalSha256,
                    expectedReplacementSha256 = payload.replacementSha256,
                ),
                logger,
            )
        }
        return selected.map { it.second }
    }
}
