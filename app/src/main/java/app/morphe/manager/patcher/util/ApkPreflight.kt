package app.morphe.manager.patcher.util

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * APK-level checks that are independent from any app or patch bundle.
 * This deliberately does not know package names such as Cromite.
 */
object ApkPreflight {
    enum class Severity { INFO, WARNING, ERROR }

    data class Finding(val severity: Severity, val code: String, val message: String)

    data class Result(
        val file: File,
        val sha256: String,
        val size: Long,
        val dexEntries: List<String>,
        val nativeEntries: List<String>,
        val abis: List<String>,
        val findings: List<Finding>,
    ) {
        val canPatch: Boolean get() = findings.none { it.severity == Severity.ERROR }
    }

    fun inspect(apk: File): Result {
        val findings = mutableListOf<Finding>()
        if (!apk.isFile) {
            return Result(apk, "", 0, emptyList(), emptyList(), emptyList(),
                listOf(Finding(Severity.ERROR, "APK_NOT_FOUND", "Input APK does not exist")))
        }

        val hash = sha256(apk)
        val dex = mutableListOf<String>()
        val native = mutableListOf<String>()
        val abis = linkedSetOf<String>()

        try {
            ZipFile(apk).use { zip ->
                val names = zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()
                dex += names.filter { it.matches(Regex("""classes(?:\d+)?\.dex""")) }
                native += names.filter { it.startsWith("lib/") && it.endsWith(".so") }
                native.forEach { name ->
                    name.removePrefix("lib/").substringBefore('/').takeIf { it.isNotBlank() }?.let(abis::add)
                }
                if ("AndroidManifest.xml" !in names) {
                    findings += Finding(Severity.ERROR, "MANIFEST_MISSING", "AndroidManifest.xml is missing")
                }
                if (dex.isEmpty()) {
                    findings += Finding(Severity.WARNING, "DEX_NONE", "APK contains no classes*.dex entries")
                }
                if (native.isEmpty()) {
                    findings += Finding(Severity.INFO, "NATIVE_NONE", "APK contains no native libraries")
                }
                val duplicateNames = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
                if (duplicateNames.isNotEmpty()) {
                    findings += Finding(Severity.ERROR, "ZIP_DUPLICATES",
                        "APK contains duplicate ZIP entries: " + duplicateNames.sorted().joinToString())
                }
            }
        } catch (e: ZipException) {
            findings += Finding(Severity.ERROR, "ZIP_INVALID", "Input is not a valid APK/ZIP: ${e.message}")
        } catch (e: Exception) {
            findings += Finding(Severity.ERROR, "APK_READ_FAILED", "Failed to inspect APK: ${e.message}")
        }

        return Result(apk, hash, apk.length(), dex.sorted(), native.sorted(), abis.sorted(), findings)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
