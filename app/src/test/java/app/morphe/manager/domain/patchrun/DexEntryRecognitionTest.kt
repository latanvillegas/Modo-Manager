package app.morphe.manager.domain.patchrun

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DexEntryRecognitionTest {
    @Test fun acceptsPrimaryDex() = assertTrue(isDexEntry("classes.dex"))
    @Test fun acceptsSecondaryDex() = assertTrue(isDexEntry("classes2.dex"))
    @Test fun acceptsHigherMultidex() = assertTrue(isDexEntry("classes17.dex"))
    @Test fun rejectsNestedDex() = assertFalse(isDexEntry("assets/classes.dex"))
    @Test fun rejectsMissingIndex() = assertFalse(isDexEntry("classesx.dex"))
    @Test fun rejectsLookalike() = assertFalse(isDexEntry("classes.dex.tmp"))
}
