package dev.notecore.document

import dev.notecore.canvas.prototype.CommandId

internal data class TextSelectionDraft(val anchor: Int, val focus: Int) {
    val start: Int get() = minOf(anchor, focus)
    val end: Int get() = maxOf(anchor, focus)
}
internal data class TextReplacementRequest(
    val operationId: CommandId,
    val selection: TextSelectionDraft,
    val replacement: String,
    val insertionStyle: TextStyleDraft,
    val defaultStyle: TextStyleDraft,
    val width: Double,
    val height: Double,
    val compositionConfirmed: Boolean,
    val budget: TextPayloadBudget,
)
internal data class TextReplacementPlan(
    val request: TextReplacementRequest,
    val before: TextPayloadDraft,
    val after: TextPayloadDraft,
)
internal enum class TextReplacementFailure {
    INVALID_OPERATION, UNCONFIRMED_COMPOSITION, INVALID_SELECTION, CONTENT_REJECTED,
    NO_CHANGE, CANCELLED, STALE_PAYLOAD, STALE_PLAN,
}
internal data class TextReplacementResult(
    val plan: TextReplacementPlan? = null,
    val failure: TextReplacementFailure? = null,
    val contentFailure: TextPayloadFailure? = null,
)

internal object TextReplacementRules {
    private fun MutableList<TextRunDraft>.append(start: Int, end: Int, style: TextStyleDraft) {
        if (end <= start) return
        val previous = lastOrNull()
        if (previous != null && previous.end == start && previous.style == style) {
            this[lastIndex] = previous.copy(end = end)
        } else {
            add(TextRunDraft(start, end, style))
        }
    }

    private fun samePayload(first: TextPayloadDraft, second: TextPayloadDraft): Boolean {
        if (first.text != second.text || first.width != second.width || first.height != second.height ||
            first.defaultStyle != second.defaultStyle || first.logicalByteCharge != second.logicalByteCharge ||
            first.runCount != second.runCount) return false
        for (index in 0 until first.runCount) if (first.run(index) != second.run(index)) return false
        return true
    }

    fun plan(current: TextPayloadDraft, request: TextReplacementRequest): TextReplacementResult {
        if (!request.operationId.value.hasValidUnicode()) {
            return TextReplacementResult(failure = TextReplacementFailure.INVALID_OPERATION)
        }
        if (!request.compositionConfirmed) {
            return TextReplacementResult(failure = TextReplacementFailure.UNCONFIRMED_COMPOSITION)
        }
        val selection = request.selection
        if (!current.text.isTextBoundary(selection.anchor) || !current.text.isTextBoundary(selection.focus)) {
            return TextReplacementResult(failure = TextReplacementFailure.INVALID_SELECTION)
        }
        if (!TextPayloadDraft.validStyle(request.insertionStyle)) {
            return TextReplacementResult(failure = TextReplacementFailure.CONTENT_REJECTED,
                contentFailure = TextPayloadFailure.STYLE)
        }
        val start = selection.start
        val end = selection.end
        val runs = mutableListOf<TextRunDraft>()
        for (index in 0 until current.runCount) {
            val run = current.run(index)
            if (run.start < start) runs.append(run.start, minOf(run.end, start), run.style)
        }
        if (request.replacement.isNotEmpty()) {
            runs.append(start, start + request.replacement.length, request.insertionStyle)
        }
        val shift = request.replacement.length - (end - start)
        for (index in 0 until current.runCount) {
            val run = current.run(index)
            if (run.end > end) {
                val clippedStart = maxOf(run.start, end)
                runs.append(clippedStart + shift, run.end + shift, run.style)
            }
        }
        val text = current.text.substring(0, start) + request.replacement + current.text.substring(end)
        val created = TextPayloadDraft.create(1, text, request.width, request.height,
            request.defaultStyle, runs, request.budget)
        val after = created.payload ?: return TextReplacementResult(
            failure = TextReplacementFailure.CONTENT_REJECTED, contentFailure = created.failure)
        if (samePayload(current, after)) return TextReplacementResult(failure = TextReplacementFailure.NO_CHANGE)
        return TextReplacementResult(TextReplacementPlan(request, current, after))
    }

    fun revalidate(current: TextPayloadDraft, candidate: TextReplacementPlan,
                   activeOperation: CommandId): TextReplacementResult {
        if (candidate.request.operationId != activeOperation) {
            return TextReplacementResult(failure = TextReplacementFailure.CANCELLED)
        }
        if (current !== candidate.before) return TextReplacementResult(failure = TextReplacementFailure.STALE_PAYLOAD)
        val fresh = plan(current, candidate.request)
        val after = fresh.plan?.after ?: return fresh
        return if (samePayload(after, candidate.after)) fresh else TextReplacementResult(failure = TextReplacementFailure.STALE_PLAN)
    }
}
