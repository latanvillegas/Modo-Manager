package app.morphe.manager.patcher.util

import android.os.Build
import android.util.Log
import app.morphe.manager.patcher.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.CRC32

object NativeLibStripper {
    private const val TAG = "Morphe NativeLibStripper"

    data class NativeReplacement(
        val apkEntry: String,
        val payload: File,
        val expectedOriginalSha256: String,
        val expectedReplacementSha256: String,
    )

    suspend fun strip(apkFile: File, logger: Logger? = null): Boolean =
        strip(apkFile, Build.SUPPORTED_ABIS.filter { it.isNotBlank() }, logger)

    suspend fun strip(apkFile: File, supportedAbis: List<String>, logger: Logger? = null): Boolean =
        withContext(Dispatchers.IO) {
            if (supportedAbis.isEmpty()) return@withContext false

            val preferredAbi = determinePreferredAbi(apkFile, supportedAbis)
            val allowedAbis = preferredAbi?.let { setOf(it) } ?: supportedAbis.toSet()

            if (preferredAbi != null) {
                Log.i(TAG, "Preserving native libraries for ABI $preferredAbi")
            }

            val tempFile = File(apkFile.parentFile, "${apkFile.nameWithoutExtension}-abi-stripped.apk")
            var removedEntries = 0

            ZipInputStream(apkFile.inputStream().buffered()).use { zis ->
                ZipOutputStream(tempFile.outputStream().buffered()).use { zos ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        val keepEntry = shouldKeepZipEntry(name, allowedAbis)

                        if (keepEntry) {
                            val newEntry = cloneEntry(entry)
                            zos.putNextEntry(newEntry)
                            if (!entry.isDirectory) {
                                while (true) {
                                    val read = zis.read(buffer)
                                    if (read == -1) break
                                    zos.write(buffer, 0, read)
                                }
                            }
                            zos.closeEntry()
                        } else if (!entry.isDirectory) {
                            removedEntries++
                        }

                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }

            if (removedEntries > 0) {
                replaceAtomically(apkFile, tempFile)
                val message = "Stripped native libraries for unsupported ABIs (removed $removedEntries entries)"
                Log.i(TAG, message)
                logger?.info(message)
                true
            } else {
                tempFile.delete()
                false
            }
        }

    /**
     * Replaces one native APK entry only when both the original and replacement payloads match
     * their declared SHA-256 values. The APK is left untouched if any preflight check fails.
     */
    suspend fun replaceNativeLibrary(
        apkFile: File,
        replacement: NativeReplacement,
        logger: Logger? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        require(replacement.apkEntry.startsWith("lib/") && replacement.apkEntry.endsWith(".so")) {
            "Native replacement path must be lib/<abi>/<name>.so: ${replacement.apkEntry}"
        }
        require(replacement.payload.isFile) {
            "Native replacement payload does not exist: ${replacement.payload}"
        }

        val replacementHash = sha256(replacement.payload.inputStream())
        require(replacementHash.equals(replacement.expectedReplacementSha256, ignoreCase = true)) {
            "Replacement SHA-256 mismatch for ${replacement.apkEntry}: expected " +
                "${replacement.expectedReplacementSha256}, got $replacementHash"
        }

        val originalHash = ZipFile(apkFile).use { zip ->
            val entry = zip.getEntry(replacement.apkEntry)
                ?: error("APK does not contain native library ${replacement.apkEntry}")
            zip.getInputStream(entry).use(::sha256)
        }
        require(originalHash.equals(replacement.expectedOriginalSha256, ignoreCase = true)) {
            "Original SHA-256 mismatch for ${replacement.apkEntry}: expected " +
                "${replacement.expectedOriginalSha256}, got $originalHash"
        }

        val tempFile = File(apkFile.parentFile, "${apkFile.nameWithoutExtension}-native-replaced.apk")
        var replaced = false
        try {
            ZipInputStream(apkFile.inputStream().buffered()).use { zis ->
                ZipOutputStream(tempFile.outputStream().buffered()).use { zos ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val replacingEntry = entry.name == replacement.apkEntry
                        val newEntry = if (replacingEntry && entry.method == ZipEntry.STORED) {
                            storedReplacementEntry(entry, replacement.payload)
                        } else {
                            cloneEntry(entry, preserveStoredMetadata = !replacingEntry)
                        }
                        zos.putNextEntry(newEntry)
                        if (!entry.isDirectory) {
                            if (replacingEntry) {
                                replacement.payload.inputStream().buffered().use { input ->
                                    input.copyTo(zos)
                                }
                                replaced = true
                            } else {
                                while (true) {
                                    val read = zis.read(buffer)
                                    if (read == -1) break
                                    zos.write(buffer, 0, read)
                                }
                            }
                        }
                        zos.closeEntry()
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }

            check(replaced) { "Native library disappeared while rewriting APK: ${replacement.apkEntry}" }

            val writtenHash = ZipFile(tempFile).use { zip ->
                val entry = zip.getEntry(replacement.apkEntry)
                    ?: error("Rewritten APK is missing ${replacement.apkEntry}")
                zip.getInputStream(entry).use(::sha256)
            }
            check(writtenHash.equals(replacement.expectedReplacementSha256, ignoreCase = true)) {
                "Post-write SHA-256 mismatch for ${replacement.apkEntry}: expected " +
                    "${replacement.expectedReplacementSha256}, got $writtenHash"
            }

            replaceAtomically(apkFile, tempFile)
            val message = "Replaced native library ${replacement.apkEntry} " +
                "(original=$originalHash replacement=$writtenHash)"
            Log.i(TAG, message)
            logger?.info(message)
            true
        } catch (error: Throwable) {
            tempFile.delete()
            throw error
        }
    }

    private fun replaceAtomically(apkFile: File, tempFile: File) {
        val backupFile = File(apkFile.parentFile, "${apkFile.name}.native-backup")
        backupFile.delete()
        check(apkFile.renameTo(backupFile)) { "Failed to preserve original APK before rewrite" }
        try {
            if (!tempFile.renameTo(apkFile)) {
                tempFile.copyTo(apkFile, overwrite = true)
                tempFile.delete()
            }
            backupFile.delete()
        } catch (error: Throwable) {
            apkFile.delete()
            backupFile.renameTo(apkFile)
            throw error
        }
    }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        input.use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun shouldKeepZipEntry(name: String, allowedAbis: Set<String>): Boolean {
        val abi = extractAbiFromEntry(name) ?: return true
        return abi in allowedAbis
    }

    /**
     * A native library stored uncompressed must stay STORED after replacement. Android can map
     * such entries directly from the APK; changing them to DEFLATED changes installation/runtime
     * semantics. ZipOutputStream requires the replacement size and CRC before putNextEntry().
     */
    private fun storedReplacementEntry(original: ZipEntry, payload: File): ZipEntry {
        val clone = cloneEntry(original, preserveStoredMetadata = false)
        val crc = CRC32()
        payload.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                crc.update(buffer, 0, read)
            }
        }
        clone.method = ZipEntry.STORED
        clone.size = payload.length()
        clone.compressedSize = payload.length()
        clone.crc = crc.value
        return clone
    }

