package app.morphe.manager.domain.patchrun

object PatchRunFailureClassifier {
    fun code(stage: PatchRunStage, cancelled: Boolean = false): PatchRunErrorCode {
        if (cancelled) return PatchRunErrorCode.CANCELLED
        return when (stage) {
            PatchRunStage.PRECHECK -> PatchRunErrorCode.INTERNAL_ERROR
            PatchRunStage.PATCH -> PatchRunErrorCode.PATCH_FAILED
            PatchRunStage.SIGN -> PatchRunErrorCode.SIGN_FAILED
            PatchRunStage.VERIFY -> PatchRunErrorCode.SIGNATURE_INVALID
            PatchRunStage.COMMIT -> PatchRunErrorCode.OUTPUT_HASH_FAILED
        }
    }
}
