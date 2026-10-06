package app.morphe.manager.patcher.util

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PatchStoragePreflightTest {
    @Test
    fun `requires five APK copies plus fixed headroom`() {
        val mib = 1024L * 1024L
        assertEquals(564L * mib, PatchStoragePreflight.requiredBytes(100L * mib))
    }

    @Test
    fun `rejects insufficient measured storage`() {
        val input = File.createTempFile("morphe-storage-", ".apk")
        try {
            input.writeBytes(ByteArray(1024))
            assertFailsWith<IllegalStateException> {
                PatchStoragePreflight.requireEnoughSpace(input, 1024)
            }
        } finally {
            input.delete()
        }
    }

    @Test
    fun `skips rejection when storage cannot be measured`() {
        val input = File.createTempFile("morphe-storage-", ".apk")
        try {
            PatchStoragePreflight.requireEnoughSpace(input, null)
        } finally {
            input.delete()
        }
    }
}
