package app.morphe.manager.domain.patchrun

import kotlin.test.Test
import kotlin.test.assertEquals

class PatchRunFailureClassifierTest {
    @Test fun classifiesPatchFailure() =
        assertEquals(PatchRunErrorCode.PATCH_FAILED, PatchRunFailureClassifier.code(PatchRunStage.PATCH))

    @Test fun classifiesSigningFailure() =
        assertEquals(PatchRunErrorCode.SIGN_FAILED, PatchRunFailureClassifier.code(PatchRunStage.SIGN))

    @Test fun classifiesVerificationFailure() =
        assertEquals(PatchRunErrorCode.SIGNATURE_INVALID, PatchRunFailureClassifier.code(PatchRunStage.VERIFY))

    @Test fun classifiesCommitFailure() =
        assertEquals(PatchRunErrorCode.OUTPUT_HASH_FAILED, PatchRunFailureClassifier.code(PatchRunStage.COMMIT))

    @Test fun cancellationOverridesStage() =
        assertEquals(PatchRunErrorCode.CANCELLED, PatchRunFailureClassifier.code(PatchRunStage.PATCH, cancelled = true))
}
