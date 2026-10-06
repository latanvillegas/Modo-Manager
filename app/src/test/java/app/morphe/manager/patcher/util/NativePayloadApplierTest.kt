package app.morphe.manager.patcher.util

import app.morphe.manager.patcher.logger.LogLevel
import app.morphe.manager.patcher.logger.Logger
import app.morphe.manager.patcher.patch.PatchBundle
import java.io.File
import kotlin.io.path.createTempDirectory
import java.io.FileOutputStream
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith

class NativePayloadApplierTest {
    @Test
    fun `conflicting selected targets fail before APK mutation`() = runBlocking {
        val first = bundle("one")
        val second = bundle("two")
        val apk = File.createTempFile("native-payload-target-", ".apk")
        val workspace = createTempDirectory("native-payload-workspace-").toFile()
        try {
            apk.writeBytes(byteArrayOf(1, 2, 3))
            assertFailsWith<IllegalArgumentException> {
                NativePayloadApplier.apply(
                    apkFile = apk,
                    selections = listOf(
                        NativePayloadApplier.Selection(first, setOf("Patch")),
                        NativePayloadApplier.Selection(second, setOf("Patch")),
                    ),
                    workspace = workspace,
                    logger = TestLogger,
                )
            }
        } finally {
            first.patchesJar.let(::File).delete()
            second.patchesJar.let(::File).delete()
            apk.delete()
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `payload ABI must match explicitly selected ABI`() {
        val arm64 = bundle("arm64")
        try {
            assertFailsWith<IllegalArgumentException> {
                NativePayloadApplier.resolve(
                    selections = listOf(NativePayloadApplier.Selection(arm64, setOf("Patch"))),
                    selectedAbi = "x86_64",
                )
            }
        } finally {
            File(arm64.patchesJar).delete()
        }
    }

    private fun bundle(id: String): PatchBundle {
        val file = File.createTempFile("native-payload-$id-", ".mpp")
        val properties = """
            schemaVersion=1
            capabilities=NATIVE
            payload.$id.patchName=Patch
            payload.$id.apkEntry=lib/arm64-v8a/libdemo.so
            payload.$id.entry=payload/native/arm64-v8a/$id.so
            payload.$id.originalSha256=0000000000000000000000000000000000000000000000000000000000000000
            payload.$id.replacementSha256=1111111111111111111111111111111111111111111111111111111111111111
        """.trimIndent().toByteArray()

        JarOutputStream(FileOutputStream(file)).use { jar ->
            jar.putNextEntry(JarEntry("META-INF/morphe/native-payloads.properties"))
            jar.write(properties)
            jar.closeEntry()
            jar.putNextEntry(JarEntry("payload/native/arm64-v8a/$id.so"))
            jar.write(byteArrayOf(9))
            jar.closeEntry()
        }
        return PatchBundle(file.absolutePath)
    }

    private object TestLogger : Logger() {
        override fun log(level: LogLevel, message: String) = Unit
    }
}
