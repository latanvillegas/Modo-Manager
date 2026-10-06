package app.morphe.manager.patcher.util

import kotlin.test.Test
import kotlin.test.assertContains

class PatchRunReportTest {
    @Test
    fun `JSON emits present input hash as string`() {
        val json = report("abc123").toJson()
        assertContains(json, "\"inputSha256\":\"abc123\"")
    }

    @Test
    fun `JSON emits unavailable input hash as null`() {
        val json = report(null).toJson()
        assertContains(json, "\"inputSha256\":null")
    }

    private fun report(inputSha256: String?) = PatchRunReport(
        packageName = "app.example",
        version = "1",
        inputSha256 = inputSha256,
        outputSha256 = "def456",
        inputSize = 1,
        outputSize = 2,
        abis = listOf("arm64-v8a"),
        selectedPatches = listOf("Example"),
        changes = listOf("APK signed and structurally verified"),
        warnings = emptyList(),
        succeeded = true,
    )
}
