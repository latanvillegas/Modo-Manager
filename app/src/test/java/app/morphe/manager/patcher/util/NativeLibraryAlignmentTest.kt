package app.morphe.manager.patcher.util

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NativeLibraryAlignmentTest {
    @Test
    fun `detects misaligned stored native library`() {
        val apk = File.createTempFile("morphe-alignment-", ".apk")
        try {
            val bytes = byteArrayOf(1, 2, 3, 4)
            val crc = CRC32().apply { update(bytes) }
            ZipOutputStream(apk.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("lib/arm64-v8a/libsample.so").apply {
                    method = ZipEntry.STORED
                    size = bytes.size.toLong()
                    compressedSize = bytes.size.toLong()
                    this.crc = crc.value
                })
                zip.write(bytes)
                zip.closeEntry()
            }

            val failures = NativeLibraryAlignment.misalignedStoredLibraries(apk)
            assertEquals(1, failures.size)
            assertEquals("lib/arm64-v8a/libsample.so", failures.single().name)
            assertTrue(failures.single().dataOffset % NativeLibraryAlignment.ALIGNMENT != 0L)
        } finally {
            apk.delete()
        }
    }
}
