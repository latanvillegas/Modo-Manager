package app.morphe.manager.patcher.patch

/** Fail-closed validation between a persisted/user selection and the bundle actually loaded. */
object PatchSelectionValidator {
    fun requireAvailable(
        bundleLabel: String,
        requested: Set<String>,
        available: Set<String>,
    ) {
        val missing = requested - available
        require(missing.isEmpty()) {
            "Selected patches are no longer available in bundle $bundleLabel: " +
                missing.sorted().joinToString(",")
        }
    }
}
