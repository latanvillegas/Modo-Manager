package app.morphe.manager.domain.patchrun

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SigningPolicyValidatorTest {
    @Test fun sameCertificateAcceptsIntersection() {
        assertTrue(SigningPolicyValidator.evaluate(
            SigningPolicy(SigningMode.REQUIRE_SAME_CERTIFICATE), "a", "a",
            setOf("cert-a"), setOf("cert-a"), true
        ).valid)
    }

    @Test fun sameCertificateRejectsDifferentSigner() {
        assertFalse(SigningPolicyValidator.evaluate(
            SigningPolicy(SigningMode.REQUIRE_SAME_CERTIFICATE), "a", "a",
            setOf("cert-a"), setOf("cert-b"), true
        ).valid)
    }

    @Test fun installAsNewRejectsUnchangedPackage() {
        assertFalse(SigningPolicyValidator.evaluate(
            SigningPolicy(SigningMode.INSTALL_AS_NEW_APP), "a", "a",
            emptySet(), setOf("cert-b"), true
        ).valid)
    }

    @Test fun installAsNewAcceptsDistinctPackage() {
        assertTrue(SigningPolicyValidator.evaluate(
            SigningPolicy(SigningMode.INSTALL_AS_NEW_APP), "a", "a.patched",
            emptySet(), setOf("cert-b"), true
        ).valid)
    }

    @Test fun verifiedOutputGateIsMandatoryWhenRequested() {
        assertFalse(SigningPolicyValidator.evaluate(
            SigningPolicy(SigningMode.USER_KEY, requireVerifiedOutput = true), "a", "a",
            emptySet(), emptySet(), false
        ).valid)
    }
}
