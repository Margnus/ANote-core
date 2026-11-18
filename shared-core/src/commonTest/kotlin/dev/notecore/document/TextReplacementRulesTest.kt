package dev.notecore.document

import dev.notecore.canvas.prototype.CommandId
import kotlin.test.*

class TextReplacementRulesTest {
    private val style = TextStyleDraft(14.0, 0xff125634.toInt(), false, null)
    private val emphasis = style.copy(size = 18.0, argb = 0xff102030.toInt(), bold = true, fontRequest = "requested-font")
    private val text = "中A😀e\u0301"
    private val spans = listOf(TextRunDraft(0, 2, style), TextRunDraft(2, 4, emphasis), TextRunDraft(4, 6, style))
    private val budget = TextPayloadBudget(100, 20, 20_000)

    private fun payload(value: String = text, runs: List<TextRunDraft> = spans, default: TextStyleDraft = style) =
        assertNotNull(TextPayloadDraft.create(1, value, 120.0, 80.0, default, runs, budget).payload)

    private fun request(
        current: TextPayloadDraft,
        selection: TextSelectionDraft = TextSelectionDraft(4, 2),
        replacement: String = "新😀",
        insertionStyle: TextStyleDraft = emphasis,
        defaultStyle: TextStyleDraft = current.defaultStyle,
        width: Double = current.width,
        height: Double = current.height,
        confirmed: Boolean = true,
        operation: CommandId = CommandId("edit"),
        limit: TextPayloadBudget = budget,
    ) = TextReplacementRequest(operation, selection, replacement, insertionStyle,
        defaultStyle, width, height, confirmed, limit)

    @Test fun confirmedReverseSelectionAtomicallyReplacesTextStylesAndLogicalBox() {
        val current = payload()
        val result = TextReplacementRules.plan(current, request(current, defaultStyle = emphasis, width = 200.0, height = 100.0))
        val plan = assertNotNull(result.plan)
        assertSame(current, plan.before)
        assertEquals("中A新😀e\u0301", plan.after.text)
        assertEquals(200.0, plan.after.width); assertEquals(100.0, plan.after.height)
        assertEquals(emphasis, plan.after.defaultStyle)
        assertEquals(3, plan.after.runCount)
        assertEquals(TextRunDraft(0, 2, style), plan.after.run(0))
        assertEquals(TextRunDraft(2, 5, emphasis), plan.after.run(1))
        assertEquals(TextRunDraft(5, 7, style), plan.after.run(2))
        assertEquals(4, plan.request.selection.anchor); assertEquals(2, plan.request.selection.focus)
        assertEquals("requested-font", plan.after.run(1).style.fontRequest)
        assertEquals(text, current.text); assertEquals(3, current.runCount)
        assertEquals(120.0, current.width); assertEquals(style, current.defaultStyle)
    }

    @Test fun sameTextReplacementRestylesSelectionAndMergesAdjacentEqualRuns() {
        val current = payload("ABCD", listOf(TextRunDraft(0, 1, style),
            TextRunDraft(1, 3, emphasis), TextRunDraft(3, 4, style)))
        val plan = assertNotNull(TextReplacementRules.plan(current, request(current,
            selection = TextSelectionDraft(1, 3), replacement = "BC", insertionStyle = style)).plan)
        assertEquals("ABCD", plan.after.text)
        assertEquals(1, plan.after.runCount)
        assertEquals(TextRunDraft(0, 4, style), plan.after.run(0))
        assertEquals(14.0, plan.after.run(0).style.size)
        assertEquals(0xff125634.toInt(), plan.after.run(0).style.argb)
        assertFalse(plan.after.run(0).style.bold)
        assertEquals(3, current.runCount)
    }

    @Test fun insertionAndDeletionPreserveUnselectedContentAndNeverMutateBefore() {
        val current = payload()
        val inserted = assertNotNull(TextReplacementRules.plan(current, request(current,
            selection = TextSelectionDraft(2, 2), replacement = "X")).plan)
        assertEquals("中AX😀e\u0301", inserted.after.text)
        assertEquals(3, inserted.after.runCount)
        assertEquals(TextRunDraft(2, 5, emphasis), inserted.after.run(1))
        val deleted = assertNotNull(TextReplacementRules.plan(current, request(current,
            selection = TextSelectionDraft(2, 4), replacement = "")).plan)
        assertEquals("中Ae\u0301", deleted.after.text)
        assertEquals(1, deleted.after.runCount)
        assertEquals(TextRunDraft(0, 4, style), deleted.after.run(0))
        assertEquals(text, current.text)
        assertEquals(spans, listOf(current.run(0), current.run(1), current.run(2)))
    }

