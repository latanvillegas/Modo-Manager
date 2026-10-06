package app.morphe.manager.patcher.patch

import kotlin.test.Test
import kotlin.test.assertFailsWith

class PatchSelectionValidatorTest {
    @Test
    fun `available selection passes`() {
        PatchSelectionValidator.requireAvailable(
            bundleLabel = "demo",
            requested = setOf("One"),
            available = setOf("One", "Two"),
        )
    }

    @Test
    fun `stale selected patch is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            PatchSelectionValidator.requireAvailable(
                bundleLabel = "demo",
                requested = setOf("One", "Removed"),
                available = setOf("One", "Two"),
            )
        }
    }
}
