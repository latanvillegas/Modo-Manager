package app.morphe.manager.domain.patchrun

data class PreflightResult(
    val descriptor: ApkDescriptor,
    val integrity: IntegrityCheck,
    val profile: ApplicationProfile?,
    val compatibility: CompatibilityResult,
) {
    val allowed: Boolean get() = integrity.valid && compatibility.compatible
}

/**
 * Generic orchestration gate. A null profile is valid: patch bundle metadata remains the
 * executable compatibility authority when no application-specific profile is selected.
 */
object PatchRunPreflight {
    fun evaluate(
        descriptor: ApkDescriptor,
        profile: ApplicationProfile? = null,
    ): PreflightResult {
        val expectedHash = profile?.expectedHashes?.takeIf { it.isNotEmpty() }?.firstOrNull()
        val integrity = IntegrityCheck(
            archiveReadable = descriptor.sizeBytes > 0,
            actualSha256 = descriptor.sha256,
            expectedSha256 = expectedHash,
        )
        val compatibility = ProfileCompatibility.evaluate(profile, descriptor)
        return PreflightResult(descriptor, integrity, profile, compatibility)
    }
}
