package app.morphe.manager.patcher.util

import java.io.File

/** Conservative workspace estimate for patching without running out of app-private storage. */
object PatchStoragePreflight {
    private const val APK_MULTIPLIER = 5L
    private const val FIXED_HEADROOM = 64L * 1024L * 1024L

    fun requiredBytes(inputSize: Long): Long {
        require(inputSize >= 0) { "Input size must not be negative" }
        return runCatching {
            Math.addExact(Math.multiplyExact(inputSize, APK_MULTIPLIER), FIXED_HEADROOM)
        }.getOrElse { Long.MAX_VALUE }
    }

    fun requireEnoughSpace(inputFile: File, availableBytes: Long?) {
        if (availableBytes == null || availableBytes < 0) return
        val required = requiredBytes(inputFile.length())
        check(availableBytes >= required) {
            "Not enough storage for safe patching: available=$availableBytes required=$required " +
                "input=${inputFile.length()}"
        }
    }
}
