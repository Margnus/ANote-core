package dev.notecore.app

import android.app.Activity
import android.app.Instrumentation
import android.os.SystemClock
import dev.notecore.document.*
import dev.notecore.canvas.prototype.*
import java.io.File

/** Original Store, ledger, worker and atomic IO; synthetic files confined to a fresh cache directory. */
internal class PendingRecoveryHostChecks(private val test: Instrumentation, private val host: Activity) {
    private fun main(action: () -> Unit) = test.runOnMainSync(action)
    private fun eventually(message: String, predicate: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 8000
        var done = false
        while (!done && SystemClock.uptimeMillis() < end) { main { done = predicate() }; if (!done) SystemClock.sleep(20) }
        check(done) { message }
    }
    fun verify() {
        val dir = File(host.cacheDir, "pending-recovery-${java.util.UUID.randomUUID()}")
        dir.writeText("blocked directory")
        lateinit var store: NoteStore; lateinit var note: PagedPreviewNotebook
        lateinit var recovery: PagedPreviewSnapshot
        var armed = false; var probing = false; var failed = 0; var oldCallbacks = 0; var completed = false
        main {
            store = NoteStore.isolated(host, dir)
            val root = DocumentRoot.empty(SceneId("pending-recovery"), "pending", PageDescriptor(PageId("page"), LayerId("layer"), 595.0, 842.0))
            note = PagedPreviewNotebook(root, "reused", ownerCheck = {
                check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
                if (armed && !probing) {
                    probing = true; val pending = try { note.hasPendingStroke } finally { probing = false }
                    if (pending) { armed = false; error("Synthetic commit owner unavailable") }
                }
            })
            store.attachActive(note)
            check(store.save(note.capture()) { _, _ -> oldCallbacks++ })
            check(note.beginStroke(1.25, 2.5, .125f, 7)); check(note.appendStroke(3.0, 4.0, .625f, 9))
            armed = true
            try { note.endStroke(8.0, 6.0, 1f, 10); error("Expected owner fault") } catch (e: IllegalStateException) { check(!armed) }
            recovery = note.captureRecovery()
            check(store.save(recovery) { _, error -> check(error != null); failed++ })
            check(!store.releaseActive(note)) // Admission is never an atomic-write receipt.
        }
        try {
            eventually("Recovery failure was not retained") { failed == 1 && store.saveStates().single().retryRequired }
            main { check(oldCallbacks == 0 && note.hasPendingStroke); check(store.saveStates().single().snapshot.sameContent(recovery)) }
            check(dir.delete()); check(dir.mkdir())
            main { check(store.save(note.captureRecovery()) { _, error -> check(error == null); completed = true }) }
            eventually("Recovery retry did not complete") { completed && store.saveStates().isEmpty() }
            val file = File(dir, "${recovery.documentId}.ncp")
            check(file.readText() == recovery.encode())
            main {
                var refused = false
                check(!store.save(note.capture()) { _, error -> check(error != null); refused = true })
                check(refused && store.saveStates().isEmpty() && note.hasPendingStroke)
                check(store.beginExportPicker(note.captureRecovery()).disposition == ExportPickerDisposition.PENDING_RECOVERY)
            }
            check(file.readText() == recovery.encode())
            main { check(store.releaseActive(note)); check(!note.retryPendingStroke()); store.stopTestBackend(); store = NoteStore.isolated(host, dir) }
            var opened = false
            main { store.open(recovery.documentId) { value, error ->
                check(error == null); note = store.adopt(checkNotNull(value)); check(note.hasPendingStroke)
                check(note.pendingStroke() == recovery.pending); check(note.totalStrokeCount == 0); opened = true
            } }
            eventually("New owner did not restore exact pending") { opened }
            completed = false
            main {
                check(note.retryPendingStroke()); check(note.retryPendingStroke()); check(note.totalStrokeCount == 1 && note.changeVersion == 1L)
                check(store.save(note.captureRecovery()) { _, error -> check(error == null); completed = true })
            }
            eventually("Committed replacement did not finish") { completed && store.saveStates().isEmpty() }
            check(file.readText().startsWith("NCPREVIEW\t2\n"))
            check(PreviewRecoveryCodec.decode(file.readText()).let { it.pending == null && it.root.scene.objectCount == 1 })
            main { check(store.releaseActive(note)); store.stopTestBackend(); store = NoteStore.isolated(host, dir) }
            opened = false
            main { store.open(recovery.documentId) { value, error ->
                check(error == null); note = store.adopt(checkNotNull(value)); check(!note.hasPendingStroke && note.totalStrokeCount == 1); opened = true
            } }
            eventually("Canonical replacement did not reopen") { opened }
            val valid = file.readText(); file.writeText(recovery.encode().dropLast(1))
            main { check(store.releaseActive(note)); store.stopTestBackend(); store = NoteStore.isolated(host, dir) }
            var refused = false
            main { store.open(recovery.documentId) { value, error -> check(value == null && error != null); refused = true } }
            eventually("Truncated checkpoint was not refused") { refused }
            check(file.readText() == recovery.encode().dropLast(1)); file.writeText(valid)
        } finally { main { store.stopTestBackend() }; dir.deleteRecursively() }
    }
}
