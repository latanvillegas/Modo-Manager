package app.morphe.manager.patcher.util

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApkPreflightTest {
    @Test
    fun `valid minimal APK passes structural preflight`() {
        val apk = createApk(listOf("AndroidManifest.xml", "classes.dex", "lib/arm64-v8a/libsample.so"))
        try {
            val result = ApkPreflight.inspect(apk)
            assertTrue(result.canPatch)
            assertTrue("classes.dex" in result.dexEntries)
            assertTrue("arm64-v8a" in result.abis)
        } finally {
            apk.delete()
        }
    }

    @Test
    fun `missing manifest blocks patching`() {
        val apk = createApk(listOf("classes.dex"))
        try {
            val result = ApkPreflight.inspect(apk)
            assertFalse(result.canPatch)
            assertTrue(result.findings.any { it.code == "MANIFEST_MISSING" })
        } finally {
            apk.delete()
        }
    }

    @Test
    fun `non zip input blocks patching`() {
        val apk = File.createTempFile("morphe-preflight-", ".apk").apply { writeText("not an apk") }
        try {
            assertFalse(ApkPreflight.inspect(apk).canPatch)
        } finally {
            apk.delete()
        }
    }

    private fun createApk(entries: List<String>): File =
        File.createTempFile("morphe-preflight-", ".apk").also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                entries.forEach { name ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(byteArrayOf(1, 2, 3))
                    zip.closeEntry()
                }
            }
        }
}
