package app.morphe.manager.patcher.util

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

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

    @Test
    fun `JSON emits reproducibility metadata`() {
        val json = report("abc123").copy(
            managerVersion = "2.0",
            patcherVersion = "1.14.1",
            selectedAbi = "arm64-v8a",
            bundleSources = listOf("uid=7 name=demo version=1 sha256=abcd"),
            nativePayloads = listOf("web:lib/arm64-v8a/libweb.so"),
            signingCertificateSha256 = listOf("cafe"),
            durationMs = 1234,
        ).toJson()
        assertContains(json, "\"managerVersion\":\"2.0\"")
        assertContains(json, "\"selectedAbi\":\"arm64-v8a\"")
        assertContains(json, "\"nativePayloads\":[\"web:lib/arm64-v8a/libweb.so\"]")
        assertContains(json, "\"durationMs\":1234")
    }

    @Test
    fun `phase durations serialize in deterministic order`() {
        val report = report("abc123").copy(
            phaseDurationsMs = linkedMapOf(
                "verification_signing_commit" to 30L,
                "preparation" to 10L,
                "patching" to 20L,
            )
        )

        val text = report.toText()
        val json = report.toJson()
        assertTrue(text.indexOf("phase_ms.patching=20") < text.indexOf("phase_ms.preparation=10"))
        assertTrue(
            text.indexOf("phase_ms.preparation=10") <
                text.indexOf("phase_ms.verification_signing_commit=30")
        )
        assertContains(
            json,
            "\"phaseDurationsMs\":{\"patching\":20, \"preparation\":10, " +
                "\"verification_signing_commit\":30}"
        )
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
