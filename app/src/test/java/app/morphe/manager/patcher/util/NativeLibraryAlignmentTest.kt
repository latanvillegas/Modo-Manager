package app.morphe.manager.patcher.util

import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
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
    @Test
    fun `realigns stored native library with inherited extra metadata`() {
        val apk = File.createTempFile("morphe-realignment-extra-", ".apk")
        val bytes = ByteArray(8193) { (it and 0xff).toByte() }
        try {
            val crc = CRC32().apply { update(bytes) }
            ZipOutputStream(apk.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("assets/prefix").apply { method = ZipEntry.DEFLATED })
                zip.write(ByteArray(211) { 3 })
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("lib/arm64-v8a/libsample.so").apply {
                    method = ZipEntry.STORED
                    size = bytes.size.toLong()
                    compressedSize = bytes.size.toLong()
                    this.crc = crc.value
                    extra = byteArrayOf(0x34, 0x12, 0x05, 0x00, 1, 2, 3, 4, 5)
                })
                zip.write(bytes)
                zip.closeEntry()
            }

            assertTrue(NativeLibraryAlignment.misalignedStoredLibraries(apk).isNotEmpty())
            assertTrue(NativeLibraryAlignment.alignStoredLibraries(apk))
            NativeLibraryAlignment.requireAligned(apk)

            ZipFile(apk).use { zip ->
                val entry = zip.getEntry("lib/arm64-v8a/libsample.so")
                assertEquals(ZipEntry.STORED, entry.method)
                assertContentEquals(bytes, zip.getInputStream(entry).readBytes())
            }
        } finally {
            apk.delete()
        }
    }

    @Test
    fun `realigns stored native library without changing payload`() {
        val apk = File.createTempFile("morphe-realignment-", ".apk")
        val bytes = ByteArray(4097) { (it and 0xff).toByte() }
        try {
            val crc = CRC32().apply { update(bytes) }
            ZipOutputStream(apk.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("assets/prefix").apply { method = ZipEntry.DEFLATED })
                zip.write(ByteArray(137) { 7 })
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("lib/arm64-v8a/libsample.so").apply {
                    method = ZipEntry.STORED
                    size = bytes.size.toLong()
                    compressedSize = bytes.size.toLong()
                    this.crc = crc.value
                })
                zip.write(bytes)
                zip.closeEntry()
            }

            assertTrue(NativeLibraryAlignment.misalignedStoredLibraries(apk).isNotEmpty())
            assertTrue(NativeLibraryAlignment.alignStoredLibraries(apk))
            NativeLibraryAlignment.requireAligned(apk)

            ZipFile(apk).use { zip ->
                val entry = zip.getEntry("lib/arm64-v8a/libsample.so")
                assertEquals(ZipEntry.STORED, entry.method)
                assertContentEquals(bytes, zip.getInputStream(entry).readBytes())
            }
        } finally {
            apk.delete()
        }
    }

}
