package dev.notecore.app

import android.app.Activity
import android.app.Instrumentation
import android.os.SystemClock
import dev.notecore.document.*
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Actual original store/worker/IO; only a uniquely named cache directory is modified. */
internal class StorageRetentionHostChecks(private val test: Instrumentation, private val host: Activity) {
    private fun main(action: () -> Unit) = test.runOnMainSync(action)
    private fun eventually(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8000
        var done = false
        while (!done && SystemClock.uptimeMillis() < deadline) {
            main { done = predicate() }
            if (!done) SystemClock.sleep(20)
        }
        check(done) { message }
    }
    fun verify(): String {
        val directory = File(host.cacheDir, "storage-retention-${java.util.UUID.randomUUID()}")
        val moved = File(directory.path + "-saved")
        lateinit var store: NoteStore
        lateinit var note: PagedPreviewNotebook
        var completed = false
        val checks = mutableListOf<String>()
        main {
            store = NoteStore.isolated(host, directory); note = store.create()
            val original = note.snapshot()
            val refused = PagedPreviewNotebook.open(original, "bad nonce", ownerCheck = { check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) })
            check(refused.notebook == null && refused.failure == PreviewOpenFailure.INVALID_SESSION_NONCE)
            val capacity = PagedPreviewNotebook.open(original, "checked", DocumentBudget(liveBytes = 0))
            check(capacity.notebook == null && capacity.documentReason == DocumentRejection.LIVE_BYTE_CAPACITY)
            check(note.snapshot() === original && store.activeDocumentId == original.id.value)
            checks.add("validated_open_refusal_preserves_original_store_owner")
            check(store.save(note.capture()) { _, error -> check(error == null); completed = true })
        }
        try {
            eventually("Initial write did not complete") { completed && store.saveStates().isEmpty() }
            check(directory.renameTo(moved)); directory.writeText("blocked directory", Charsets.UTF_8)
            main {
                var unchanged = 0
                check(store.save(note.capture()) { _, error -> check(error == null); unchanged++ })
                check(unchanged == 1 && store.saveStates().isEmpty())
                check(note.rename("Changed while storage unavailable"))
            }
            checks.add("saved_unchanged_skips_io")
            var failed = false
            lateinit var changed: PagedPreviewSnapshot
            main {
                changed = note.capture()
                check(store.save(changed) { _, error -> check(error != null); failed = true })
            }
            eventually("Changed root was incorrectly treated as saved") { failed && store.saveStates().single().retryRequired }
            main { check(store.saveStates().single().snapshot.sameContent(changed)) }
            checks.add("changed_revision_failure_retained")
            check(directory.delete()); check(moved.renameTo(directory))
            completed = false
            main { check(store.save(changed) { _, error -> check(error == null); completed = true }) }
            eventually("Repair did not save the retained root") { completed && store.saveStates().isEmpty() }
            check(File(directory, "${changed.documentId}.ncp").readText(Charsets.UTF_8) == changed.encode())
            checks.add("repair_retries_exact_root")
            main {
                val first = note.capture()
                val firstRequest = store.beginExportPicker(first)
                check(firstRequest.disposition == ExportPickerDisposition.READY)
                check(store.exportReservedByteCharge == first.retainedByteCharge)
                check(note.rename("Later export"))
                check(store.beginExportPicker(note.capture()).disposition == ExportPickerDisposition.BUSY)
                check(store.takeExportPicker(checkNotNull(firstRequest.requestCode)) === first); check(store.exportReservedByteCharge == 0L)
                val later = note.capture()
                val nextRequest = store.beginExportPicker(later)
                check(nextRequest.disposition == ExportPickerDisposition.READY)
                check(store.takeExportPicker(checkNotNull(firstRequest.requestCode)) == null)
                check(store.takeExportPicker(checkNotNull(nextRequest.requestCode)) === later)
                check(store.takeExportPicker(nextRequest.requestCode) == null && store.exportReservedByteCharge == 0L)
                // This edit was for export only; retain it through the original save handoff before close.
                completed = false
                check(store.save(later) { _, error -> check(error == null); completed = true })
            }
            checks.add("single_export_snapshot_preserves_original")
            eventually("Post-export edit did not save") { completed && store.saveStates().isEmpty() }
            main { store.releaseActive(note); store.stopTestBackend(); store = NoteStore.isolated(host, directory) }
            var opened = false
            main { store.open(changed.documentId) { value, error ->
                check(error == null && value?.persisted == true)
                note = store.adopt(checkNotNull(value)); opened = true
            } }
            eventually("Saved document did not reopen") { opened }
            main {
                store.stopTestBackend()
                var success = 0
                check(store.save(note.capture()) { _, error -> check(error == null); success++ })
                check(success == 1 && store.saveStates().isEmpty())
                checks.add("reopened_saved_marker_skips_stopped_backend")
                val root = note.snapshot()
                val foreign = PagedPreviewSnapshot.fromRoot(DocumentRoot.compose("Foreign same revision", root.scene, root.pages()))
                var refused = 0
                check(store.save(foreign) { _, error -> check(error != null); refused++ })
                check(refused > 0 && store.saveStates().single().snapshot === foreign)
                check(note.snapshot() === root)
                checks.add("foreign_same_revision_is_not_acknowledged_as_saved")
            }
            return JSONObject().put("checks", JSONArray(checks)).put("original_note_files_used", false)
                .put("system_file_picker_verified", false).toString()
        } finally {
            main { store.stopTestBackend() }
            directory.deleteRecursively(); moved.deleteRecursively()
        }
    }
}
