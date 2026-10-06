package app.morphe.manager.patcher.runtime

import android.content.Context
import app.morphe.manager.patcher.Session
import app.morphe.manager.patcher.logger.Logger
import app.morphe.manager.patcher.patch.PatchBundle
import app.morphe.manager.patcher.patch.PatchSelectionValidator
import app.morphe.manager.patcher.patch.applyPatchOptions
import app.morphe.manager.patcher.patch.requirePatchOptionsAvailable
import app.morphe.manager.patcher.split.SplitApkPreparer
import app.morphe.manager.patcher.util.NativePayloadApplier
import app.morphe.manager.patcher.worker.ProgressEventHandler
import app.morphe.manager.ui.model.State
import app.morphe.manager.util.Options
import app.morphe.manager.util.PatchSelection
import java.io.File

/**
 * Simple [Runtime] implementation that runs the patcher using coroutines.
 */
class CoroutineRuntime(private val context: Context) : Runtime(context) {
    override suspend fun execute(
        inputFile: String,
        outputFile: String,
        packageName: String,
        selectedPatches: PatchSelection,
        options: Options,
        logger: Logger,
        onPatchCompleted: suspend (String) -> Unit,
        onProgress: ProgressEventHandler,
        skipUnneededSplits: Boolean,
        selectedAbi: String?,
        onMergedApkReady: (suspend (File) -> Unit)?,
        // This runtime patches in the app's own process and gets one attempt at it
        onRestart: suspend () -> Unit
    ) {
        ResourceMonitor.startPolling(logger)

        try {
            val selectedBundles = selectedPatches.keys
            val bundles = bundles()

            // Only the selected bundles are read, one at a time, so a native death inside any of
            // them is attributed to it instead of leaving the next launch to crash the same way
            val allPatches = bundles
                .filterKeys { it in selectedBundles }
                .mapValues { (uid, bundle) ->
                    bundleLoadGuard.read(uid, File(bundle.patchesJar)) {
                        PatchBundle.Loader.patches(setOf(bundle), packageName).getValue(bundle)
                    }
                }

            val patchList = selectedPatches.flatMap { (bundle, selected) ->
                val bundlePatches = allPatches[bundle]
                    ?: throw IllegalArgumentException("Patch bundle $bundle does not exist")
                PatchSelectionValidator.requireAvailable(
                    bundleLabel = bundle.toString(),
                    requested = selected,
                    available = bundlePatches.keys,
                )
                bundlePatches.filterKeys { it in selected }.values
            }

            // Set all patch options
            options.forEach { (bundle, bundlePatchOptions) ->
                val patchesByName = allPatches[bundle]
                    ?: throw IllegalArgumentException("Options reference unavailable patch bundle $bundle")
                patchesByName.requirePatchOptionsAvailable(bundlePatchOptions)
                patchesByName.applyPatchOptions(bundlePatchOptions, logger)
            }

            onProgress(null, State.COMPLETED, null) // Loading patches

            val preparation = SplitApkPreparer.prepareIfNeeded(
                source = File(inputFile),
                workspace = File(cacheDir),
                logger = logger,
                skipUnneededSplits = skipUnneededSplits,
                selectedAbi = selectedAbi,
                onEvent = { event ->
                    val message = event.toLocalizedString(context)
                    logger.info(message)
                    onProgress(message, State.RUNNING, null)
                }
            )

            try {
                if (preparation.merged) {
                    NativePayloadApplier.apply(
                        apkFile = preparation.file,
                        selections = selectedPatches.map { (uid, patchNames) ->
                            NativePayloadApplier.Selection(
                                bundle = requireNotNull(bundles[uid]) {
                                    "Selected patch bundle $uid disappeared before native payload execution"
                                },
                                patchNames = patchNames,
                            )
                        },
                        workspace = preparation.file.parentFile ?: File(cacheDir),
                        logger = logger,
                        selectedAbi = selectedAbi,
                    )
                    onProgress(null, State.COMPLETED, null)
                    onMergedApkReady?.invoke(preparation.file)
                }

                Session(
                    cacheDir = cacheDir,
                    frameworkDir = frameworkPath,
                    androidContext = context,
                    logger = logger,
                    input = preparation.file,
                    onPatchCompleted = onPatchCompleted,
                    onProgress = onProgress
                ).use { session ->
                    session.run(
                        File(outputFile),
                        patchList
                    )
                }
            } finally {
                preparation.cleanup()
            }
        } finally {
            ResourceMonitor.stopPolling(logger)
        }
    }
}
