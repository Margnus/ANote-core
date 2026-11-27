package dev.notecore.document

import dev.notecore.canvas.prototype.BrushKind
import kotlin.test.*

class PenSlotRulesTest {
    private val defaults = PenSlotRules.defaults()

    @Test fun defaultsHaveSixDistinctSlotsWithPenAndHighlighterAndNoPressure() {
        assertEquals(PenSlotRules.SLOT_COUNT, defaults.slots.size)
        assertEquals(0, defaults.activeIndex)
        assertTrue(defaults.slots.none { it.kind == BrushKind.PRESSURE })
        assertTrue(defaults.slots.any { it.kind == BrushKind.MONOLINE })
        assertTrue(defaults.slots.any { it.kind == BrushKind.HIGHLIGHTER })
        assertEquals(defaults.slots.map { it.argb }.toSet().size, PenSlotRules.SLOT_COUNT)
        assertTrue(defaults.slots.all { it.width.isFinite() && it.width > 0.0 })
        assertEquals(defaults, PenSlotRules.defaults())
    }

    @Test fun selectSwitchesActiveIndexAndRejectsSameOrOutOfRange() {
        val patch = assertNotNull(PenSlotRules.plan(defaults, PenSlotChange.Select(3)).patch)
        assertEquals(0, patch.before.activeIndex); assertEquals(3, patch.after.activeIndex)
        assertEquals(defaults.slots, patch.after.slots)
        assertEquals(PenSlotFailure.NO_CHANGE, PenSlotRules.plan(patch.after, PenSlotChange.Select(3)).failure)
        for (index in listOf(-1, PenSlotRules.SLOT_COUNT)) {
            assertEquals(PenSlotFailure.INVALID_INDEX, PenSlotRules.plan(defaults, PenSlotChange.Select(index)).failure)
        }
        assertEquals(0, defaults.activeIndex)
    }

    @Test fun updateReplacesSlotAndPreservesOthersWithoutMutatingInput() {
        val replacement = PenSlot(BrushKind.HIGHLIGHTER, 0xFF112233.toInt(), 8.0)
        val patch = assertNotNull(PenSlotRules.plan(defaults, PenSlotChange.Update(2, replacement)).patch)
        assertEquals(defaults.slots[0], patch.after.slots[0])
        assertEquals(replacement, patch.after.slots[2])
        assertEquals(defaults.slots[3], patch.after.slots[3])
        assertEquals(0, patch.after.activeIndex)
        assertEquals(defaults.slots, patch.before.slots)
        assertEquals(PenSlotFailure.NO_CHANGE,
            PenSlotRules.plan(patch.after, PenSlotChange.Update(2, replacement)).failure)
    }

    @Test fun invalidSlotBrushAndIndexAreRejectedWithoutPartialPatch() {
        for (bad in listOf(
            PenSlot(BrushKind.PRESSURE, 1, 2.0),
            PenSlot(BrushKind.MONOLINE, 1, 0.0),
            PenSlot(BrushKind.MONOLINE, 1, -1.0),
            PenSlot(BrushKind.MONOLINE, 1, Double.NaN),
            PenSlot(BrushKind.MONOLINE, 1, 129.0))) {
            assertEquals(PenSlotFailure.INVALID_SLOT, PenSlotRules.plan(defaults, PenSlotChange.Update(0, bad)).failure)
        }
        for (index in listOf(-1, PenSlotRules.SLOT_COUNT)) {
            assertEquals(PenSlotFailure.INVALID_INDEX,
                PenSlotRules.plan(defaults, PenSlotChange.Update(index, defaults.slots[0])).failure)
        }
        assertNull(PenSlotRules.plan(defaults, PenSlotChange.Update(0, PenSlot(BrushKind.MONOLINE, 1, 3.0))).failure)
    }

