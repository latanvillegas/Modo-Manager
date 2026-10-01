package app.morphe.manager.domain.patchrun

import kotlinx.serialization.Serializable

@Serializable enum class ApkContainerType { UNIVERSAL_APK, SPLIT_ARCHIVE }

@Serializable
data class ApkDescriptor(
    val packageName: String, val versionName: String?, val versionCode: Long,
    val abis: Set<String> = emptySet(), val minSdk: Int?, val targetSdk: Int?,
    val containerType: ApkContainerType, val sizeBytes: Long, val sha256: String,
    val signingCertificateSha256: Set<String> = emptySet(),
)

@Serializable
data class IntegrityCheck(val archiveReadable: Boolean, val actualSha256: String?, val expectedSha256: String? = null) {
    val hashMatches: Boolean get() = expectedSha256 == null || actualSha256?.equals(expectedSha256, true) == true
    val valid: Boolean get() = archiveReadable && actualSha256 != null && hashMatches
}

@Serializable enum class CompatibilityCode { COMPATIBLE, PACKAGE_MISMATCH, VERSION_MISMATCH, VERSION_CODE_MISMATCH, ABI_MISMATCH, HASH_MISMATCH, PATCHER_TOO_OLD, PROFILE_RESTRICTION }
@Serializable data class CompatibilityResult(val compatible: Boolean, val code: CompatibilityCode, val reason: String)
@Serializable enum class PatchRunStage { PRECHECK, PATCH, SIGN, VERIFY, COMMIT }
@Serializable enum class PatchRunErrorCode { INVALID_ARCHIVE, PACKAGE_MISMATCH, VERSION_MISMATCH, VERSION_CODE_MISMATCH, ABI_MISMATCH, HASH_MISMATCH, INCOMPATIBLE_PATCH, PATCHER_TOO_OLD, PATCH_FAILED, SIGN_FAILED, SIGNATURE_INVALID, OUTPUT_HASH_FAILED, EXPORT_FAILED, CANCELLED, INTERNAL_ERROR }
@Serializable data class PatchRunError(val code: PatchRunErrorCode, val stage: PatchRunStage, val message: String, val causeType: String? = null)
@Serializable enum class RiskLevel { LOW, MEDIUM, HIGH }
@Serializable data class VersionRule(val exact: Set<String> = emptySet())
@Serializable data class ProfilePatchRestriction(val patchId: String, val requires: Set<String> = emptySet(), val incompatibleWith: Set<String> = emptySet())
@Serializable enum class SigningMode { TEST_KEY, USER_KEY, REQUIRE_SAME_CERTIFICATE, INSTALL_AS_NEW_APP }
@Serializable data class SigningPolicy(
    val mode: SigningMode = SigningMode.USER_KEY,
    val requireVerifiedOutput: Boolean = true,
)
@Serializable data class ProfileTest(val id: String, val description: String)

@Serializable
data class ApplicationProfile(
    val id: String, val displayName: String,
    val packageNames: Set<String> = emptySet(), val versionRules: VersionRule? = null,
    val versionCodes: Set<Long> = emptySet(), val abis: Set<String> = emptySet(),
    val expectedHashes: Set<String> = emptySet(), val patchIds: Set<String> = emptySet(),
    val restrictions: List<ProfilePatchRestriction> = emptyList(), val minimumPatcherVersion: String? = null,
    val signingPolicy: SigningPolicy = SigningPolicy(), val tests: List<ProfileTest> = emptyList(),
    val risk: RiskLevel = RiskLevel.MEDIUM,
)

