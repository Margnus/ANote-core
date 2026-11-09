package dev.notecore.document

import kotlin.test.*

class TextPayloadDraftTest {
    private val style = TextStyleDraft(14.0, 0xff125634.toInt(), false, null)
    private val emphasis = style.copy(bold = true, fontRequest = "requested-font")
    private val text = "中A😀e\u0301"
    private val spans = listOf(TextRunDraft(0, 2, style), TextRunDraft(2, 4, emphasis), TextRunDraft(4, 6, style))
    private val budget = TextPayloadBudget(100, 10, 10000)
    private fun make(value: String = text, runs: List<TextRunDraft> = spans, limit: TextPayloadBudget = budget,
                     base: TextStyleDraft = style, width: Double = 120.0, height: Double = 80.0, version: Int = 1) =
        TextPayloadDraft.create(version, value, width, height, base, runs, limit)

    @Test fun mixedUnicodeAndCompleteStylesArePreservedWithoutNormalizationOrFontResolution() {
        val p = assertNotNull(make().payload)
        assertEquals(6, p.text.length); assertEquals(text, p.text)
        assertEquals("😀", p.text.substring(p.run(1).start, p.run(1).end))
        assertEquals("e\u0301", p.text.substring(p.run(2).start, p.run(2).end))
        assertEquals(emphasis, p.run(1).style)
        assertEquals(120.0, p.width); assertEquals(80.0, p.height)
    }

    @Test fun malformedUnicodeAndSplitSurrogateRangeAreRejectedWithoutPartialPayload() {
        for (bad in listOf("\uD800", "\uDC00", "x\uD800y")) {
            val r = make(bad, listOf(TextRunDraft(0, bad.length, style)))
            assertNull(r.payload); assertEquals(TextPayloadFailure.UNICODE, r.failure)
        }
        for (split in listOf(listOf(TextRunDraft(0, 3, style), TextRunDraft(3, 6, style)),
            listOf(TextRunDraft(0, 2, style), TextRunDraft(2, 3, style), TextRunDraft(3, 6, style)))) {
            val r = make(runs = split); assertNull(r.payload); assertEquals(TextPayloadFailure.RUNS, r.failure)
        }
    }

    @Test fun gapsOverlapsReversedEmptyAndOutOfRangeRunsNeverUseImplicitDefaults() {
        for (bad in listOf(emptyList(), listOf(TextRunDraft(1, 6, style)),
            listOf(TextRunDraft(0, 4, style), TextRunDraft(2, 6, style)),
            listOf(TextRunDraft(0, 2, style), TextRunDraft(4, 6, style)),
            listOf(TextRunDraft(0, 0, style)), listOf(TextRunDraft(0, 7, style)),
            listOf(TextRunDraft(-1, 6, style)), listOf(TextRunDraft(0, 6, style), TextRunDraft(6, 5, style)))) {
            val r = make(runs = bad); assertNull(r.payload); assertEquals(TextPayloadFailure.RUNS, r.failure)
        }
    }

    @Test fun callerListMutationCannotChangeAcceptedPayloadAndEmptyTextKeepsInsertionStyle() {
        val input = spans.toMutableList(); val p = assertNotNull(make(runs = input).payload)
        input.clear(); input.add(TextRunDraft(0, 1, emphasis))
        assertEquals(3, p.runCount); assertEquals(spans[2], p.run(2))
        val empty = assertNotNull(make("", emptyList(), base = emphasis).payload)
        assertEquals(0, empty.runCount); assertEquals(emphasis, empty.defaultStyle)
        assertEquals(TextPayloadFailure.RUNS, make("", listOf(TextRunDraft(0, 0, style))).failure)
    }

    @Test fun exactLogicalBudgetAdmitsAndEachSmallerCapacityRefusesWholeInput() {
        val charge = assertNotNull(make().payload).logicalByteCharge
        assertNotNull(make(limit = TextPayloadBudget(6, 3, charge)).payload)
        for (small in listOf(TextPayloadBudget(5, 3, charge), TextPayloadBudget(6, 2, charge),
            TextPayloadBudget(6, 3, charge - 1))) {
            val r = make(limit = small); assertNull(r.payload); assertEquals(TextPayloadFailure.CAPACITY, r.failure)
        }
        for (bad in listOf(TextPayloadBudget(-1, 3, charge), TextPayloadBudget(6, -1, charge), TextPayloadBudget(6, 3, -1))) {
            assertEquals(TextPayloadFailure.BUDGET, make(limit = bad).failure)
        }
    }

    @Test fun unknownVersionInvalidBoxAndInvalidStylesHaveExplicitRefusal() {
        assertEquals(TextPayloadFailure.VERSION, make(version = 2).failure)
        for (bad in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(TextPayloadFailure.BOX, make(width = bad).failure)
            assertEquals(TextPayloadFailure.BOX, make(height = bad).failure)
        }
        for (bad in listOf(style.copy(size = 0.0), style.copy(size = Double.NaN),
            style.copy(fontRequest = ""), style.copy(fontRequest = "x".repeat(129)), style.copy(fontRequest = "\uD800"))) {
            assertEquals(TextPayloadFailure.STYLE, make(base = bad).failure)
            assertEquals(TextPayloadFailure.STYLE, make(runs = listOf(TextRunDraft(0, 6, bad))).failure)
        }
    }
}