    @Test fun resetRestoresDefaultsAndNoChangeWhenAlreadyDefault() {
        val modified = assertNotNull(PenSlotRules.plan(defaults, PenSlotChange.Update(4,
            PenSlot(BrushKind.HIGHLIGHTER, 0xFFABCDEF.toInt(), 20.0))).patch).after
        val patch = assertNotNull(PenSlotRules.plan(modified, PenSlotChange.Reset).patch)
        assertEquals(defaults, patch.after)
        assertEquals(PenSlotFailure.NO_CHANGE, PenSlotRules.plan(defaults, PenSlotChange.Reset).failure)
    }

    @Test fun invalidInitialStateIsRejectedWithoutPartialPatch() {
        for (state in listOf(
            PenSlotState(defaults.slots.drop(1), 0),
            PenSlotState(defaults.slots + defaults.slots.first(), 0),
            PenSlotState(defaults.slots.map { it.copy(width = 0.0) }, 0),
            PenSlotState(defaults.slots.map { it.copy(kind = BrushKind.PRESSURE) }, 0),
            PenSlotState(defaults.slots, -1),
            PenSlotState(defaults.slots, PenSlotRules.SLOT_COUNT))) {
            assertEquals(PenSlotFailure.INVALID_STATE.takeIf { state.slots.size != PenSlotRules.SLOT_COUNT }
                ?: PenSlotFailure.INVALID_SLOT.takeIf { state.slots.any { s -> !validSlotForTest(s) } }
                ?: PenSlotFailure.INVALID_INDEX,
                PenSlotRules.plan(state, PenSlotChange.Select(1)).failure)
        }
    }

    @Test fun callerListMutationCannotChangeAcceptedState() {
        val mutable = defaults.slots.toMutableList()
        val state = PenSlotState(mutable, 0)
        val patch = assertNotNull(PenSlotRules.plan(state, PenSlotChange.Select(2)).patch)
        mutable.clear()
        assertEquals(PenSlotRules.SLOT_COUNT, patch.after.slots.size)
        assertEquals(defaults.slots, patch.after.slots)
        assertEquals(2, patch.after.activeIndex)
    }

    @Test fun hostFacadeUsesTheSameRulesAndReturnsNullWithoutChanges() {
        val edited = assertNotNull(PenSlotRules.update(defaults, 4, BrushKind.HIGHLIGHTER, 0xFFABCDEF.toInt(), 20.0))
        assertEquals(PenSlot(BrushKind.HIGHLIGHTER, 0xFFABCDEF.toInt(), 20.0), edited.slots[4])
        assertEquals(3, assertNotNull(PenSlotRules.select(edited, 3)).activeIndex)
        assertNull(PenSlotRules.select(edited, edited.activeIndex))
        assertNull(PenSlotRules.select(edited, -1))
        assertNull(PenSlotRules.update(edited, 0, BrushKind.PRESSURE, 1, 2.0))
        assertEquals(defaults, PenSlotRules.reset(edited))
        assertNull(PenSlotRules.reset(defaults))
    }

    @Test fun hostEncodingRoundTripsAndRejectsCorruptPreferences() {
        val edited = assertNotNull(PenSlotRules.update(
            assertNotNull(PenSlotRules.select(defaults, 5)), 5, BrushKind.HIGHLIGHTER, 0xFF102030.toInt(), 12.5))
        val encoded = assertNotNull(PenSlotRules.encode(edited))
        assertEquals(edited, PenSlotRules.decode(encoded))
        assertNull(PenSlotRules.decode(null))
        assertNull(PenSlotRules.decode(""))
        assertNull(PenSlotRules.decode(encoded.replaceFirst("v1", "v2")))
        assertNull(PenSlotRules.decode(encoded.replaceFirst("HIGHLIGHTER", "PRESSURE")))
        assertNull(PenSlotRules.decode(encoded.replaceFirst("12.5", "0.0")))
        assertNull(PenSlotRules.decode(encoded.replaceFirst("a=5", "a=6")))
        assertNull(PenSlotRules.decode(encoded + ";extra"))
        assertNull(PenSlotRules.encode(PenSlotState(defaults.slots.drop(1), 0)))
    }

    private fun validSlotForTest(slot: PenSlot) = when (slot.kind) {
        BrushKind.PRESSURE -> false
        else -> slot.width.isFinite() && slot.width > 0.0 && slot.width <= 128.0
    }
}
