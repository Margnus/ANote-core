package dev.notecore.app

import dev.notecore.canvas.prototype.BrushKind
import dev.notecore.document.PenSlot
import dev.notecore.document.PenSlotRules
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PenSlotPreferencesTest {
    @Test fun defaultsAndSavedStateSurviveNewStoreInstances() {
        val context = RuntimeEnvironment.getApplication()
        val first = PenSlotPreferences(context)
        assertEquals(PenSlotRules.defaults(), first.load())

        val edited = PenSlotRules.update(
            PenSlotRules.select(PenSlotRules.defaults(), 4)!!,
            4,
            BrushKind.HIGHLIGHTER,
            0xFF123456.toInt(),
            18.0,
        )!!
        first.save(edited)
        assertEquals(edited, PenSlotPreferences(context).load())
        assertNotEquals(PenSlot(BrushKind.MONOLINE, 0xFF000000.toInt(), 2.0), edited.slots[4])
    }

    @Test fun corruptStoredValueFallsBackToDefaultsWithoutOverwritingUntilNextSave() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("notecore-preview-pens", android.content.Context.MODE_PRIVATE)
            .edit().putString("slots-v1", "v1;broken").commit()
        val store = PenSlotPreferences(context)
        assertEquals(PenSlotRules.defaults(), store.load())
        store.save(PenSlotRules.defaults())
        assertEquals(PenSlotRules.defaults(), PenSlotPreferences(context).load())
    }
}
