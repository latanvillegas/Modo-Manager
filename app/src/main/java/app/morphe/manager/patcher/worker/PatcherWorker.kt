package app.morphe.manager.patcher.worker

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.morphe.manager.BuildConfig
import app.morphe.manager.MainActivity
import app.morphe.manager.ManagerApplication
import app.morphe.manager.R
import app.morphe.manager.data.platform.Filesystem
import app.morphe.manager.data.room.apps.installed.InstallType
import app.morphe.manager.domain.installer.InstallerManager
import app.morphe.manager.domain.installer.RootInstaller
import app.morphe.manager.domain.manager.KeystoreManager
import app.morphe.manager.domain.manager.PreferencesManager
import app.morphe.manager.domain.repository.InstalledAppRepository
import app.morphe.manager.domain.repository.OriginalApkRepository
import app.morphe.manager.domain.repository.PatchBundleRepository
import app.morphe.manager.domain.worker.Worker
import app.morphe.manager.domain.worker.WorkerRepository
import app.morphe.manager.patcher.logger.Logger
import app.morphe.manager.patcher.patch.ApkArchitectureResolver
import app.morphe.manager.patcher.patch.PatchSourceRef
import app.morphe.manager.patcher.runtime.CoroutineRuntime
import app.morphe.manager.patcher.runtime.ProcessRuntime
import app.morphe.manager.patcher.runtime.coerceMemoryLimit
import app.morphe.manager.patcher.runtime.heapLimitMebibytes
import app.morphe.manager.patcher.split.SplitApkPreparer
import app.morphe.manager.patcher.util.NativeLibStripper
import app.morphe.manager.patcher.util.NativeLibraryAlignment
import app.morphe.manager.patcher.util.NativePayloadApplier
import app.morphe.manager.patcher.util.ApkPreflight
import app.morphe.manager.patcher.util.FileHash
import app.morphe.manager.patcher.util.PatchRunReport
import app.morphe.manager.patcher.util.PatchStoragePreflight
import app.morphe.manager.patcher.util.TransactionalApkOutput
import app.morphe.manager.ui.model.SelectedApp
import app.morphe.manager.ui.model.State
import app.morphe.manager.util.*
import app.morphe.manager.util.PatchSelectionUtils.restrictTo
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

typealias ProgressEventHandler = (name: String?, state: State?, message: String?) -> Unit