object ProfileCompatibility {
    fun evaluate(profile: ApplicationProfile?, apk: ApkDescriptor): CompatibilityResult {
        if (profile == null) return ok("No application profile selected; bundle compatibility is authoritative.")
        if (profile.packageNames.isNotEmpty() && apk.packageName !in profile.packageNames)
            return no(CompatibilityCode.PACKAGE_MISMATCH, "Package ${apk.packageName} is not allowed by profile ${profile.id}.")
        profile.versionRules?.exact?.takeIf { it.isNotEmpty() }?.let {
            if (apk.versionName !in it) return no(CompatibilityCode.VERSION_MISMATCH, "Version ${apk.versionName} is not allowed by profile ${profile.id}.")
        }
        if (profile.versionCodes.isNotEmpty() && apk.versionCode !in profile.versionCodes)
            return no(CompatibilityCode.VERSION_CODE_MISMATCH, "Version code ${apk.versionCode} is not allowed by profile ${profile.id}.")
        if (profile.abis.isNotEmpty() && apk.abis.intersect(profile.abis).isEmpty())
            return no(CompatibilityCode.ABI_MISMATCH, "APK ABIs ${apk.abis} do not match profile ABIs ${profile.abis}.")
        if (profile.expectedHashes.isNotEmpty() && profile.expectedHashes.none { it.equals(apk.sha256, true) })
            return no(CompatibilityCode.HASH_MISMATCH, "APK SHA-256 does not match any hash declared by profile ${profile.id}.")
        return ok("APK matches profile ${profile.id}.")
    }
    private fun ok(reason: String) = CompatibilityResult(true, CompatibilityCode.COMPATIBLE, reason)
    private fun no(code: CompatibilityCode, reason: String) = CompatibilityResult(false, code, reason)
}

@Serializable data class PatchRef(val id: String, val source: String? = null, val sourceVersion: String? = null)
@Serializable data class ArtifactRef(
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val signingCertificateSha256: Set<String> = emptySet(),
    val signatureVerified: Boolean? = null,
)
@Serializable data class ProvenanceRef(val commit: String? = null, val workflow: String? = null, val workflowRunId: String? = null, val attestation: String? = null)

@Serializable
data class PatchRunReport(
    val schemaVersion: Int = 1, val runId: String, val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long? = null, val stage: PatchRunStage, val succeeded: Boolean = false,
    val profileId: String? = null, val input: ApkDescriptor, val integrity: IntegrityCheck,
    val compatibility: CompatibilityResult, val patches: List<PatchRef> = emptyList(),
    val output: ArtifactRef? = null, val errors: List<PatchRunError> = emptyList(),
    val logs: List<String> = emptyList(), val provenance: ProvenanceRef? = null,
) {
    fun toText(): String = buildString {
        appendLine("Morphe Patch Run Report v$schemaVersion")
        appendLine("runId: $runId"); appendLine("stage: $stage"); appendLine("succeeded: $succeeded")
        appendLine("profile: ${profileId ?: "none"}")
        appendLine("input.package: ${input.packageName}")
        appendLine("input.version: ${input.versionName ?: "unknown"} (${input.versionCode})")
        appendLine("input.abis: ${input.abis.sorted().joinToString(",")}")
        appendLine("input.sha256: ${input.sha256}")
        appendLine("integrity.valid: ${integrity.valid}")
        appendLine("compatibility: ${compatibility.code} - ${compatibility.reason}")
        output?.let {
            appendLine("output.file: ${it.fileName}"); appendLine("output.sha256: ${it.sha256}")
            appendLine("output.signatureVerified: ${it.signatureVerified}")
            appendLine("output.certificateSha256: ${it.signingCertificateSha256 ?: "unknown"}")
        }
        provenance?.let {
            appendLine("provenance.commit: ${it.commit ?: "unknown"}"); appendLine("provenance.workflow: ${it.workflow ?: "unknown"}")
            appendLine("provenance.runId: ${it.workflowRunId ?: "unknown"}")
        }
        errors.forEach { appendLine("error.${it.stage}.${it.code}: ${it.message}") }
        if (logs.isNotEmpty()) { appendLine("logs:"); logs.forEach { appendLine(it) } }
    }
}
