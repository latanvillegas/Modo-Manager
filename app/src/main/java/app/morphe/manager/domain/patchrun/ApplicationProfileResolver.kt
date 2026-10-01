package app.morphe.manager.domain.patchrun

import kotlinx.serialization.Serializable

@Serializable
data class ApplicationProfilesDocument(
    val schemaVersion: Int,
    val profiles: List<ApplicationProfile> = emptyList(),
)

/**
 * Selects a declarative profile without knowing any application names.
 *
 * A package with no declared profile keeps the generic bundle behavior. Once a profile is
 * declared for that package, however, version/hash/ABI mismatches are intentionally left for
 * PatchRunPreflight to reject instead of silently falling back to an unprofiled run.
 */
object ApplicationProfileResolver {
    fun resolve(profiles: Iterable<ApplicationProfile>, apk: ApkDescriptor): ApplicationProfile? {
        val packageCandidates = profiles
            .filter { it.packageNames.isEmpty() || apk.packageName in it.packageNames }
            .distinctBy { it.id }

        if (packageCandidates.isEmpty()) return null
        if (packageCandidates.size == 1) return packageCandidates.single()

        val identityMatches = packageCandidates.filter { profile ->
            val versionMatches = profile.versionRules?.exact
                ?.takeIf { it.isNotEmpty() }
                ?.let { apk.versionName in it } ?: true
            val codeMatches = profile.versionCodes.isEmpty() || apk.versionCode in profile.versionCodes
            val abiMatches = profile.abis.isEmpty() || apk.abis.intersect(profile.abis).isNotEmpty()
            versionMatches && codeMatches && abiMatches
        }

        return when (identityMatches.size) {
            1 -> identityMatches.single()
            0 -> throw IllegalArgumentException(
                "Multiple application profiles declare package ${apk.packageName}, but none uniquely matches its version/code/ABI."
            )
            else -> throw IllegalArgumentException(
                "Multiple application profiles match ${apk.packageName}: " +
                    identityMatches.joinToString { it.id }
            )
        }
    }
}
