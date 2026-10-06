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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun `native replacement removes transaction files after success`() = runBlocking {
        val dir = kotlin.io.path.createTempDirectory("morphe-native-transaction-").toFile()
        val apk = File(dir, "input.apk")
        val payload = File(dir, "payload.so")
        val original = byteArrayOf(1, 2, 3, 4)
        val replacement = byteArrayOf(5, 6, 7, 8)
        payload.writeBytes(replacement)

        try {
            writeNativeApk(apk, original)

            NativeLibStripper.replaceNativeLibrary(
                apk,
                NativeLibStripper.NativeReplacement(
                    apkEntry = "lib/arm64-v8a/libsample.so",
                    payload = payload,
                    expectedOriginalSha256 = sha256(original),
                    expectedReplacementSha256 = sha256(replacement),
                )
            )

            assertFalse(File(dir, "input.apk.native-backup").exists())
            assertFalse(File(dir, "input-native-replaced.apk").exists())
            assertTrue(apk.isFile && apk.length() > 0L)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `stale undeletable backup fails closed without touching original apk`() = runBlocking {
        val dir = kotlin.io.path.createTempDirectory("morphe-native-stale-backup-").toFile()
        val apk = File(dir, "input.apk")
        val payload = File(dir, "payload.so")
        val original = byteArrayOf(1, 2, 3, 4)
        val replacement = byteArrayOf(5, 6, 7, 8)
        payload.writeBytes(replacement)

        try {
            writeNativeApk(apk, original)
            val originalApkBytes = apk.readBytes()
            val staleBackup = File(dir, "input.apk.native-backup").apply {
                mkdirs()
                resolve("keep").writeText("occupied")
            }

            val error = assertFailsWith<IllegalStateException> {
                NativeLibStripper.replaceNativeLibrary(
                    apk,
                    NativeLibStripper.NativeReplacement(
                        apkEntry = "lib/arm64-v8a/libsample.so",
                        payload = payload,
                        expectedOriginalSha256 = sha256(original),
                        expectedReplacementSha256 = sha256(replacement),
                    )
                )
            }

            assertTrue(error.message.orEmpty().contains("stale native rewrite backup"))
            assertContentEquals(originalApkBytes, apk.readBytes())
            assertTrue(staleBackup.isDirectory)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun writeNativeApk(apk: File, bytes: ByteArray) {
        ZipOutputStream(apk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("lib/arm64-v8a/libsample.so"))
            zip.write(bytes)
            zip.closeEntry()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
