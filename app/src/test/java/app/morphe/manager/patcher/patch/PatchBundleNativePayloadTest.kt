package app.morphe.manager.patcher.patch

import java.io.File
import java.security.MessageDigest
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PatchBundleNativePayloadTest {
    @Test
    fun `legacy bundle has no native manifest`() {
        val jar = bundle(emptyMap())
        try {
            assertNull(PatchBundle(jar.absolutePath).nativePayloadManifest())
        } finally {
            jar.delete()
        }
    }

    @Test
    fun `native manifest parses and payload hash is verified`() {
        val payload = byteArrayOf(1, 3, 3, 7)
        val hash = sha256(payload)
        val manifest = """
            capabilities=DEX,RESOURCE,NATIVE
            payload.demo.apkEntry=lib/testabi/libdemo.so
            payload.demo.entry=payload/native/testabi/libdemo.so
            payload.demo.originalSha256=0000000000000000000000000000000000000000000000000000000000000000
            payload.demo.replacementSha256=$hash
        """.trimIndent().toByteArray()
        val jar = bundle(mapOf(
            "META-INF/morphe/native-payloads.properties" to manifest,
            "payload/native/testabi/libdemo.so" to payload,
        ))
        val out = createTempDir(prefix = "morphe-native-payload-")
        try {
            val patchBundle = PatchBundle(jar.absolutePath)
            val parsed = patchBundle.nativePayloadManifest()!!
            assertTrue(PatchBundle.Capability.NATIVE in parsed.capabilities)
            assertEquals(1, parsed.payloads.size)
            val extracted = patchBundle.extractNativePayload(parsed.payloads.single(), out)
            assertTrue(extracted.isFile)
            assertEquals(hash, sha256(extracted.readBytes()))
        } finally {
            jar.delete()
            out.deleteRecursively()
        }
    }

    @Test
    fun `native payload without native capability is rejected`() {
        val manifest = """
            capabilities=DEX
            payload.demo.apkEntry=lib/testabi/libdemo.so
            payload.demo.entry=payload/native/testabi/libdemo.so
            payload.demo.originalSha256=0000000000000000000000000000000000000000000000000000000000000000
            payload.demo.replacementSha256=1111111111111111111111111111111111111111111111111111111111111111
        """.trimIndent().toByteArray()
        val jar = bundle(mapOf("META-INF/morphe/native-payloads.properties" to manifest))
        try {
            assertFailsWith<IllegalStateException> {
                PatchBundle(jar.absolutePath).nativePayloadManifest()
            }
        } finally {
            jar.delete()
        }
    }

    private fun bundle(entries: Map<String, ByteArray>): File =
        File.createTempFile("morphe-bundle-", ".jar").also { file ->
            JarOutputStream(file.outputStream()).use { jar ->
                entries.forEach { (name, bytes) ->
                    jar.putNextEntry(JarEntry(name))
                    jar.write(bytes)
                    jar.closeEntry()
                }
            }
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
