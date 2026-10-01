package app.morphe.manager.domain.patchrun.profiles

import app.morphe.manager.domain.patchrun.ApplicationProfile
import app.morphe.manager.domain.patchrun.ProfileTest
import app.morphe.manager.domain.patchrun.RiskLevel
import app.morphe.manager.domain.patchrun.SigningPolicy
import app.morphe.manager.domain.patchrun.VersionRule

object Cromite153Profile {
    const val ID = "cromite-153.0.8010.37-arm64"

    val profile = ApplicationProfile(
        id = ID,
        displayName = "Cromite 153.0.8010.37 (arm64-v8a)",
        packageNames = setOf("org.cromite.cromite"),
        versionRules = VersionRule(exact = setOf("153.0.8010.37")),
        versionCodes = setOf(801003702L),
        abis = setOf("arm64-v8a"),
        expectedHashes = setOf("9db12af1af021f42b4371e76d0e9fe476a7085bbbbc76bc410a538d028bf6071"),
        signingPolicy = SigningPolicy(requireVerifiedOutput = true),
        tests = listOf(
            ProfileTest("identity", "Package, version name, version code and ABI must match."),
            ProfileTest("source-hash", "The selected source APK must match the declared SHA-256."),
            ProfileTest("signed-output", "The final APK signature must verify cryptographically."),
        ),
        risk = RiskLevel.HIGH,
    )
}

object BuiltInApplicationProfiles {
    val all: List<ApplicationProfile> = listOf(Cromite153Profile.profile)

    fun candidates(packageName: String): List<ApplicationProfile> =
        all.filter { it.packageNames.isEmpty() || packageName in it.packageNames }
}
