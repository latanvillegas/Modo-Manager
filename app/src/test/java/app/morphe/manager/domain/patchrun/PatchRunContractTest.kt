package app.morphe.manager.domain.patchrun

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PatchRunContractTest {
    private fun apk(hash: String = "abc") = ApkDescriptor(
        packageName = "example.app", versionName = "1.0", versionCode = 7,
        abis = setOf("arm64-v8a"), minSdk = 26, targetSdk = 35,
        containerType = ApkContainerType.UNIVERSAL_APK, sizeBytes = 100, sha256 = hash,
    )

    @Test fun noProfileDoesNotRestrictGenericPatching() {
        assertTrue(ProfileCompatibility.evaluate(null, apk()).compatible)
    }

    @Test fun profileWithoutHashDoesNotRequireHashMatch() {
        val profile = ApplicationProfile("generic", "Generic", packageNames = setOf("example.app"))
        assertTrue(ProfileCompatibility.evaluate(profile, apk("anything")).compatible)
    }

    @Test fun declaredHashIsMandatory() {
        val profile = ApplicationProfile("strict", "Strict", expectedHashes = setOf("expected"))
        assertEquals(CompatibilityCode.HASH_MISMATCH, ProfileCompatibility.evaluate(profile, apk("other")).code)
    }

    @Test fun transactionCannotSkipVerify() {
        val tx = PatchRunTransaction()
        tx.advance(PatchRunStage.PATCH); tx.advance(PatchRunStage.SIGN)
        assertFailsWith<InvalidPatchRunTransition> { tx.advance(PatchRunStage.COMMIT) }
        tx.advance(PatchRunStage.VERIFY); tx.advance(PatchRunStage.COMMIT)
        assertEquals(PatchRunStage.COMMIT, tx.stage)
    }

    @Test fun reportMatchesGoldenJson() {
        val report = PatchRunReport(
            runId = "golden-run", startedAtEpochMs = 1000, finishedAtEpochMs = 2000,
            stage = PatchRunStage.COMMIT, succeeded = true, input = apk("abc"),
            integrity = IntegrityCheck(true, "abc"), compatibility = CompatibilityResult(true, CompatibilityCode.COMPATIBLE, "compatible"),
        )
        val actual = Json { prettyPrint = true; encodeDefaults = true; explicitNulls = true }
            .encodeToString(report)
        val expected = javaClass.getResource("/patch-run-report-v1.json")!!.readText()
        assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(actual))
    }
}
