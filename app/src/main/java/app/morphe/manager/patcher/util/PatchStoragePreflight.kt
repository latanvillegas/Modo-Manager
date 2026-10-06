package app.morphe.manager.patcher.util

import java.io.File

/** Workspace estimate for patching without running out of app-private storage. */
object PatchStoragePreflight {
    private const val BASE_APK_COPIES = 3L // patched + signed pending + rollback/output headroom
    private const val SPLIT_EXTRA_COPIES = 2L // merge staging and mono APK
    private const val PREPARED_INPUT_COPIES = 1L
    private const val FIXED_HEADROOM = 64L * 1024L * 1024L

    data class Workload(
        val splitArchive: Boolean = false,
        val preparedInput: Boolean = false,
        val nativePayloadBytes: Long = 0L,
    )

    fun requiredBytes(inputSize: Long, workload: Workload = Workload()): Long {
        require(inputSize >= 0) { "Input size must not be negative" }
        require(workload.nativePayloadBytes >= 0) { "Native payload size must not be negative" }
        val copies = BASE_APK_COPIES +
            (if (workload.splitArchive) SPLIT_EXTRA_COPIES else 0L) +
            (if (workload.preparedInput) PREPARED_INPUT_COPIES else 0L)
        return runCatching {
            val apkBytes = Math.multiplyExact(inputSize, copies)
            // Extraction plus ZIP rewrite can temporarily retain both payload bytes.
            val payloadBytes = Math.multiplyExact(workload.nativePayloadBytes, 2L)
            Math.addExact(Math.addExact(apkBytes, payloadBytes), FIXED_HEADROOM)
        }.getOrElse { Long.MAX_VALUE }
    }

    fun requireEnoughSpace(
        inputFile: File,
        availableBytes: Long?,
        workload: Workload = Workload(),
    ) {
        if (availableBytes == null || availableBytes < 0) return
        val required = requiredBytes(inputFile.length(), workload)
        check(availableBytes >= required) {
            "Not enough storage for safe patching: available=$availableBytes required=$required " +
                "input=${inputFile.length()}"
        }
    }
}
