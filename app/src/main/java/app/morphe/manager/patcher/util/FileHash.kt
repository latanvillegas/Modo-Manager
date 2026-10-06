package app.morphe.manager.patcher.util

import java.io.File
import java.security.MessageDigest

/** Streaming hashes for diagnostics and bundle provenance without loading large files in memory. */
object FileHash {
    fun sha256(file: File): String {
        require(file.isFile) { "File does not exist: ${file.absolutePath}" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
