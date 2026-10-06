package app.morphe.manager.patcher.util

import java.io.File

/** Deterministic, exportable diagnostics for a patch run. */
data class PatchRunReport(
    val packageName: String,
    val version: String?,
    val inputSha256: String?,
    val outputSha256: String?,
    val inputSize: Long,
    val outputSize: Long?,
    val abis: List<String>,
    val selectedPatches: List<String>,
    val changes: List<String>,
    val warnings: List<String>,
    val succeeded: Boolean,
    val managerVersion: String? = null,
    val patcherVersion: String? = null,
    val selectedAbi: String? = null,
    val bundleSources: List<String> = emptyList(),
    val nativePayloads: List<String> = emptyList(),
    val signingCertificateSha256: List<String> = emptyList(),
    val signingRepackagedArchive: Boolean? = null,
    val durationMs: Long? = null,
    val phaseDurationsMs: Map<String, Long> = emptyMap(),
) {
    fun toText(): String = buildString {
        appendLine("package=$packageName")
        appendLine("version=${version ?: "?"}")
        appendLine("input_sha256=${inputSha256 ?: "?"}")
        appendLine("output_sha256=${outputSha256 ?: "?"}")
        appendLine("input_size=$inputSize")
        appendLine("output_size=${outputSize ?: -1}")
        appendLine("abis=${abis.joinToString(",")}")
        appendLine("succeeded=$succeeded")
        appendLine("manager_version=${managerVersion ?: "?"}")
        appendLine("patcher_version=${patcherVersion ?: "?"}")
        appendLine("selected_abi=${selectedAbi ?: "auto"}")
        appendLine("duration_ms=${durationMs ?: -1}")
        phaseDurationsMs.toSortedMap().forEach { (phase, millis) ->
            appendLine("phase_ms.$phase=$millis")
        }
        bundleSources.forEach { appendLine("bundle=$it") }
        nativePayloads.forEach { appendLine("native_payload=$it") }
        signingCertificateSha256.forEach { appendLine("signing_certificate_sha256=$it") }
        appendLine("signing_repackaged_archive=${signingRepackagedArchive ?: "?"}")
        selectedPatches.forEach { appendLine("patch=$it") }
        changes.forEach { appendLine("change=$it") }
        warnings.forEach { appendLine("warning=$it") }
    }

    fun toJson(): String = buildString {
        append('{')
        append("\"packageName\":\"${json(packageName)}\",")
        append("\"version\":${version?.let { "\"${json(it)}\"" } ?: "null"},")
        append("\"inputSha256\":${inputSha256?.let { '\"' + json(it) + '\"' } ?: "null"},")
        append("\"outputSha256\":${outputSha256?.let { "\"${json(it)}\"" } ?: "null"},")
        append("\"inputSize\":$inputSize,")
        append("\"outputSize\":${outputSize ?: "null"},")
        append("\"abis\":${array(abis)},")
        append("\"selectedPatches\":${array(selectedPatches)},")
        append("\"changes\":${array(changes)},")
        append("\"warnings\":${array(warnings)},")
        append("\"managerVersion\":${managerVersion?.let { "\"${json(it)}\"" } ?: "null"},")
        append("\"patcherVersion\":${patcherVersion?.let { "\"${json(it)}\"" } ?: "null"},")
        append("\"selectedAbi\":${selectedAbi?.let { "\"${json(it)}\"" } ?: "null"},")
        append("\"bundleSources\":${array(bundleSources)},")
        append("\"nativePayloads\":${array(nativePayloads)},")
        append("\"signingCertificateSha256\":${array(signingCertificateSha256)},")
        append("\"signingRepackagedArchive\":${signingRepackagedArchive ?: "null"},")
        append("\"durationMs\":${durationMs ?: "null"},")
        append("\"phaseDurationsMs\":${longMap(phaseDurationsMs)},")
        append("\"succeeded\":$succeeded")
        append('}')
    }

    fun writeTo(directory: File, baseName: String = "morphe-patch-report"): Pair<File, File> {
        directory.mkdirs()
        val txt = File(directory, "$baseName.txt").apply { writeText(toText()) }
        val json = File(directory, "$baseName.json").apply { writeText(toJson()) }
        return txt to json
    }

    private fun array(values: List<String>) =
        values.joinToString(prefix = "[", postfix = "]") { "\"${json(it)}\"" }

    private fun longMap(values: Map<String, Long>) =
        values.toSortedMap().entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "\"${json(key)}\":$value"
        }

    private fun json(value: String) = buildString {
        value.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }
}
