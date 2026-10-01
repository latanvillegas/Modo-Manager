package app.morphe.manager.domain.patchrun

class InvalidPatchRunTransition(from: PatchRunStage, to: PatchRunStage) :
    IllegalStateException("Invalid patch run transition: $from -> $to")

class PatchRunTransaction(initial: PatchRunStage = PatchRunStage.PRECHECK) {
    var stage: PatchRunStage = initial
        private set

    fun advance(to: PatchRunStage) {
        val expected = when (stage) {
            PatchRunStage.PRECHECK -> PatchRunStage.PATCH
            PatchRunStage.PATCH -> PatchRunStage.SIGN
            PatchRunStage.SIGN -> PatchRunStage.VERIFY
            PatchRunStage.VERIFY -> PatchRunStage.COMMIT
            PatchRunStage.COMMIT -> null
        }
        if (to != expected) throw InvalidPatchRunTransition(stage, to)
        stage = to
    }
}
