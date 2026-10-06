package app.morphe.manager.network.service

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ParallelDownloadTargetTest {
    @Test
    fun `opening parallel download target removes bytes from previous file`() {
        val dir = Files.createTempDirectory("parallel-download-target-test").toFile()
        val target = dir.resolve("asset.apk")
        try {
            target.writeBytes(ByteArray(4096) { 0x5a })

            openParallelDownloadTarget(target).use { channel ->
                assertEquals(0L, channel.size())
            }

            assertEquals(0L, target.length())
        } finally {
            dir.deleteRecursively()
        }
    }
}
