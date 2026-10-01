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
        integrity: IntegrityCheck = IntegrityCheck(
            archiveReadable = descriptor.sizeBytes > 0,
            actualSha256 = descriptor.sha256,
        ),
    ): PreflightResult {
        val compatibility = ProfileCompatibility.evaluate(profile, descriptor)
        val hashRestricted = profile?.expectedHashes?.isNotEmpty() == true
        val hashMatches = !hashRestricted || profile!!.expectedHashes.any { it.equals(descriptor.sha256, ignoreCase = true) }
        val effectiveIntegrity = if (hashRestricted) {
            integrity.copy(expectedSha256 = if (hashMatches) descriptor.sha256 else "<profile-hash-set>")
        } else integrity
        return PreflightResult(descriptor, effectiveIntegrity, profile, compatibility)
    }
}
