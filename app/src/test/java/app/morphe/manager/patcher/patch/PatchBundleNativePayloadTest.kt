package app.morphe.manager.patcher.patch

import java.io.File
import java.security.MessageDigest
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertFalse
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
            payload.demo.patchName=Demo Patch
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
            payload.demo.patchName=Demo Patch
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

    @Test
    fun `unknown capability is rejected`() {
        val manifest = """
            capabilities=DEX,NATIVE,FUTURE
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

    @Test
    fun `native payload requires patch binding`() {
        val manifest = """
            capabilities=NATIVE
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

    @Test
    fun `selection returns only payload bound to selected patch`() {
        val payload = byteArrayOf(4, 3, 2, 1)
        val hash = sha256(payload)
        val manifest = """
            capabilities=NATIVE
            payload.demo.apkEntry=lib/testabi/libdemo.so
            payload.demo.entry=payload/native/testabi/libdemo.so
            payload.demo.patchName=Demo Patch
            payload.demo.originalSha256=0000000000000000000000000000000000000000000000000000000000000000
            payload.demo.replacementSha256=$hash
        """.trimIndent().toByteArray()
        val jar = bundle(mapOf(
            "META-INF/morphe/native-payloads.properties" to manifest,
            "payload/native/testabi/libdemo.so" to payload,
        ))
        try {
            val patchBundle = PatchBundle(jar.absolutePath)
            assertEquals(listOf("demo"), patchBundle.nativePayloadsFor(setOf("Demo Patch")).map { it.id })
            assertTrue(patchBundle.nativePayloadsFor(setOf("Other Patch")).isEmpty())
        } finally {
            jar.delete()
        }
    }

    @Test
    fun `failed payload hash removes extracted file`() {
        val payload = byteArrayOf(9, 9, 9)
        val manifest = """
            capabilities=NATIVE
            payload.demo.apkEntry=lib/testabi/libdemo.so
            payload.demo.entry=payload/native/testabi/libdemo.so
            payload.demo.patchName=Demo Patch
            payload.demo.originalSha256=0000000000000000000000000000000000000000000000000000000000000000
            payload.demo.replacementSha256=1111111111111111111111111111111111111111111111111111111111111111
        """.trimIndent().toByteArray()
        val jar = bundle(mapOf(
            "META-INF/morphe/native-payloads.properties" to manifest,
            "payload/native/testabi/libdemo.so" to payload,
        ))
        val out = createTempDir(prefix = "morphe-native-payload-bad-")
        try {
            val patchBundle = PatchBundle(jar.absolutePath)
            val parsed = patchBundle.nativePayloadManifest()!!.payloads.single()
            assertFailsWith<IllegalStateException> { patchBundle.extractNativePayload(parsed, out) }
            assertFalse(File(out, "demo.so").exists())
        } finally {
            jar.delete()
            out.deleteRecursively()
        }
    }


    @Test
    fun `future native manifest schema is rejected`() {
        val jar = bundle(mapOf(
            "META-INF/morphe/native-payloads.properties" to """
                schemaVersion=2
                capabilities=NATIVE
            """.trimIndent().toByteArray()
        ))
        try {
            assertFailsWith<IllegalArgumentException> {
                PatchBundle(jar.absolutePath).nativePayloadManifest()
            }
        } finally {
            jar.delete()
        }
    }

    @Test
    fun `unknown native manifest field is rejected`() {
        val jar = bundle(mapOf(
            "META-INF/morphe/native-payloads.properties" to """
                schemaVersion=1
                capabilities=NATIVE
                payload.demo.futureField=value
            """.trimIndent().toByteArray()
        ))
        try {
            assertFailsWith<IllegalArgumentException> {
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
