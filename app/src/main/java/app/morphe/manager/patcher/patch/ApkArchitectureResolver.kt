/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.patcher.patch

import android.os.Build
import app.morphe.manager.patcher.split.SplitApkPreparer
import app.morphe.manager.patcher.util.Abi
import app.morphe.manager.patcher.util.NativeLibStripper
import app.morphe.manager.ui.model.SelectedApp
import app.morphe.manager.util.PM
import app.morphe.patcher.patch.ApkArchitecture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * The architecture patches are asked about, read out of the APK a run starts from.
 *
 * Exactly one ABI survives a run: a split archive is merged with the device's own ABI split, and
 * the native libraries of every other one are stripped from the output, so the patched app always
 * ends up built for the ABI the device prefers among those the input carries. An APK without any
 * native code is not built for an architecture at all and stays [ApkArchitecture.UNIVERSAL].
 */
object ApkArchitectureResolver {
    /** Architecture of the APK [selectedApp] will be patched from. */
    suspend fun resolve(selectedApp: SelectedApp, pm: PM): ApkArchitecture =
        withContext(Dispatchers.IO) {
            when (selectedApp) {
                is SelectedApp.Local -> resolve(selectedApp.file)
                is SelectedApp.Installed -> resolve(installedApks(selectedApp.packageName, pm))
            }
        }

    /** Architecture of a single APK or split archive. */
    suspend fun resolve(file: File): ApkArchitecture = resolve(listOf(file))

    /**
     * Architecture of an app spread over several files, such as the base APK and the config
     * splits of an installed app, whose native libraries live in a split of their own.
     */
    suspend fun resolve(files: List<File>): ApkArchitecture = withContext(Dispatchers.IO) {
        of(files.filter { it.exists() }.flatMap(::abisOf))
    }

    /**
     * Resolves an explicit ABI chosen by the user. Null keeps automatic device selection.
     * The requested ABI must actually exist in the input; otherwise the run is rejected before
     * patch compatibility is evaluated.
     */
    fun ofSelected(abis: Collection<String>, selectedAbi: String?): ApkArchitecture {
        if (selectedAbi == null) return of(abis)
        val normalized = selectedAbi.lowercase(Locale.ROOT)
        val present = abis.map { it.lowercase(Locale.ROOT) }.toSet()
        require(normalized in present) {
            "Selected ABI $selectedAbi is not present in the input"
        }
        return Abi.architectureOf(normalized)
            ?: throw IllegalArgumentException("Unsupported ABI: $selectedAbi")
    }

    /** Architecture an APK carrying [abis] under lib/ is patched for on this device. */
    fun of(abis: Collection<String>): ApkArchitecture =
        of(abis, Build.SUPPORTED_ABIS?.toList().orEmpty())

    internal fun of(abis: Collection<String>, deviceAbis: List<String>): ApkArchitecture {
        val present = abis.mapNotNullTo(mutableSetOf()) { abi ->
            abi.lowercase(Locale.ROOT).takeIf { Abi.architectureOf(it) != null }
        }
        if (present.isEmpty()) return ApkArchitecture.UNIVERSAL

        // Asked of the stripper rather than decided here, so the answer stays the ABI the run
        // actually keeps. An APK carrying nothing this device runs is still answered by what it
        // does carry, so patches see the file for what it is instead of where it was opened
        val abi = NativeLibStripper.preferredAbi(present, deviceAbis.map { it.lowercase(Locale.ROOT) })
            ?: Abi.NAMES.first { it in present }

        return Abi.architectureOf(abi) ?: ApkArchitecture.UNIVERSAL
    }

    fun abisOf(selectedApp: SelectedApp, pm: PM): List<String> = when (selectedApp) {
        is SelectedApp.Local -> abisOf(selectedApp.file)
        is SelectedApp.Installed -> installedApks(selectedApp.packageName, pm).flatMap(::abisOf).distinct()
    }

    private fun installedApks(packageName: String, pm: PM): List<File> {
        val info = pm.getPackageInfo(packageName)?.applicationInfo ?: return emptyList()
        return (listOf(info.sourceDir) + info.splitSourceDirs?.toList().orEmpty()).map(::File)
    }

    private fun abisOf(file: File): List<String> =
        if (SplitApkPreparer.isSplitArchive(file)) SplitApkPreparer.splitArchiveAbis(file)
        else NativeLibStripper.extractAbisFromApk(file)
}
