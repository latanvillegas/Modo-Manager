package app.morphe.manager.domain.patchrun

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PatchRunPreflightTest {
    private val apk = ApkDescriptor(
        packageName = "example.app", versionName = "1", versionCode = 1,
        abis = setOf("arm64-v8a"), minSdk = 26, targetSdk = 36,
        containerType = ApkContainerType.UNIVERSAL_APK, sizeBytes = 10, sha256 = "abc",
    )

    @Test fun noProfileKeepsGenericCoreUsable() {
        assertTrue(PatchRunPreflight.evaluate(apk).allowed)
    }

    @Test fun declaredHashMismatchFailsClosed() {
        val profile = ApplicationProfile(
            id = "p", displayName = "p", packageNames = setOf("example.app"),
            expectedHashes = setOf("def"),
        )
        assertFalse(PatchRunPreflight.evaluate(apk, profile).allowed)
    }
}
