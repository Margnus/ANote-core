package dev.notecore.app

import dev.notecore.canvas.prototype.*
import dev.notecore.document.*
import org.json.JSONArray
import org.json.JSONObject

/** Original notebook on ART. Synthetic in-memory roots; ordinary menu UI is a separate gate. */
internal object PreviewEditHostChecks {
    fun verify(): String {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        fun note(budget: DocumentBudget = DocumentBudget()) = PagedPreviewNotebook(DocumentRoot.empty(
            SceneId("edit-host"), "before", PageDescriptor(PageId("page"), LayerId("layer"), 595.0, 842.0)), "edit-host", budget)
        val n = note(); val before = n.snapshot()
        check(n.beginStroke(1.0, 2.0, .5f, 1))
        for (request in listOf(PreviewEdit.SetPaper(Double.NaN, 500.0, PaperKind.GRID), PreviewEdit.Rename("\uD800"), PreviewEdit.SelectPage(""))) {
            val result = n.edit(request)
            check(!result.accepted && result.failure?.kind == PreviewFailureKind.INVALID_ARGUMENT)
            check(n.hasActiveStroke && n.snapshot() === before && result.rootRevision == 0L)
        }
        check(n.edit(PreviewEdit.Undo).failure?.kind == PreviewFailureKind.HISTORY_EMPTY && n.hasActiveStroke)
        check(n.endStroke(3.0, 4.0, .6f, 2) && n.pointCount(0) == 2)
        val samples = n.stroke(0).samples
        check(n.edit(PreviewEdit.Rename("合法😀")).accepted)
        check(n.edit(PreviewEdit.InsertPage).accepted && n.edit(PreviewEdit.SelectPage("page")).accepted)
        check(n.edit(PreviewEdit.CopyPage).accepted && n.stroke(0).samples === samples)
        check(n.edit(PreviewEdit.Undo).accepted && n.edit(PreviewEdit.Redo).accepted)
        check(PagedPreviewCodec.decode(n.capture().encode()).title == "合法😀")
        val bounded = note(DocumentBudget(maxPages = 1)); check(bounded.beginStroke(1.0, 2.0, .5f, 1))
        val rejected = bounded.edit(PreviewEdit.InsertPage)
        check(rejected.failure?.documentReason == DocumentRejection.PAGE_CAPACITY && bounded.hasActiveStroke)
        check(bounded.endStroke(3.0, 4.0, .6f, 2))
        val pending = note(DocumentBudget(journalEntries = 1)); check(pending.edit(PreviewEdit.Rename("filled")).accepted)
        check(!pending.beginStroke(1.0, 2.0, .5f, 1))
        check(pending.lastFailure?.documentReason == DocumentRejection.JOURNAL_CAPACITY)
        check(!pending.hasPendingStroke && pending.retire())
        check(n.retire())
        check(n.edit(PreviewEdit.SelectPage("")).failure?.kind == PreviewFailureKind.CLOSED)
        return JSONObject().put("checks", JSONArray(listOf("invalid_parameters_keep_active_samples", "typed_history_and_original_root_edits",
            "capacity_refusal_keeps_active_input", "admission_and_closed_failures_are_explicit")))
            .put("synthetic_memory_only", true).toString()
    }
}
