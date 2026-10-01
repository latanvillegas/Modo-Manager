package app.morphe.manager.domain.patchrun.profiles

import app.morphe.manager.domain.patchrun.ApkContainerType
import app.morphe.manager.domain.patchrun.ApkDescriptor
import app.morphe.manager.domain.patchrun.CompatibilityCode
import app.morphe.manager.domain.patchrun.ProfileCompatibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Cromite153ProfileTest {
    private fun descriptor(
        packageName: String = "org.cromite.cromite",
        versionName: String = "153.0.8010.37",
        versionCode: Long = 801003702L,
        abis: Set<String> = setOf("arm64-v8a"),
        sha256: String = "9db12af1af021f42b4371e76d0e9fe476a7085bbbbc76bc410a538d028bf6071",
    ) = ApkDescriptor(
        packageName, versionName, versionCode, abis, 29, 36,
        ApkContainerType.UNIVERSAL_APK, 323809949L, sha256,
    )

    @Test fun exactReferenceApkMatches() {
        assertTrue(ProfileCompatibility.evaluate(Cromite153Profile.profile, descriptor()).compatible)
    }

    @Test fun wrongPackageFailsClosed() {
        assertEquals(CompatibilityCode.PACKAGE_MISMATCH,
            ProfileCompatibility.evaluate(Cromite153Profile.profile, descriptor(packageName = "example.app")).code)
    }

    @Test fun wrongVersionFailsClosed() {
        assertEquals(CompatibilityCode.VERSION_MISMATCH,
            ProfileCompatibility.evaluate(Cromite153Profile.profile, descriptor(versionName = "153.0.8010.38")).code)
    }

    @Test fun wrongVersionCodeFailsClosed() {
        assertEquals(CompatibilityCode.VERSION_CODE_MISMATCH,
            ProfileCompatibility.evaluate(Cromite153Profile.profile, descriptor(versionCode = 801003703L)).code)
    }

    @Test fun wrongAbiFailsClosed() {
        assertEquals(CompatibilityCode.ABI_MISMATCH,
            ProfileCompatibility.evaluate(Cromite153Profile.profile, descriptor(abis = setOf("x86_64"))).code)
    }

    @Test fun wrongHashFailsClosed() {
        assertEquals(CompatibilityCode.HASH_MISMATCH,
            ProfileCompatibility.evaluate(Cromite153Profile.profile, descriptor(sha256 = "00")).code)
    }
}
