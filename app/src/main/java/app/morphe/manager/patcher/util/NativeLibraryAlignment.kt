package app.morphe.manager.patcher.util

import java.io.File
import java.io.RandomAccessFile
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Verifies direct-load native libraries in an APK. STORED .so entries must start on a 16 KiB
 * boundary so Android devices with 16 KiB pages can mmap them directly from the archive.
 */
object NativeLibraryAlignment {
    const val ALIGNMENT = 16 * 1024
    private const val LOCAL_FILE_HEADER_SIGNATURE = 0x04034b50
    private const val DATA_DESCRIPTOR_SIGNATURE = 0x08074b50

    data class MisalignedEntry(val name: String, val dataOffset: Long)

    fun misalignedStoredLibraries(apk: File): List<MisalignedEntry> =
        ZipFile(apk).use { zip ->
            val storedLibraries = zip.entries().asSequence()
                .filter { !it.isDirectory && it.method == ZipEntry.STORED }
                .map { it.name }
                .filter { it.startsWith("lib/") && it.endsWith(".so") }
                .toSet()
            if (storedLibraries.isEmpty()) return@use emptyList()

            RandomAccessFile(apk, "r").use { file ->
                val result = mutableListOf<MisalignedEntry>()
                val verifiedLibraries = mutableSetOf<String>()
                var offset = 0L
                while (offset + 30 <= file.length()) {
                    file.seek(offset)
                    if (readIntLe(file) != LOCAL_FILE_HEADER_SIGNATURE) break

                    file.skipBytes(2) // version needed
                    val flags = readShortLe(file)
                    file.skipBytes(2) // method
                    file.skipBytes(2 + 2 + 4 + 4 + 4) // time/date/crc/sizes
                    val nameLength = readShortLe(file)
                    val extraLength = readShortLe(file)
                    check(offset + 30L + nameLength + extraLength <= file.length()) {
                        "Malformed ZIP local header at offset $offset"
                    }

                    val nameBytes = ByteArray(nameLength)
                    file.readFully(nameBytes)
                    val name = nameBytes.toString(Charsets.UTF_8)
                    val dataOffset = offset + 30L + nameLength + extraLength
                    val compressedSize = zip.getEntry(name)?.compressedSize ?: break

                    if (name in storedLibraries) {
                        verifiedLibraries += name
                        if (dataOffset % ALIGNMENT != 0L) {
                            result += MisalignedEntry(name, dataOffset)
                        }
                    }

                    offset = dataOffset + compressedSize
                    if (flags and 0x08 != 0) {
                        if (offset + 12 > file.length()) break
                        file.seek(offset)
                        val possibleSignature = readIntLe(file)
                        offset += if (possibleSignature == DATA_DESCRIPTOR_SIGNATURE) 16 else 12
                    }
                }
                check(verifiedLibraries == storedLibraries) {
                    "Could not verify local offsets for all stored native libraries; " +
                        "verified=${verifiedLibraries.sorted()} expected=${storedLibraries.sorted()}"
                }
                result
            }
        }

    /** Rewrites the APK only when STORED native libraries need 16 KiB alignment. */
    fun alignStoredLibraries(apk: File): Boolean {
        if (misalignedStoredLibraries(apk).isEmpty()) return false

        val temp = File(apk.parentFile, "${apk.nameWithoutExtension}-aligned.apk")
        try {
            ZipFile(apk).use { zip ->
                val counting = CountingOutputStream(temp.outputStream().buffered())
                ZipOutputStream(counting).use { output ->
                    zip.entries().asSequence().forEach { entry ->
                        val copy = ZipEntry(entry.name).apply {
                            method = entry.method
                            time = entry.time
                            comment = entry.comment
                            size = entry.size
                            crc = entry.crc
                            if (entry.method == ZipEntry.STORED) compressedSize = entry.size
                        }

                        if (entry.method == ZipEntry.STORED &&
                            entry.name.startsWith("lib/") &&
                            entry.name.endsWith(".so")) {
                            val existingExtra = entry.extra ?: ByteArray(0)
                            val baseDataOffset = counting.count + 30L +
                                entry.name.toByteArray(Charsets.UTF_8).size + existingExtra.size
                            var padding = ((ALIGNMENT - (baseDataOffset % ALIGNMENT)) % ALIGNMENT).toInt()
                            // ZIP extra fields require a 4-byte header. If the exact remainder is
                            // 1..3 bytes, use the equivalent padding one alignment page later.
                            if (padding in 1..3) padding += ALIGNMENT
                            if (padding > 0) {
                                val payload = padding - 4
                                require(existingExtra.size + padding <= 0xffff) {
                                    "ZIP extra field would exceed 65535 bytes for ${entry.name}"
                                }
                                val alignmentExtra = ByteArray(padding).apply {
                                    this[0] = 0x35
                                    this[1] = 0xd9.toByte()
                                    this[2] = (payload and 0xff).toByte()
                                    this[3] = ((payload ushr 8) and 0xff).toByte()
                                }
                                copy.extra = existingExtra + alignmentExtra
                            } else {
                                copy.extra = existingExtra.takeIf { it.isNotEmpty() }
                            }
                        } else {
                            entry.extra?.let { copy.extra = it.copyOf() }
                        }

                        output.putNextEntry(copy)
                        if (!entry.isDirectory) {
                            zip.getInputStream(entry).use { input -> input.copyTo(output) }
                        }
                        output.closeEntry()
                    }
                }
            }

            requireAligned(temp)
            replaceTransactionally(apk, temp)
            return true
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }
    }

    private fun replaceTransactionally(apk: File, replacement: File) {
        val backup = File(apk.parentFile, "${apk.name}.alignment-backup")
        check(!backup.exists() || backup.delete()) { "Failed to remove stale alignment backup: ${backup.path}" }
        check(apk.renameTo(backup)) { "Failed to preserve APK before alignment rewrite" }
        try {
            if (!replacement.renameTo(apk)) replacement.copyTo(apk, overwrite = true)
            requireAligned(apk)
            check(backup.delete() || !backup.exists()) { "Failed to remove alignment backup" }
            if (replacement.exists()) check(replacement.delete()) { "Failed to remove alignment temporary APK" }
        } catch (error: Throwable) {
            apk.delete()
            if (!backup.renameTo(apk)) backup.copyTo(apk, overwrite = true)
            throw error
        }
    }

    fun requireAligned(apk: File) {
        val failures = misalignedStoredLibraries(apk)
        check(failures.isEmpty()) {
            "Stored native libraries are not 16 KiB aligned: " +
                failures.joinToString { "${it.name}@${it.dataOffset}" }
        }
    }

    private class CountingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        var count: Long = 0
            private set

        override fun write(value: Int) {
            out.write(value)
            count++
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            out.write(bytes, offset, length)
            count += length
        }
    }

    private fun readShortLe(file: RandomAccessFile): Int {
        val b0 = file.readUnsignedByte()
        val b1 = file.readUnsignedByte()
        return b0 or (b1 shl 8)
    }

    private fun readIntLe(file: RandomAccessFile): Int =
        readShortLe(file) or (readShortLe(file) shl 16)
}
