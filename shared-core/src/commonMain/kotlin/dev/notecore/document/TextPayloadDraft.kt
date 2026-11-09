package dev.notecore.document

/** Internal format candidate, not a scene object, editor, resource resolver or persisted schema. */
internal data class TextStyleDraft(val size: Double, val argb: Int, val bold: Boolean, val fontRequest: String?)
internal data class TextRunDraft(val start: Int, val end: Int, val style: TextStyleDraft)
internal data class TextPayloadBudget(val utf16Units: Int, val runs: Int, val logicalBytes: Long)
internal enum class TextPayloadFailure { VERSION, BUDGET, CAPACITY, UNICODE, BOX, STYLE, RUNS }
internal data class TextPayloadResult(val payload: TextPayloadDraft? = null, val failure: TextPayloadFailure? = null)

/** Owns its run collection. Offsets are UTF-16 scalar boundaries, not grapheme/glyph indices. */
internal class TextPayloadDraft private constructor(
    val text: String, val width: Double, val height: Double, val defaultStyle: TextStyleDraft,
    private val entries: List<TextRunDraft>, val logicalByteCharge: Long,
) {
    val runCount: Int get() = entries.size
    fun run(index: Int): TextRunDraft = entries[index]

    companion object {
        private fun boundary(text: String, offset: Int) = offset in 0..text.length &&
            !(offset > 0 && offset < text.length && text[offset - 1].isHighSurrogate() && text[offset].isLowSurrogate())

        private fun valid(style: TextStyleDraft): Boolean = style.size.isFinite() && style.size > 0 &&
            (style.fontRequest == null || (style.fontRequest.isNotBlank() && style.fontRequest.length <= 128 &&
                style.fontRequest.hasValidUnicode()))

        private fun styleBytes(style: TextStyleDraft) = 48L + (style.fontRequest?.length ?: 0).toLong() * 2

        /** Caller supplies confirmed text and a stable list; this validator cannot identify IME composition. */
        fun create(version: Int, text: String, width: Double, height: Double, defaultStyle: TextStyleDraft,
                   runs: List<TextRunDraft>, budget: TextPayloadBudget): TextPayloadResult {
            fun denied(failure: TextPayloadFailure) = TextPayloadResult(failure = failure)
            if (version != 1) return denied(TextPayloadFailure.VERSION)
            if (budget.utf16Units < 0 || budget.runs < 0 || budget.logicalBytes < 0) return denied(TextPayloadFailure.BUDGET)
            if (text.length > budget.utf16Units || runs.size > budget.runs) return denied(TextPayloadFailure.CAPACITY)
            if (!text.hasValidUnicode()) return denied(TextPayloadFailure.UNICODE)
            if (!width.isFinite() || !height.isFinite() || width <= 0 || height <= 0) return denied(TextPayloadFailure.BOX)
            if (!valid(defaultStyle)) return denied(TextPayloadFailure.STYLE)
            // Full coverage has no implicit platform-style gaps. Empty text keeps only its insertion style.
            if (text.isEmpty() != runs.isEmpty()) return denied(TextPayloadFailure.RUNS)
            var end = 0
            var charge = 128L + text.length.toLong() * 2 + styleBytes(defaultStyle)
            for (run in runs) {
                if (run.start != end || run.end <= run.start || !boundary(text, run.start) || !boundary(text, run.end)) {
                    return denied(TextPayloadFailure.RUNS)
                }
                if (!valid(run.style)) return denied(TextPayloadFailure.STYLE)
                charge += 32L + styleBytes(run.style)
                end = run.end
            }
            if (end != text.length) return denied(TextPayloadFailure.RUNS)
            if (charge > budget.logicalBytes) return denied(TextPayloadFailure.CAPACITY)
            // Copy only after validation/admission; immutable run/style/String values can be shared.
            return TextPayloadResult(TextPayloadDraft(text, width, height, defaultStyle, runs.toList(), charge))
        }
    }
}
