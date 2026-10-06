package app.morphe.manager.patcher.util

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

class FileHashTest {
    @Test
    fun `sha256 hashes file bytes deterministically`() {
        val file = File.createTempFile("morphe-hash-", ".bin")
        try {
            val bytes = byteArrayOf(0, 1, 2, 3, 4, 5)
            file.writeBytes(bytes)
            val expected = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            assertEquals(expected, FileHash.sha256(file))
        } finally {
            file.delete()
        }
    }
}
