package app.morphe.manager.patcher.util

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

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

                    if (name in storedLibraries && dataOffset % ALIGNMENT != 0L) {
                        result += MisalignedEntry(name, dataOffset)
                    }

                    offset = dataOffset + compressedSize
                    if (flags and 0x08 != 0) {
                        if (offset + 12 > file.length()) break
                        file.seek(offset)
                        val possibleSignature = readIntLe(file)
                        offset += if (possibleSignature == DATA_DESCRIPTOR_SIGNATURE) 16 else 12
                    }
                }
                result
            }
        }

    fun requireAligned(apk: File) {
        val failures = misalignedStoredLibraries(apk)
        check(failures.isEmpty()) {
            "Stored native libraries are not 16 KiB aligned: " +
                failures.joinToString { "${it.name}@${it.dataOffset}" }
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
