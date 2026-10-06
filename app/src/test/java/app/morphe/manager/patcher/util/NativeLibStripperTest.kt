package app.morphe.manager.patcher.util

import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class NativeLibStripperTest {
    @Test
    fun `native replacement preserves STORED method and replacement metadata`() = runBlocking {
        val apk = File.createTempFile("morphe-native-", ".apk")
        val payload = File.createTempFile("morphe-native-payload-", ".so")
        val original = byteArrayOf(1, 2, 3, 4)
        val replacement = byteArrayOf(9, 8, 7, 6, 5)
        payload.writeBytes(replacement)

        try {
            ZipOutputStream(apk.outputStream()).use { zip ->
                val crc = CRC32().apply { update(original) }
                zip.putNextEntry(ZipEntry("lib/arm64-v8a/libsample.so").apply {
                    method = ZipEntry.STORED
                    size = original.size.toLong()
                    compressedSize = original.size.toLong()
                    this.crc = crc.value
                })
                zip.write(original)
                zip.closeEntry()
            }

            NativeLibStripper.replaceNativeLibrary(
                apk,
                NativeLibStripper.NativeReplacement(
                    apkEntry = "lib/arm64-v8a/libsample.so",
                    payload = payload,
                    expectedOriginalSha256 = sha256(original),
                    expectedReplacementSha256 = sha256(replacement),
                )
            )

            ZipFile(apk).use { zip ->
                val entry = zip.getEntry("lib/arm64-v8a/libsample.so")
                assertEquals(ZipEntry.STORED, entry.method)
                assertEquals(replacement.size.toLong(), entry.size)
                assertEquals(replacement.size.toLong(), entry.compressedSize)
                assertContentEquals(replacement, zip.getInputStream(entry).readBytes())
            }
        } finally {
            apk.delete()
            payload.delete()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
