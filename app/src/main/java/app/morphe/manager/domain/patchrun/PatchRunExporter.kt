package app.morphe.manager.domain.patchrun

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class PatchRunExporter(private val context: Context) {
    companion object {
        val json = Json { prettyPrint = true; encodeDefaults = true; explicitNulls = true }
    }

    suspend fun export(treeUri: Uri, apk: File, report: PatchRunReport, logText: String): List<String> =
        withContext(Dispatchers.IO) {
            val root = DocumentFile.fromTreeUri(context, treeUri)
                ?: throw IllegalArgumentException("Selected export destination is not a document tree")
            require(root.canWrite()) { "Selected export destination is not writable" }
            val base = "morphe_${report.runId}"
            val artifacts = listOf(
                Triple("application/vnd.android.package-archive", "$base.apk", apk.readBytes()),
                Triple("application/json", "$base.json", json.encodeToString(report).toByteArray()),
                Triple("text/plain", "$base.txt", report.toText().toByteArray()),
                Triple("text/plain", "$base.log.txt", logText.toByteArray()),
            )
            artifacts.map { (mime, name, bytes) ->
                root.findFile(name)?.delete()
                val target = root.createFile(mime, name) ?: error("Unable to create $name")
                context.contentResolver.openOutputStream(target.uri, "w")!!.use { it.write(bytes) }
                name
            }
        }
}
