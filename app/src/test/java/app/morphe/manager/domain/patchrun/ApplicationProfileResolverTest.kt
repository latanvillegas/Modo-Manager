package app.morphe.manager.domain.patchrun

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ApplicationProfileResolverTest {
    private val apk = ApkDescriptor(
        packageName = "example.app", versionName = "2", versionCode = 20,
        abis = setOf("arm64-v8a"), minSdk = 29, targetSdk = 36,
        containerType = ApkContainerType.UNIVERSAL_APK, sizeBytes = 10, sha256 = "abc",
    )

    @Test fun noProfileForPackagePreservesGenericBehavior() {
        val profile = ApplicationProfile("other", "Other", packageNames = setOf("other.app"))
        assertNull(ApplicationProfileResolver.resolve(listOf(profile), apk))
    }

    @Test fun singlePackageProfileIsReturnedSoPreflightCanRejectVersionOrHash() {
        val profile = ApplicationProfile(
            "candidate", "Candidate", packageNames = setOf("example.app"),
            versionRules = VersionRule(exact = setOf("1")), expectedHashes = setOf("def")
        )
        assertEquals(profile, ApplicationProfileResolver.resolve(listOf(profile), apk))
    }

    @Test fun multipleProfilesAreNarrowedByIdentity() {
        val old = ApplicationProfile(
            "old", "Old", packageNames = setOf("example.app"),
            versionRules = VersionRule(exact = setOf("1")), versionCodes = setOf(10)
        )
        val current = ApplicationProfile(
            "current", "Current", packageNames = setOf("example.app"),
            versionRules = VersionRule(exact = setOf("2")), versionCodes = setOf(20),
            abis = setOf("arm64-v8a")
        )
        assertEquals(current, ApplicationProfileResolver.resolve(listOf(old, current), apk))
    }

    @Test fun ambiguousProfilesFailClosed() {
        val a = ApplicationProfile("a", "A", packageNames = setOf("example.app"))
        val b = ApplicationProfile("b", "B", packageNames = setOf("example.app"))
        assertFailsWith<IllegalArgumentException> {
            ApplicationProfileResolver.resolve(listOf(a, b), apk)
        }
    }
}
