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
            fun target(mime: String, name: String): DocumentFile {
                root.findFile(name)?.delete()
                return root.createFile(mime, name) ?: error("Unable to create $name")
            }
            val names = mutableListOf<String>()
            val apkName = "$base.apk"
            context.contentResolver.openOutputStream(target("application/vnd.android.package-archive", apkName).uri, "w")!!.use { out ->
                apk.inputStream().buffered().use { it.copyTo(out) }
            }
            names += apkName
            listOf(
                Triple("application/json", "$base.json", json.encodeToString(report)),
                Triple("text/plain", "$base.txt", report.toText()),
                Triple("text/plain", "$base.log.txt", logText),
            ).forEach { (mime, name, text) ->
                context.contentResolver.openOutputStream(target(mime, name).uri, "w")!!.bufferedWriter().use { it.write(text) }
                names += name
            }
            names
        }
}
