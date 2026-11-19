package dev.notecore.document

import dev.notecore.canvas.prototype.BrushKind

/** App-level pen preference slot; not document content and not a persisted authority. */
internal data class PenSlot(val kind: BrushKind, val argb: Int, val width: Double)
internal data class PenSlotState(val slots: List<PenSlot>, val activeIndex: Int)
internal sealed interface PenSlotChange {
    data class Select(val index: Int) : PenSlotChange
    data class Update(val index: Int, val slot: PenSlot) : PenSlotChange
    data object Reset : PenSlotChange
}
internal data class PenSlotPatch(
    val before: PenSlotState, val after: PenSlotState, val change: PenSlotChange,
)
internal enum class PenSlotFailure { INVALID_STATE, INVALID_INDEX, INVALID_SLOT, NO_CHANGE }
internal data class PenSlotResult(val patch: PenSlotPatch? = null, val failure: PenSlotFailure? = null)

internal object PenSlotRules {
    const val SLOT_COUNT = 6

    fun defaults(): PenSlotState = PenSlotState(listOf(
        PenSlot(BrushKind.MONOLINE, 0xFF000000.toInt(), 2.0),
        PenSlot(BrushKind.MONOLINE, 0xFFDC2626.toInt(), 2.0),
        PenSlot(BrushKind.MONOLINE, 0xFF2563EB.toInt(), 2.0),
        PenSlot(BrushKind.MONOLINE, 0xFF16A34A.toInt(), 2.0),
        PenSlot(BrushKind.HIGHLIGHTER, 0xFFEAB308.toInt(), 12.0),
        PenSlot(BrushKind.MONOLINE, 0xFFEA580C.toInt(), 3.0),
    ), 0)

    private fun validSlot(slot: PenSlot) = when (slot.kind) {
        BrushKind.PRESSURE -> false
        else -> slot.width.isFinite() && slot.width > 0.0 && slot.width <= 128.0
    }

    private fun validate(state: PenSlotState): PenSlotFailure? {
        if (state.slots.size != SLOT_COUNT) return PenSlotFailure.INVALID_STATE
        if (state.slots.any { !validSlot(it) }) return PenSlotFailure.INVALID_SLOT
        if (state.activeIndex !in 0 until SLOT_COUNT) return PenSlotFailure.INVALID_INDEX
        return null
    }

    fun plan(state: PenSlotState, change: PenSlotChange): PenSlotResult {
        validate(state)?.let { return PenSlotResult(failure = it) }
        val frozen = state.copy(slots = state.slots.toList())
        return when (change) {
            is PenSlotChange.Select -> {
                if (change.index !in 0 until SLOT_COUNT) return PenSlotResult(failure = PenSlotFailure.INVALID_INDEX)
                if (frozen.activeIndex == change.index) return PenSlotResult(failure = PenSlotFailure.NO_CHANGE)
                PenSlotResult(PenSlotPatch(frozen, frozen.copy(activeIndex = change.index), change))
            }
            is PenSlotChange.Update -> {
                if (change.index !in 0 until SLOT_COUNT) return PenSlotResult(failure = PenSlotFailure.INVALID_INDEX)
                if (!validSlot(change.slot)) return PenSlotResult(failure = PenSlotFailure.INVALID_SLOT)
                if (frozen.slots[change.index] == change.slot) return PenSlotResult(failure = PenSlotFailure.NO_CHANGE)
                val slots = frozen.slots.toMutableList()
                slots[change.index] = change.slot
                PenSlotResult(PenSlotPatch(frozen, frozen.copy(slots = slots.toList()), change))
            }
            is PenSlotChange.Reset -> {
                val default = defaults()
                if (frozen == default) return PenSlotResult(failure = PenSlotFailure.NO_CHANGE)
                PenSlotResult(PenSlotPatch(frozen, default, change))
            }
        }
    }
}
