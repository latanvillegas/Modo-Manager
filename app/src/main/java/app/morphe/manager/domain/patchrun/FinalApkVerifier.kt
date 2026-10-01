package app.morphe.manager.domain.patchrun

import com.android.apksig.ApkVerifier
import java.io.File
import java.security.MessageDigest

data class ApkVerificationResult(
    val verified: Boolean,
    val certificateSha256: Set<String>,
    val errors: List<String>,
    val warnings: List<String>,
)

class FinalApkVerifier {
    fun verify(file: File): ApkVerificationResult {
        val result = ApkVerifier.Builder(file).build().verify()
        val digest = MessageDigest.getInstance("SHA-256")
        val fingerprints = result.signerCertificates.mapTo(linkedSetOf()) { certificate ->
            digest.reset()
            digest.digest(certificate.encoded).joinToString("") { "%02x".format(it) }
        }
        return ApkVerificationResult(
            verified = result.isVerified,
            certificateSha256 = fingerprints,
            errors = result.errors.map { it.toString() },
            warnings = result.warnings.map { it.toString() },
        )
    }
}