    private fun cloneEntry(entry: ZipEntry, preserveStoredMetadata: Boolean = true): ZipEntry {
        val clone = ZipEntry(entry.name)
        clone.time = entry.time
        clone.comment = entry.comment
        entry.extra?.let { clone.extra = it.copyOf() }
        try {
            entry.creationTime?.let { clone.creationTime = it }
            entry.lastAccessTime?.let { clone.lastAccessTime = it }
            entry.lastModifiedTime?.let { clone.lastModifiedTime = it }
        } catch (_: Exception) {
            // Ignore metadata failures.
        }

        when (entry.method) {
            ZipEntry.STORED -> {
                if (preserveStoredMetadata) {
                    clone.method = ZipEntry.STORED
                    if (entry.size >= 0) clone.size = entry.size
                    if (entry.compressedSize >= 0) clone.compressedSize = entry.compressedSize
                    clone.crc = entry.crc
                } else {
                    // Replacement bytes have different size/CRC, so let ZipOutputStream recompute.
                    clone.method = ZipEntry.DEFLATED
                }
            }

            ZipEntry.DEFLATED -> clone.method = ZipEntry.DEFLATED
            else -> if (entry.method != -1) clone.method = entry.method
        }

        return clone
    }

    private fun extractAbiFromEntry(name: String): String? {
        if (!name.startsWith("lib/")) return null
        val secondSlash = name.indexOf('/', startIndex = 4)
        if (secondSlash == -1) return null
        return name.substring(4, secondSlash)
    }

    fun extractAbisFromApk(apkFile: File): List<String> =
        runCatching {
            ZipFile(apkFile).use { zip ->
                zip.entries().asSequence()
                    .map { it.name }
                    .mapNotNull(::extractAbiFromEntry)
                    .distinct()
                    .toList()
            }
        }.getOrDefault(emptyList())

    fun extractAbisFromStream(stream: InputStream): List<String> {
        val abis = LinkedHashSet<String>()
        var insideLibs = false

        runCatching {
            ZipInputStream(stream.buffered()).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val abi = extractAbiFromEntry(entry.name)
                    if (abi == null) {
                        if (insideLibs) break
                    } else {
                        insideLibs = true
                        abis += abi
                    }
                }
            }
        }

        return abis.toList()
    }

    fun preferredAbi(abisInApk: Set<String>, supportedAbis: List<String>): String? =
        supportedAbis.firstOrNull { it in abisInApk }

    private fun determinePreferredAbi(apkFile: File, supportedAbis: List<String>): String? =
        runCatching {
            ZipFile(apkFile).use { zip ->
                val abisInApk = zip.entries().asSequence()
                    .map { it.name }
                    .mapNotNull(::extractAbiFromEntry)
                    .toSet()

                preferredAbi(abisInApk, supportedAbis)
            }
        }.getOrNull()
}