class PatcherWorker(
    context: Context,
    parameters: WorkerParameters
) : Worker<PatcherWorker.Args>(context, parameters), KoinComponent {
    private val workerRepository: WorkerRepository by inject()
    private val prefs: PreferencesManager by inject()
    private val keystoreManager: KeystoreManager by inject()
    private val pm: PM by inject()
    private val fs: Filesystem by inject()
    private val installedAppRepository: InstalledAppRepository by inject()
    private val originalApkRepository: OriginalApkRepository by inject()
    private val patchBundleRepository: PatchBundleRepository by inject()
    private val rootInstaller: RootInstaller by inject()
    private val installerManager: InstallerManager by inject()

    class Args(
        val input: SelectedApp,
        val output: String,
        val selectedPatches: PatchSelection,
        val options: Options,
        val logger: Logger,
        val onPatchCompleted: suspend () -> Unit,
        /**
         * Patching was abandoned and started over from the first step, so anything reported by
         * the previous attempt has to be discarded rather than counted twice.
         */
        val onPatchingRestarted: suspend () -> Unit,
        val setInputFile: suspend (File, Boolean, Boolean) -> Unit,
        val onProgress: ProgressEventHandler,
        val patchSources: List<PatchSourceRef> = emptyList(),
        /**
         * Manager selection key -> bundle-declared patch name, grouped by bundle UID.
         * Native payload manifests bind to the declared name rather than a disambiguated UI key.
         */
        val declaredPatchNames: Map<Int, Map<String, String>> = emptyMap(),
        /** Optional ABI selected by the user; null keeps automatic device resolution. */
        val selectedAbi: String? = null,
        /**
         * Batch runs announce the whole queue once instead of every app, so the completion
         * tone and notification are suppressed per item.
         */
        val announceCompletion: Boolean = true,
        /** Apps already done and the queue total, null for a single run. */
        val queuePosition: Pair<Int, Int>? = null
    ) {
        val packageName get() = input.packageName
    }

    /** Queue position shown in the ongoing notification, set once the args are claimed. */
    private var queueLabel: String? = null

    override suspend fun getForegroundInfo() =
        ForegroundInfo(
            NOTIFICATION_ID,
            createNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )

    @SuppressLint("WrongConstant")
    private fun mainActivityPendingIntent(): PendingIntent {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    @SuppressLint("WrongConstant")
    private fun createNotification(
        stepName: String? = null,
        patchProgress: Pair<Int, Int>? = null, // Completed to total patches
        contentText: String? = null,
    ): Notification {
        val pendingIntent = mainActivityPendingIntent()
        return Notification.Builder(applicationContext, UpdateNotificationManager.CHANNEL_PATCHER)
            .setContentTitle(
                stepName ?: applicationContext.getString(R.string.patcher_notification_title)
            )
            .setContentText(
                contentText
                    ?: queueLabel
                    ?: applicationContext.getText(R.string.patcher_notification_text)
            )
            .apply {
                if (patchProgress != null) {
                    val (completed, total) = patchProgress
                    setSubText("$completed / $total")
                    setProgress(total, completed, false)
                }
            }
            .setSmallIcon(Icon.createWithResource(applicationContext, R.drawable.ic_notification))
            .setContentIntent(pendingIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()
    }

    private fun updatePatcherNotification(
        stepName: String?,
        patchProgress: Pair<Int, Int>? = null,
        contentText: String? = null,
    ) {
        val notificationManager =
            applicationContext.getSystemService(NotificationManager::class.java)
        // Android won't visually switch from indeterminate → determinate on the same notification
        // ID unless we first post a brief non-indeterminate update. Post the real notification
        // directly - the determinate bar replaces the spinning one cleanly this way
        notificationManager.notify(NOTIFICATION_ID, createNotification(stepName, patchProgress, contentText))
    }

    private fun showCompletionNotification(
        succeeded: Boolean,
        autoInstallPending: Boolean,
        playSound: Boolean,
        successSoundUri: String,
        errorSoundUri: String,
    ) {
        if (playSound) CompletionSound.play(
            applicationContext,
            succeeded,
            successSoundUri,
            errorSoundUri
        )
        // Don't show "patching complete" when an auto-install will immediately follow: it
        // either needs nothing from the user or asks for it in a notification of its own
        if (succeeded && autoInstallPending) return
        // Don't notify when the app is in the foreground - user sees the result on screen
        if (ManagerApplication.isInForeground) return
        val notification = Notification.Builder(applicationContext, UpdateNotificationManager.CHANNEL_PATCHER)
            .setContentTitle(
                applicationContext.getString(
                    if (succeeded) R.string.patcher_complete_title else R.string.patcher_failed_title
                )
            )
            .setContentText(applicationContext.getText(R.string.patcher_notification_text))
            .setSmallIcon(
                Icon.createWithResource(
                    applicationContext,
                    if (succeeded) R.drawable.ic_notification_done else R.drawable.ic_notification_failed
                )
            )
            .setColor(
                applicationContext.getColor(
                    if (succeeded) R.color.notification_success else R.color.notification_failure
                )
            )
            .setContentIntent(mainActivityPendingIntent())
            .setAutoCancel(true)
            .build()
        applicationContext.getSystemService(NotificationManager::class.java)
            .notify(COMPLETION_NOTIFICATION_ID, notification)
    }

    override suspend fun doWork(): Result {
        if (runAttemptCount > 0) {
            Log.d(tag, "Android requested retrying but retrying is disabled.".logFmt())
            return Result.failure()
        }

        try {
            // This does not always show up for some reason
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            // Foreground promotion can fail on some devices or when notification permission is
            // denied. Log it but continue - patching still works, just with less OS protection
            Log.w(tag, "Failed to promote worker to foreground service:".logFmt(), e)
        }

        val wakeLock: PowerManager.WakeLock =
            (applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$tag::Patcher")
                .apply {
                    // No timeout: the finally block below always releases this lock, so a cap
                    // would only risk the CPU sleeping mid-patch on large or slow devices
                    @Suppress("WakelockTimeout")
                    acquire()
                    Log.d(tag, "Acquired wakelock.")
                }

        lateinit var args: Args
        var patchingSucceeded = false
        val result = try {
            args = workerRepository.claimInput(this)
            queueLabel = args.queuePosition?.let { (done, total) ->
                applicationContext.getString(
                    R.string.batch_patch_progress_counter,
                    done.toString(),
                    total.toString()
                )
            }
            runPatcher(args).also { if (it == Result.success()) patchingSucceeded = true }
        } finally {
            wakeLock.release()
        }

        // Only delete the temporary input APK after patching if not rooted, since root mount
        // install still needs it. The UI install flow deletes disposable inputs after mounting.
        if (patchingSucceeded && Shell.isAppGrantedRoot() == false) {
            (args.input as? SelectedApp.Local)?.takeIf { it.temporary }?.file?.delete()
        }

        return result
    }

    private suspend fun runPatcher(args: Args): Result {

        val totalPatches = args.selectedPatches.values.sumOf { it.size }
        var completedPatches = 0
        // Cached so onPatchCompleted can update the title without a string lookup race
        val applyingPatchesLabel = applicationContext.getString(R.string.applying_patches)
        val writingApkLabel = applicationContext.getString(R.string.patcher_step_write_patched)
        val signingApkLabel = applicationContext.getString(R.string.patcher_step_sign_apk)
        val isExpertMode = prefs.useExpertMode.get()
        // Flipped once when the patching phase completes to trigger the writing-step notification
        var patchingPhaseCompleted = false

        fun updateProgress(name: String? = null, state: State? = null, message: String? = null) {
            if (state == State.RUNNING) {
                if (name != null) {
                    updatePatcherNotification(stepName = name, patchProgress = null)
                } else if (totalPatches > 0) {
                    updatePatcherNotification(
                        stepName = applyingPatchesLabel,
                        patchProgress = completedPatches to totalPatches
                    )
                }
            } else if (state == State.COMPLETED && !patchingPhaseCompleted
                && completedPatches == totalPatches && totalPatches > 0
            ) {
                patchingPhaseCompleted = true
                updatePatcherNotification(stepName = writingApkLabel, patchProgress = null)
            }
            args.onProgress(name, state, message)
        }

        val onPatchCompleted: suspend (String) -> Unit = { patchName ->
            completedPatches++
            // Update both title and progress bar together on every completed patch;
            // in expert mode also show which patch just finished
            updatePatcherNotification(
                stepName = applyingPatchesLabel,
                patchProgress = completedPatches to totalPatches,
                contentText = if (isExpertMode && patchName.isNotBlank()) patchName else null,
            )
            args.onPatchCompleted()
        }

        // The notification carries a patch count of its own, which would otherwise keep
        // climbing past the total once a restarted attempt reports the same patches again.
        // It goes back to the indeterminate form because the next attempt starts at loading
        // patches, not at applying them
        val onRestart: suspend () -> Unit = {
            completedPatches = 0
            patchingPhaseCompleted = false
            updatePatcherNotification(stepName = null, patchProgress = null)
            args.onPatchingRestarted()
        }

        // Every worker owns a private workspace. Fixed filenames inside fs.tempDir allow two
        // independent patch runs to overwrite each other's APKs.
        val runWorkspace = fs.tempDir.resolve("patch-run-$id").also {
            check(it.mkdirs() || it.isDirectory) { "Could not create isolated patch workspace" }
        }
        val patchedApk = runWorkspace.resolve("patched.apk")
        var preparedRuntimeInput: File? = null
        var succeeded = false
        var autoInstallPending = false
        val completionSoundEnabled = prefs.patcherCompletionSound.get()
        val successSoundUri = prefs.patcherSuccessSoundUri.get()
        val errorSoundUri = prefs.patcherErrorSoundUri.get()

        return try {
            val startTime = System.currentTimeMillis()
            val phaseStartNanos = System.nanoTime()
            var preparationDurationMs = 0L
            var patchingDurationMs = 0L
            var verificationSigningCommitDurationMs = 0L

            if (args.input is SelectedApp.Installed) {
                installedAppRepository.get(args.packageName)?.let {
                    if (it.installType == InstallType.MOUNT) {
                        rootInstaller.unmount(args.packageName)
                    }
                }
            }

            val inputFile = when (val selectedApp = args.input) {
                is SelectedApp.Local -> {
                    val needsSplit = SplitApkPreparer.isSplitArchive(selectedApp.file)
                    args.setInputFile(selectedApp.file, needsSplit, false)
                    selectedApp.file
                }

                is SelectedApp.Installed -> {
                    val source = File(pm.getPackageInfo(selectedApp.packageName)!!.applicationInfo!!.sourceDir)
                    args.setInputFile(source, false, false)
                    source
                }
            }

            val inputIsSplitArchive = SplitApkPreparer.isSplitArchive(inputFile)

            val initialDeviceStats = applicationContext.deviceStats()

            // Generic preflight: reject structurally invalid APKs before the patcher mutates anything.
            // Split archives are validated after SplitApkPreparer has produced the mono APK.
            if (!inputIsSplitArchive) {
                val preflight = ApkPreflight.inspect(inputFile)
                preflight.findings.forEach { finding ->
                    val message = "[Preflight] ${finding.code}: ${finding.message}"
                    when (finding.severity) {
                        ApkPreflight.Severity.ERROR -> args.logger.error(message)
                        ApkPreflight.Severity.WARNING -> args.logger.warn(message)
                        ApkPreflight.Severity.INFO -> args.logger.info(message)
                    }
                }
                check(preflight.canPatch) {
                    "APK preflight failed; input was not modified"
                }
                args.logger.info(
                    "[Preflight] sha256=${preflight.sha256} size=${preflight.size} " +
                        "dex=${preflight.dexEntries.size} native=${preflight.nativeEntries.size} " +
                        "abis=${preflight.abis.joinToString(",")}"
                )
            }

            val useProcessRuntime = prefs.useProcessRuntime.get()
            val stripNativeLibs = prefs.stripUnusedNativeLibs.get()
            // The architecture the patches were selected against, worth a line of its own now
            // that a patch can declare itself unavailable for the one the input carries. Read
            // from the app rather than from [inputFile], which for an installed one is the base
            // APK alone and says nothing about the split its native libraries live in
            val apkArchitecture = ApkArchitectureResolver.resolve(args.input, pm)
            val selectedCount = args.selectedPatches.values.sumOf { it.size }

            // Log device environment for diagnostics
            val deviceStats = initialDeviceStats

            // What this build of Morphe brings to the run. Every bug report needs the versions,
            // and native lib stripping silently changes what ends up in the output APK
            args.logger.info(
                "$LOG_WORKER_PREFIX_BUILD " +
                        "$LOG_WORKER_FIELD_MANAGER=${BuildConfig.VERSION_NAME} " +
                        "$LOG_WORKER_FIELD_PATCHER=${BuildConfig.PATCHER_VERSION} " +
                        "$LOG_WORKER_FIELD_NATIVE_LIBS=$stripNativeLibs"
            )

            args.logger.info(
                "$LOG_WORKER_PREFIX_DEVICE " +
                        "$LOG_WORKER_FIELD_ANDROID=${Build.VERSION.RELEASE} " +
                        "$LOG_WORKER_FIELD_API=${Build.VERSION.SDK_INT} " +
                        "$LOG_WORKER_FIELD_RAM_AVAIL=\"${formatBytesForReport(deviceStats?.ramAvailable ?: 0L)}\" " +
                        "$LOG_WORKER_FIELD_RAM_TOTAL=\"${formatBytesForReport(deviceStats?.ramTotal ?: 0L)}\" " +
                        "$LOG_WORKER_FIELD_STORAGE_AVAIL=\"${formatBytesForReport(deviceStats?.storageAvailable ?: 0L)}\" " +
                        "$LOG_WORKER_FIELD_STORAGE_TOTAL=\"${formatBytesForReport(deviceStats?.storageTotal ?: 0L)}\""
            )

            args.logger.info(
                "$LOG_WORKER_PREFIX_STARTED ${System.currentTimeMillis()} " +
                        "$LOG_WORKER_FIELD_PACKAGE=${args.packageName} " +
                        "$LOG_WORKER_FIELD_VERSION=${args.input.version} " +
                        "$LOG_WORKER_FIELD_INPUT=${inputFile.absolutePath} " +
                        "$LOG_WORKER_FIELD_SIZE=${inputFile.length()} " +
                        "$LOG_WORKER_FIELD_SPLIT=$inputIsSplitArchive " +
                        "$LOG_WORKER_FIELD_ARCH=$apkArchitecture " +
                        "$LOG_WORKER_FIELD_PATCHES=$selectedCount " +
                        "$LOG_WORKER_FIELD_DEVICE=${Build.MANUFACTURER} " +
                        "$LOG_WORKER_FIELD_MODEL=${Build.MODEL}"
            )

            // One line per source rather than a joined list, so a name and its version stay
            // together no matter how many sources contributed to this run
            args.patchSources.forEach { source ->
                args.logger.info(
                    "$LOG_WORKER_PREFIX_SOURCE $LOG_WORKER_FIELD_NAME=\"${source.name}\" " +
                            "$LOG_WORKER_FIELD_VERSION=\"${source.version ?: "?"}\""
                )
            }

            // Log runtime mode info
            if (useProcessRuntime) {
                // The limit the runtime will actually start with, not the raw setting
                val memLimit = coerceMemoryLimit(applicationContext, prefs.patcherProcessMemoryLimit.get())
                args.logger.info("$LOG_WORKER_PREFIX_RUNTIME process $LOG_WORKER_FIELD_MEMORY_LIMIT=$memLimit")
            } else {
                // CoroutineRuntime starts memory polling internally; only log the heap size here
                args.logger.logCoroutineHeap()
                args.logger.info("$LOG_WORKER_PREFIX_RUNTIME coroutine")
            }

            // Execute patching. ProcessRuntime has its own retry loop that reduces memory on OOM
            // If it still fails on Android <= Q, fall back to CoroutineRuntime
            val runtime = if (useProcessRuntime) {
                ProcessRuntime(applicationContext)
            } else {
                CoroutineRuntime(applicationContext)
            }

            val options = args.options.restrictTo(args.selectedPatches)

            // Native payloads are bundle data, never app-specific Manager rules. Resolve only
            // payloads bound to patches selected from that exact bundle.
            val availableBundles = patchBundleRepository.bundles.first()
            val missingBundleUids = args.selectedPatches.keys - availableBundles.keys
            check(missingBundleUids.isEmpty()) {
                "Selected patch bundles are no longer available: ${missingBundleUids.sorted().joinToString(",")}"
            }
            val nativeSelections = args.selectedPatches.map { (uid, patchNames) ->
                NativePayloadApplier.Selection(
                    bundle = checkNotNull(availableBundles[uid]) {
                        "Selected patch bundle disappeared during resolution: $uid"
                    },
                    patchNames = patchNames.toSet(),
                    declaredPatchNamesByKey = patchNames.associateWith { key ->
                        args.declaredPatchNames[uid]?.get(key) ?: key
                    },
                )
            }
            val nativePayloads = NativePayloadApplier.resolve(nativeSelections, args.selectedAbi)
            val nativePayloadBytes = nativePayloads
                .groupBy({ (bundle, _) -> bundle }, { (_, payload) -> payload })
                .entries.fold(0L) { total, (bundle, payloads) ->
                    Math.addExact(total, bundle.nativePayloadBytes(payloads))
                }
            // Any ZIP rewrite happens on a private input copy before the patcher. The patcher
            // writes and 16 KiB-aligns its own output afterwards, so replacement/ABI filtering
            // cannot invalidate the alignment of the exported APK.
            val needsPreparedInput = !inputIsSplitArchive && (stripNativeLibs || nativePayloads.isNotEmpty())
            val storageWorkload = PatchStoragePreflight.Workload(
                splitArchive = inputIsSplitArchive,
                preparedInput = needsPreparedInput,
                nativePayloadBytes = nativePayloadBytes,
            )
            PatchStoragePreflight.requireEnoughSpace(
                inputFile,
                initialDeviceStats?.storageAvailable,
                storageWorkload,
            )
            args.logger.info(
                "[Preflight] storage available=${initialDeviceStats?.storageAvailable ?: -1} " +
                    "required=${PatchStoragePreflight.requiredBytes(inputFile.length(), storageWorkload)}"
            )

            val runtimeInputFile = if (needsPreparedInput) {
                val preparedInput = File.createTempFile("runtime-input-", ".apk", runWorkspace)
                preparedRuntimeInput = preparedInput
                inputFile.copyTo(preparedInput, overwrite = true)

                nativePayloads.forEach { (bundle, payload) ->
                    val extracted = bundle.extractNativePayload(payload, runWorkspace.resolve("native-payloads"))
                    NativeLibStripper.replaceNativeLibrary(
                        preparedInput,
                        NativeLibStripper.NativeReplacement(
                            apkEntry = payload.apkEntry,
                            payload = extracted,
                            expectedOriginalSha256 = payload.originalSha256,
                            expectedReplacementSha256 = payload.replacementSha256,
                        ),
                        args.logger,
                    )
                }

                if (stripNativeLibs) {
                    val outputAbis = args.selectedAbi?.let(::listOf)
                        ?: Build.SUPPORTED_ABIS.filter { it.isNotBlank() }
                    NativeLibStripper.strip(preparedInput, outputAbis, args.logger)
                }
                preparedInput
            } else {
                inputFile
            }

            // After merging a split archive (in either runtime), save the resulting mono-APK
            // directly to originalApksDir so it is used for repatching instead of the archive
            val onMergedApkReady: suspend (File) -> Unit = { mergedFile ->
                val version = pm.getPackageInfo(mergedFile)?.versionName
                    ?.takeUnless { it.isBlank() }
                    ?: args.input.version
                    ?: "unknown"
                val savedFile = originalApkRepository.saveOriginalApk(
                    packageName = args.packageName,
                    version = version,
                    sourceFile = mergedFile
                )
                args.setInputFile(savedFile ?: mergedFile, true, true)
            }

            preparationDurationMs = (System.nanoTime() - phaseStartNanos) / 1_000_000
            val patchingStartNanos = System.nanoTime()
            try {
                runtime.execute(
                    inputFile = runtimeInputFile.absolutePath,
                    outputFile = patchedApk.absolutePath,
                    packageName = args.packageName,
                    selectedPatches = args.selectedPatches,
                    declaredPatchNames = args.declaredPatchNames,
                    options = options,
                    logger = args.logger,
                    onPatchCompleted = onPatchCompleted,
                    onProgress = ::updateProgress,
                    skipUnneededSplits = stripNativeLibs,
                    selectedAbi = args.selectedAbi,
                    onMergedApkReady = onMergedApkReady,
                    onRestart = onRestart,
                )
            } catch (e: Exception) {
                val fallbackReason = when {
                    !useProcessRuntime -> null
                    isBlockedSyscall(e) -> "Patcher process was killed for a system call the device forbids"
                    e is ProcessRuntime.ProcessConnectTimeoutException -> e.message
                    e is ProcessRuntime.HeapLimitIgnoredException -> e.message
                    isOomRelated(e) && Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q ->
                        "Process runtime OOM on Android ${Build.VERSION.RELEASE}"
                    else -> null
                } ?: throw e

                args.logger.warn("$fallbackReason, falling back to coroutine runtime")

                // The fallback is a fresh run of the whole pipeline, same as a memory retry
                onRestart()
                args.logger.logCoroutineHeap()

                CoroutineRuntime(applicationContext).execute(
                    inputFile = runtimeInputFile.absolutePath,
                    outputFile = patchedApk.absolutePath,
                    packageName = args.packageName,
                    selectedPatches = args.selectedPatches,
                    declaredPatchNames = args.declaredPatchNames,
                    options = options,
                    logger = args.logger,
                    onPatchCompleted = onPatchCompleted,
                    onProgress = ::updateProgress,
                    skipUnneededSplits = stripNativeLibs,
                    selectedAbi = args.selectedAbi,
                    onMergedApkReady = onMergedApkReady,
                    onRestart = onRestart,
                )
            }
            patchingDurationMs = (System.nanoTime() - patchingStartNanos) / 1_000_000
            val verificationStartNanos = System.nanoTime()

            // Patcher output is 16 KiB aligned, but any post-patch ZIP rewrite can move STORED
            // native libraries. Never sign/export an APK that Android cannot mmap safely.
            NativeLibraryAlignment.requireAligned(patchedApk)

            // Validate the patcher's unsigned output before signing or exporting it.
            val unsignedPreflight = ApkPreflight.inspect(patchedApk)
            unsignedPreflight.findings.forEach { finding ->
                val message = "[Postflight] ${finding.code}: ${finding.message}"
                when (finding.severity) {
                    ApkPreflight.Severity.ERROR -> args.logger.error(message)
                    ApkPreflight.Severity.WARNING -> args.logger.warn(message)
                    ApkPreflight.Severity.INFO -> args.logger.info(message)
                }
            }
            check(unsignedPreflight.canPatch) {
                "Patched APK failed structural postflight; output was not exported"
            }
            args.selectedAbi?.let { requestedAbi ->
                val outputAbis = unsignedPreflight.abis
                check(requestedAbi in outputAbis) {
                    "Patched APK does not contain selected ABI $requestedAbi; output ABIs=${outputAbis.joinToString(",")}"
                }
                args.logger.info("[Postflight] Selected ABI verified: $requestedAbi")
            }

            updatePatcherNotification(stepName = signingApkLabel, patchProgress = null)
            val finalOutput = File(args.output)
            TransactionalApkOutput.recover(finalOutput)
            val transactionalOutput = TransactionalApkOutput.pending(finalOutput)
            try {
                val signingResult = keystoreManager.sign(patchedApk, transactionalOutput)

                // Signing normally preserves entry offsets, but its malformed-ZIP fallback
                // repackages the archive. Verify the artifact that will actually be committed.
                NativeLibraryAlignment.requireAligned(transactionalOutput)

                val signedPreflight = ApkPreflight.inspect(transactionalOutput)
                check(signedPreflight.canPatch) {
                    "Signed APK failed structural postflight; final output was not replaced"
                }
                check(pm.getPackageInfo(transactionalOutput) != null) {
                    "Signed APK package metadata is unreadable; final output was not replaced"
                }
                val archiveCertificateHashes = pm.getApkFileSignatureHashes(transactionalOutput)
                check(archiveCertificateHashes.isNotEmpty()) {
                    "Signed APK has no verifiable signing certificate; final output was not replaced"
                }
                val expectedCertificateHashes = keystoreManager.signingCertificateHashes()
                check(expectedCertificateHashes.isNotEmpty() &&
                    archiveCertificateHashes.any { it in expectedCertificateHashes }) {
                    "Signed APK certificate does not match the active Manager keystore"
                }
                args.logger.info(
                    "[Postflight] signing certificate verified: " +
                        archiveCertificateHashes.sorted().joinToString(",")
                )

                TransactionalApkOutput.commit(finalOutput, transactionalOutput)
                verificationSigningCommitDurationMs =
                    (System.nanoTime() - verificationStartNanos) / 1_000_000

                val reportChanges = buildList {
                    add("Selected patches applied: $selectedCount")
                    if (stripNativeLibs) {
                        add(
                            args.selectedAbi?.let { "Native libraries restricted to ABI $it" }
                                ?: "Unused native ABIs stripped for this device"
                        )
                    }
                    add("Native library alignment verified at 16 KiB")
                    if (signingResult.repackagedArchive) {
                        add("Signing required ZIP archive repackaging fallback")
                    }
                    add("APK signature, certificate, structure and package metadata verified")
                }
                val report = PatchRunReport(
                    packageName = args.packageName,
                    version = args.input.version,
                    inputSha256 = if (!inputIsSplitArchive) ApkPreflight.inspect(inputFile).sha256 else null,
                    outputSha256 = ApkPreflight.inspect(finalOutput).sha256,
                    inputSize = inputFile.length(),
                    outputSize = finalOutput.length(),
                    abis = unsignedPreflight.abis,
                    selectedPatches = args.selectedPatches.values.flatten().sorted(),
                    changes = reportChanges,
                    warnings = unsignedPreflight.findings
                        .filter { it.severity == ApkPreflight.Severity.WARNING }
                        .map { "${it.code}: ${it.message}" },
                    succeeded = true,
                    managerVersion = BuildConfig.VERSION_NAME,
                    patcherVersion = BuildConfig.PATCHER_VERSION,
                    selectedAbi = args.selectedAbi,
                    bundleSources = args.selectedPatches.keys.sorted().mapNotNull { uid ->
                        availableBundles[uid]?.let { bundle ->
                            val attrs = bundle.manifestAttributes
                            "uid=$uid name=${attrs?.name ?: "?"} version=${attrs?.version ?: "?"} " +
                                "sha256=${FileHash.sha256(File(bundle.patchesJar))}"
                        }
                    },
                    nativePayloads = nativePayloads.map { (_, payload) ->
                        "${payload.id}:${payload.apkEntry}"
                    }.sorted(),
                    signingCertificateSha256 = archiveCertificateHashes.sorted(),
                    signingRepackagedArchive = signingResult.repackagedArchive,
                    durationMs = System.currentTimeMillis() - startTime,
                    phaseDurationsMs = mapOf(
                        "preparation" to preparationDurationMs,
                        "patching" to patchingDurationMs,
                        "verification_signing_commit" to verificationSigningCommitDurationMs,
                    ),
                )
                runCatching {
                    report.writeTo(
                        finalOutput.parentFile ?: fs.tempDir,
                        "${finalOutput.nameWithoutExtension}-patch-report"
                    )
                }.onFailure { error ->
                    args.logger.warn(
                        "Patched APK was committed successfully, but the diagnostic report could not be written: " +
                            (error.message ?: error::class.java.simpleName)
                    )
                }
            } finally {
                transactionalOutput.delete()
            }
            updateProgress(state = State.COMPLETED) // Signing

            val elapsed = System.currentTimeMillis() - startTime

            args.logger.info(
                "$LOG_WORKER_PREFIX_SUCCEEDED $LOG_WORKER_FIELD_OUTPUT=${args.output} " +
                        "$LOG_WORKER_FIELD_SIZE=${File(args.output).length()} " +
                        "$LOG_WORKER_FIELD_ELAPSED=${elapsed}ms"
            )

            Log.i(tag, "Patching succeeded".logFmt())
            val outputPackageName = pm.getPackageInfo(File(args.output))?.packageName ?: args.packageName
            autoInstallPending = installerManager.autoInstallAllowed(outputPackageName)
            succeeded = true
            Result.success()
        } catch (e: CancellationException) {
            args.logger.warn("Patching cancelled; temporary workspace will be discarded")
            throw e
        } catch (e: ProcessRuntime.ProcessExitException) {
            Log.e(
                tag,
                "Patcher process exited with code ${e.exitCode}".logFmt(),
                e
            )
            val message = applicationContext.getString(
                R.string.patcher_process_exit_message,
                e.exitCode.toString()
            )
            updateProgress(state = State.FAILED, message = message)
            Result.failure(
                workDataOf(
                    PROCESS_EXIT_CODE_KEY to e.exitCode,
                    PROCESS_PREVIOUS_LIMIT_KEY to e.heapLimitMb,
                    PROCESS_FAILURE_MESSAGE_KEY to message
                )
            )
        } catch (e: ProcessRuntime.HeapExhaustedException) {
            Log.e(
                tag,
                "Patcher exhausted its ${e.heapLimitMb}MB heap. ${e.originalStackTrace}".logFmt()
            )
            // The stack trace is already in the log; the failure itself says what the user can
            // act on, since no memory limit this device allows would have been enough
            val message = applicationContext.getString(
                R.string.patcher_heap_exhausted_message,
                e.heapLimitMb
            )
            updateProgress(state = State.FAILED, message = message)
            Result.failure(
                workDataOf(PROCESS_FAILURE_MESSAGE_KEY to message)
            )
        } catch (e: ProcessRuntime.RemoteFailureException) {
            Log.e(
                tag,
                "An exception occurred in the remote process while patching. ${e.originalStackTrace}".logFmt()
            )
            updateProgress(state = State.FAILED, message = e.originalStackTrace)
            Result.failure(
                workDataOf(PROCESS_FAILURE_MESSAGE_KEY to e.originalStackTrace)
            )
        } catch (e: Exception) {
            Log.e(tag, "An exception occurred while patching".logFmt(), e)
            updateProgress(state = State.FAILED, message = e.stackTraceToString())
            Result.failure(
                workDataOf(PROCESS_FAILURE_MESSAGE_KEY to e.stackTraceToString())
            )
        } finally {
            preparedRuntimeInput?.let { preparedInput ->
                if (!preparedInput.delete() && preparedInput.exists()) {
                    Log.w(tag, "Failed to delete temporary ABI-prepared APK: ${preparedInput.absolutePath}".logFmt())
                }
            }
            if (!runWorkspace.deleteRecursively() && runWorkspace.exists()) {
                Log.w(tag, "Failed to delete patch workspace: ${runWorkspace.absolutePath}".logFmt())
            }
            if (!isStopped && args.announceCompletion) showCompletionNotification(
                succeeded,
                autoInstallPending,
                completionSoundEnabled,
                successSoundUri,
                errorSoundUri
            )
        }
    }

    /**
     * Whether seccomp killed the patcher process. Firmware can load a vendor library from a
     * framework class initializer, which only runs where the zygote did not get there first,
     * so the same run survives in the app's own process.
     */
    private fun isBlockedSyscall(e: Exception) =
        e is ProcessRuntime.ProcessExitException && e.exitCode == ProcessRuntime.SIGSYS_EXIT_CODE

    private fun Logger.logCoroutineHeap() = info("$LOG_PROCESS_PREFIX_COROUTINE_HEAP ${heapLimitMebibytes()}MB")

    private fun isOomRelated(e: Exception) = when (e) {
        is ProcessRuntime.ProcessExitException ->
            e.exitCode == ProcessRuntime.OOM_EXIT_CODE || e.exitCode == ProcessRuntime.SIGKILL_EXIT_CODE
        is ProcessRuntime.HeapExhaustedException -> true
        else -> false
    }

    companion object {
        private const val LOG_PREFIX = "[Worker]"
        private fun String.logFmt() = "$LOG_PREFIX $this"

        const val NOTIFICATION_ID = 1
        const val COMPLETION_NOTIFICATION_ID = 2

        /** Kept as the patcher screen's entry point now that the tone itself is shared. */
        fun stopCompletionSound() = CompletionSound.stop()

        const val PROCESS_EXIT_CODE_KEY = "process_exit_code"
        const val PROCESS_PREVIOUS_LIMIT_KEY = "process_previous_limit"
        const val PROCESS_FAILURE_MESSAGE_KEY = "process_failure_message"

        const val LOG_WORKER_PREFIX_STARTED = "Patching started at"
        const val LOG_WORKER_PREFIX_SUCCEEDED = "Patching succeeded:"
        const val LOG_WORKER_PREFIX_DEVICE = "Device:"
        const val LOG_WORKER_PREFIX_RUNTIME = "Runtime:"
        const val LOG_WORKER_PREFIX_SOURCE = "Source:"
        const val LOG_WORKER_PREFIX_BUILD = "Build:"

        const val LOG_WORKER_FIELD_PACKAGE = "pkg"
        const val LOG_WORKER_FIELD_INPUT = "input"
        const val LOG_WORKER_FIELD_SPLIT = "split"
        const val LOG_WORKER_FIELD_ARCH = "arch"
        const val LOG_WORKER_FIELD_PATCHES = "patches"
        const val LOG_WORKER_FIELD_DEVICE = "device"
        const val LOG_WORKER_FIELD_MODEL = "model"
        const val LOG_WORKER_FIELD_OUTPUT = "output"
        const val LOG_WORKER_FIELD_NAME = "name"
        const val LOG_WORKER_FIELD_VERSION = "version"
        const val LOG_WORKER_FIELD_MANAGER = "manager"
        const val LOG_WORKER_FIELD_PATCHER = "patcher"
        const val LOG_WORKER_FIELD_NATIVE_LIBS = "nativeLibs"
        const val LOG_PROCESS_PREFIX_COROUTINE_HEAP = "App memory limit:"
        const val LOG_WORKER_FIELD_SIZE = "size"
        const val LOG_WORKER_FIELD_MEMORY_LIMIT = "memoryLimit"
        const val LOG_WORKER_FIELD_ELAPSED = "elapsed"
        const val LOG_WORKER_FIELD_ANDROID = "android"
        const val LOG_WORKER_FIELD_API = "api"
        const val LOG_WORKER_FIELD_RAM_AVAIL = "ramAvail"
        const val LOG_WORKER_FIELD_RAM_TOTAL = "ramTotal"
        const val LOG_WORKER_FIELD_STORAGE_AVAIL = "storageAvail"
        const val LOG_WORKER_FIELD_STORAGE_TOTAL = "storageTotal"
    }
}
