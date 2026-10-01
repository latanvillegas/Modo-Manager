package app.morphe.manager.domain.patchrun

import android.net.Uri
import java.io.File

/**
 * Bridges a completed worker report to SAF without making the worker own UI or storage grants.
 * The caller supplies a tree Uri obtained through the normal Android document-tree flow.
 */
class PatchRunExportCoordinator(private val exporter: PatchRunExporter) {
    suspend fun export(
        treeUri: Uri,
        committedApk: File,
        report: PatchRunReport,
        logs: List<Pair<String, String>>,
    ): Result<List<String>> {
        if (!report.succeeded || report.stage != PatchRunStage.COMMIT) {
            return Result.failure(IllegalStateException("Only a successfully committed run can export an APK bundle"))
        }
        if (!committedApk.isFile) {
            return Result.failure(IllegalArgumentException("Committed APK is unavailable"))
        }
        val logText = logs.joinToString(separator = "\n") { (level, message) -> "[$level] $message" }
        return runCatching { exporter.export(treeUri, committedApk, report, logText) }
    }
}