    @Test fun unconfirmedCompositionAndInvalidSelectionNeverProducePlans() {
        val current = payload()
        val unconfirmed = TextReplacementRules.plan(current, request(current, confirmed = false))
        assertNull(unconfirmed.plan); assertEquals(TextReplacementFailure.UNCONFIRMED_COMPOSITION, unconfirmed.failure)
        for (selection in listOf(TextSelectionDraft(2, 3), TextSelectionDraft(3, 2),
            TextSelectionDraft(-1, 2), TextSelectionDraft(0, 7))) {
            val result = TextReplacementRules.plan(current, request(current, selection = selection))
            assertNull(result.plan); assertEquals(TextReplacementFailure.INVALID_SELECTION, result.failure)
        }
        val invalidOperation = TextReplacementRules.plan(current, request(current, operation = CommandId("\uD800")))
        assertNull(invalidOperation.plan); assertEquals(TextReplacementFailure.INVALID_OPERATION, invalidOperation.failure)
        assertEquals(text, current.text); assertEquals(3, current.runCount)
    }

    @Test fun invalidContentAndExactBudgetsUseOriginalAdmissionFailures() {
        val current = payload()
        val invalidInsertion = TextReplacementRules.plan(current, request(current, insertionStyle = emphasis.copy(size = 0.0)))
        assertNull(invalidInsertion.plan); assertEquals(TextReplacementFailure.CONTENT_REJECTED, invalidInsertion.failure)
        assertEquals(TextPayloadFailure.STYLE, invalidInsertion.contentFailure)
        val invalidDefault = TextReplacementRules.plan(current, request(current, defaultStyle = style.copy(size = Double.NaN)))
        assertEquals(TextPayloadFailure.STYLE, invalidDefault.contentFailure)
        assertEquals(TextPayloadFailure.BOX, TextReplacementRules.plan(current, request(current, width = 0.0)).contentFailure)
        assertEquals(TextPayloadFailure.UNICODE, TextReplacementRules.plan(current, request(current, replacement = "\uD800")).contentFailure)
        assertEquals(TextPayloadFailure.BUDGET, TextReplacementRules.plan(current,
            request(current, limit = TextPayloadBudget(-1, 2, 3))).contentFailure)
        val candidate = assertNotNull(TextReplacementRules.plan(current, request(current)).plan)
        val charge = candidate.after.logicalByteCharge
        assertNotNull(TextReplacementRules.plan(current,
            request(current, limit = TextPayloadBudget(100, 20, charge))).plan)
        val small = TextReplacementRules.plan(current, request(current, limit = TextPayloadBudget(100, 20, charge - 1)))
        assertNull(small.plan); assertEquals(TextPayloadFailure.CAPACITY, small.contentFailure)
        assertEquals(text, current.text)
    }

    @Test fun noChangeIsExplicitAndEmptyTextUsesConfirmedInsertionStyle() {
        val current = payload()
        val noChange = TextReplacementRules.plan(current, request(current,
            selection = TextSelectionDraft(2, 2), replacement = ""))
        assertNull(noChange.plan); assertEquals(TextReplacementFailure.NO_CHANGE, noChange.failure)
        val empty = payload("", emptyList())
        val inserted = assertNotNull(TextReplacementRules.plan(empty, request(empty,
            selection = TextSelectionDraft(0, 0), replacement = "中", defaultStyle = emphasis)).plan)
        assertEquals("中", inserted.after.text)
        assertEquals(1, inserted.after.runCount)
        assertEquals(TextRunDraft(0, 1, emphasis), inserted.after.run(0))
        assertEquals(emphasis, inserted.after.defaultStyle)
        assertEquals(0, empty.runCount); assertEquals("", empty.text)
    }

    @Test fun revalidationChecksPayloadIdentityOperationAndCompleteAfterContent() {
        val current = payload()
        val candidate = assertNotNull(TextReplacementRules.plan(current, request(current)).plan)
        val accepted = TextReplacementRules.revalidate(current, candidate, CommandId("edit"))
        val acceptedPlan = assertNotNull(accepted.plan)
        assertSame(current, acceptedPlan.before)
        assertEquals(candidate.after.text, acceptedPlan.after.text)
        assertEquals(candidate.after.logicalByteCharge, acceptedPlan.after.logicalByteCharge)
        val cancelled = TextReplacementRules.revalidate(current, candidate, CommandId("other"))
        assertNull(cancelled.plan); assertEquals(TextReplacementFailure.CANCELLED, cancelled.failure)
        val otherCurrent = payload()
        assertNotSame(current, otherCurrent)
        assertEquals(TextReplacementFailure.STALE_PAYLOAD,
            TextReplacementRules.revalidate(otherCurrent, candidate, CommandId("edit")).failure)
        val otherAfter = assertNotNull(TextPayloadDraft.create(1, "different", 120.0, 80.0, style,
            listOf(TextRunDraft(0, 9, style)), budget).payload)
        for (changed in listOf(candidate.copy(after = otherAfter),
            candidate.copy(request = candidate.request.copy(replacement = "tampered")))) {
            val result = TextReplacementRules.revalidate(current, changed, CommandId("edit"))
            assertNull(result.plan); assertEquals(TextReplacementFailure.STALE_PLAN, result.failure)
        }
        assertEquals(text, current.text); assertEquals(candidate.after.text, "中A新😀e\u0301")
    }
}
