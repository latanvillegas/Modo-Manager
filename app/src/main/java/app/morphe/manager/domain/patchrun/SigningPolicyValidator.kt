package app.morphe.manager.domain.patchrun

data class SigningPolicyResult(val valid: Boolean, val reason: String)

object SigningPolicyValidator {
    fun evaluate(
        policy: SigningPolicy,
        inputPackage: String,
        outputPackage: String,
        inputCertificates: Set<String>,
        outputCertificates: Set<String>,
        signatureVerified: Boolean,
    ): SigningPolicyResult {
        if (policy.requireVerifiedOutput && !signatureVerified)
            return SigningPolicyResult(false, "Final APK signature is not cryptographically verified.")

        return when (policy.mode) {
            SigningMode.TEST_KEY, SigningMode.USER_KEY ->
                SigningPolicyResult(true, "Verified output accepted by selected signing policy.")

            SigningMode.REQUIRE_SAME_CERTIFICATE -> {
                if (inputCertificates.isEmpty())
                    SigningPolicyResult(false, "Original signing certificate is unavailable.")
                else if (outputCertificates.intersect(inputCertificates).isEmpty())
                    SigningPolicyResult(false, "Final APK certificate does not match the original certificate.")
                else
                    SigningPolicyResult(true, "Final APK certificate matches the original certificate.")
            }

            SigningMode.INSTALL_AS_NEW_APP ->
                if (outputPackage == inputPackage)
                    SigningPolicyResult(false, "Install-as-new-app requires a different package name.")
                else
                    SigningPolicyResult(true, "Final APK uses a distinct package name.")
        }
    }
}
