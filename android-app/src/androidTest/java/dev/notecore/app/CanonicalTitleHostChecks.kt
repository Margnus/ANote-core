package dev.notecore.app

import android.app.Activity
import dev.notecore.canvas.prototype.*
import dev.notecore.document.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Original authority and Android file port; all files are isolated synthetic cache fixtures. */
internal object CanonicalTitleHostChecks {
    fun verify(host: Activity): String {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        val doc = SceneId("title-native")
        val page = PageDescriptor(PageId("page"), LayerId("layer"), 595.0, 842.0)
        fun root(title: String = "before") = DocumentRoot.empty(doc, title, page)
        val owner = MemoryDocumentAuthority(root())
        val directory = File(host.cacheDir, "title-contract-${UUID.randomUUID()}")
        val io = PreviewFileIO(directory, host.contentResolver)
        io.write(PagedPreviewSnapshot.fromRoot(owner.snapshot()))
        val file = File(directory, "title-native.ncp"); val original = file.readBytes()
        val before = owner.snapshot(); val status = owner.status(); val operation = CommandId("retry")
        for (bad in listOf("\uD800", "\uDC00", "bad\uD800tail", "\uD800\uD800")) {
            check((owner.commit(DocumentCommand.Rename(operation, doc, 0, bad)) as DocumentCommit.Rejected).reason == DocumentRejection.INVALID_TITLE)
            check(owner.snapshot() === before && owner.status() == status)
            check(file.readBytes().contentEquals(original) && io.read("title-native").title == "before")
        }
        val title = "中".repeat(118) + "😀"
        check(owner.commit(DocumentCommand.Rename(operation, doc, 0, title)) is DocumentCommit.Accepted)
        val snapshot = PagedPreviewSnapshot.fromRoot(owner.snapshot()); io.write(snapshot)
        check(io.read("title-native").title == title)
        check(file.readBytes().contentEquals(snapshot.encode().encodeToByteArray()))
        val receipt = owner.commit(DocumentCommand.Rename(operation, doc, 0, title)) as DocumentCommit.Accepted
        check(receipt.receipt.rootRevision == 1L)
        check(owner.commit(DocumentCommand.Undo(CommandId("undo"), doc, 1, operation)) is DocumentCommit.Accepted)
        val undone = owner.snapshot(); val undoStatus = owner.status()
        check(owner.commit(DocumentCommand.Rename(CommandId("bad-redo"), doc, 2, "\uDC00")) is DocumentCommit.Rejected)
        check(owner.snapshot() === undone && owner.status() == undoStatus)
        check(owner.commit(DocumentCommand.Redo(CommandId("redo"), doc, 2, operation)) is DocumentCommit.Accepted)
        check(owner.snapshot().title == title)
        var refused = false
        try { MemoryDocumentAuthority(root("\uD800")) } catch (_: IllegalArgumentException) { refused = true }
        check(refused)
        return JSONObject().put("checks", JSONArray(listOf("invalid_title_keeps_root_receipts_and_file", "same_operation_valid_retry_and_utf8_file_reopen",
            "invalid_title_keeps_redo_branch", "invalid_initial_title_refused")))
            .put("synthetic_file_bytes", file.length()).put("unicode_utf16_length", title.length).toString()
    }
}
