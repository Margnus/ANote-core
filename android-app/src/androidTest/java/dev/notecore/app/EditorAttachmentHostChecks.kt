package dev.notecore.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import dev.notecore.document.*
import java.io.File

/** Original Store/worker plus real Activity callbacks; isolated cache files, no OS input oracle. */
internal class EditorAttachmentHostChecks(private val test: Instrumentation, private val host: Activity) {
    private fun main(action: () -> Unit) = test.runOnMainSync(action)
    private fun eventually(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 10000
        var done = false
        while (!done && SystemClock.uptimeMillis() < end) {
            main { done = condition() }; if (!done) SystemClock.sleep(20)
        }
        check(done) { message }
    }
    fun verify(): List<String> {
        val root = File(host.cacheDir, "editor-attachments-${java.util.UUID.randomUUID()}")
        check(root.mkdirs())
        val owners = mutableListOf<NoteStore>()
        fun owner(path: File) = NoteStore.isolated(host, path).also { owners.add(it) }
        try {
            lateinit var store: NoteStore; lateinit var note: PagedPreviewNotebook
            val directory = File(root, "shared")
            lateinit var expected: String
            lateinit var documentId: String
            var saved = 0
            main {
                store = owner(directory); note = checkNotNull(store.createForEditor())
                check(store.attachEditor(note, "A") && store.attachEditor(note, "B"))
                check(store.attachEditor(note, "A") && store.editorCount == 2)
                check(store.beginOwnerRetirement() == StoreRetirementRequest.WORK_PENDING)
                check(!store.releaseActive(note))
                check(store.createForEditor() == null)
                var refused = false
                store.open("different") { value, error -> check(value == null && error != null); refused = true }
                check(refused && store.activeDocumentId == note.documentId)
                check(store.adoptForEditor(OpenedDocument(note.snapshot(), false)).notebook === note)
                check(note.beginStroke(1.0, 2.0, .5f, 1)) // B is still writing when A leaves.
                check(store.leaveEditor(note, "A") == EditorDeparture.DETACHED)
                check(note.hasActiveStroke && store.editorCount == 1)
                check(store.leaveEditor(note, "A") == EditorDeparture.STALE)
                check(note.endStroke(3.0, 4.0, .5f, 2))
                check(note.rename("still original authority"))
                check(store.save(note.captureRecovery()) { _, error -> check(error == null); saved++ })
                check(store.leaveEditor(note, "B") == EditorDeparture.DOCUMENT_CLOSED)
                expected = note.capture().encode()
                documentId = note.documentId
                check(store.editorCount == 0 && store.activeDocumentId == null)
            }
            eventually("Last editor's accepted root/receiver did not complete") { store.saveStates().isEmpty() && saved == 1 }
            check(File(directory, "$documentId.ncp").readText(Charsets.UTF_8) == expected)
            var reopened = false
            main { store.open(note.documentId) { opened, error ->
                check(error == null); note = checkNotNull(store.adoptForEditor(checkNotNull(opened)).notebook)
                check(note.capture().encode() == expected); reopened = true
            } }
            eventually("Shared editor root did not reopen") { reopened }
            main {
                for (i in 0..7) check(store.attachEditor(note, "window-$i"))
                check(!store.attachEditor(note, "window-8") && store.editorCount == 8)
                check(!store.attachEditor(note, "") && !store.attachEditor(note, "x".repeat(65)))
                store.forgetEditor(note, "window-0") // Simulates the old Activity destruction.
                check(store.attachEditor(note, "replacement") && store.editorCount == 8)
                for (i in 1..7) store.forgetEditor(note, "window-$i")
                store.forgetEditor(note, "replacement")
                check(store.editorCount == 0 && store.activeDocumentId == note.documentId)
                check(note.beginStroke(5.0, 6.0, .5f, 3)); check(note.endStroke(7.0, 8.0, .5f, 4))
                // No endpoints remain: creating a different document first admits the original root.
                val next = checkNotNull(store.createForEditor())
                check(next !== note && next.documentId != note.documentId)
            }
            verifyPending(owner = ::owner, directory = File(root, "pending"))
            verifyActivities(owner = ::owner, directory = File(root, "activities"))
            for (entry in owners) {
                main { check(entry.editorCount == 0); check(entry.beginOwnerRetirement() == StoreRetirementRequest.STARTED) }
                eventually("Editor owner did not drain original writes and stop") { entry.ownerRetirementStatus().phase == StoreOwnerPhase.RETIRED }
            }
            check(PreviewRecoveryCodec.decode(File(directory, "$documentId.ncp").readText(Charsets.UTF_8)).root.scene.objectCount == 2)
            return listOf("shared_editor_detach_capacity_and_reopen", "pending_editor_failure_retry_original_recovery", "actual_activity_shared_editor_departure")
        } finally {
            main { owners.forEach { it.stopTestBackend() } }
            root.deleteRecursively()
        }
    }
    private fun verifyPending(owner: (File) -> NoteStore, directory: File) {
        directory.writeText("blocked directory", Charsets.UTF_8)
        val base = "NCPREVIEW\t2\nD\tpending-editor\tpending\t1\t0\t0\nG\tpage\tlayer\t595.0\t842.0\tBLANK\t0\nEND\n"
        val ink = "NCPREVIEW\t2\nD\tpending-editor\t\t1\t1\t0\nG\tpage\tlayer\t595.0\t842.0\tBLANK\t0\nS\treused-ink-2\tlayer\t1\tMONOLINE\t1\t-14339513\t2.5\t3\t1.0\t0.0\t0.0\t1.0\t0.0\t0.0\nP\t1.25\t2.5\t0.125\t7\nP\t3.0\t4.0\t0.625\t9\nP\t8.0\t6.0\t1.0\t10\nEND\n"
        val operation = "reused-op-1".map { it.code.toString(16).padStart(4, '0') }.joinToString("")
        val checkpoint = PreviewRecoveryCodec.decode("NCRECOVERY\t1\nD\tpending-editor\tpending\t1\t0\t0\n${base.length}\t${ink.length}\t$operation\n" + base + ink + "RECOVERY-END\n")
        lateinit var store: NoteStore; lateinit var note: PagedPreviewNotebook
        main {
            store = owner(directory)
            note = checkNotNull(PagedPreviewNotebook.openRecovery(checkpoint, "consumer", DocumentBudget(historyBytes = 0),
                ownerCheck = { check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) }).notebook)
            check(store.attachEditor(note, "pending-A") && store.attachEditor(note, "pending-B"))
            check(store.leaveEditor(note, "pending-A") == EditorDeparture.DETACHED)
            check(store.leaveEditor(note, "pending-B") == EditorDeparture.RETRY_REQUIRED)
            check(note.hasPendingStroke && store.editorCount == 1 && note.captureRecovery().sameContent(checkpoint))
        }
        eventually("Failed recovery was not held by original ledger") { store.saveStates().single().retryRequired }
        main { check(store.editorPresentation(note).phase == EditorSavePhase.RETRY_REQUIRED) }
        check(directory.delete()); check(directory.mkdir())
        main { check(store.retryRetainedWrites()) }
        eventually("Repaired recovery did not complete original write") { store.saveStates().isEmpty() }
        main { check(store.editorPresentation(note).phase == EditorSavePhase.RECOVERY_SAVED) }
        val file = File(directory, "pending-editor.ncp")
        check(file.readText(Charsets.UTF_8) == checkpoint.encode())
        main {
            check(store.leaveEditor(note, "pending-B") == EditorDeparture.DOCUMENT_CLOSED)
            check(store.editorCount == 0 && store.activeDocumentId == null)
            check(store.editorPresentation(note).phase == EditorSavePhase.CLOSED)
        }
        var read = false
        main { store.open("pending-editor") { opened, error ->
            check(error == null); note = checkNotNull(store.adoptForEditor(checkNotNull(opened)).notebook)
            check(note.hasPendingStroke && note.captureRecovery().sameContent(checkpoint))
            check(note.retryPendingStroke() && note.retryPendingStroke())
            check(note.totalStrokeCount == 1 && note.pointCount(0) == 3)
            check(store.attachEditor(note, "resumed"))
            check(store.leaveEditor(note, "resumed") == EditorDeparture.DOCUMENT_CLOSED)
            read = true
        } }
        eventually("Reopened pending content was not saved as a single command") { read && store.saveStates().isEmpty() }
        check(PreviewRecoveryCodec.decode(file.readText(Charsets.UTF_8)).let { it.pending == null && it.root.scene.objectCount == 1 })
    }
    private fun verifyActivities(owner: (File) -> NoteStore, directory: File) {
        val singleton = NoteStore::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val noteField = MainActivity::class.java.getDeclaredField("notebook").apply { isAccessible = true }
        val activities = mutableListOf<MainActivity>()
        lateinit var store: NoteStore; lateinit var note: PagedPreviewNotebook
        lateinit var documentId: String
        var previous: Any? = null
        fun launch(): MainActivity = (test.startActivitySync(Intent(host, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) as MainActivity).also { activities.add(it) }
        fun back(activity: MainActivity) {
            fun find(view: android.view.View): android.widget.Button? {
                if (view is android.widget.Button && view.text.toString() == "返回") return view
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                return null
            }
            check(checkNotNull(find(activity.window.decorView)).performClick())
        }
        main {
            check(noteField.get(host) == null) // Never replace a real user editor.
            previous = singleton.get(null)
            store = owner(directory); note = checkNotNull(store.createForEditor())
            documentId = note.documentId
            // Test-only dependency substitution; normal onCreate/get/open/editor paths run unchanged.
            singleton.set(null, store)
        }
        try {
            val a = launch(); val b = launch()
            main {
                check(store.editorCount == 2 && noteField.get(a) === note && noteField.get(b) === note)
                back(a)
                check(noteField.get(a) == null && noteField.get(b) === note && store.editorCount == 1)
                check(note.beginStroke(1.0, 2.0, .5f, 1)); check(note.endStroke(3.0, 4.0, .5f, 2))
            }
            lateinit var root: DocumentRoot
            main { root = note.snapshot() }
            val monitor = test.addMonitor(MainActivity::class.java.name, null, false)
            main { b.recreate() }
            val recreated = try {
                test.waitForMonitorWithTimeout(monitor, 10000) as? MainActivity ?: error("Recreated MainActivity missing")
            } finally { test.removeMonitor(monitor) }
            activities.add(recreated); test.waitForIdleSync()
            main {
                check(recreated !== b && noteField.get(recreated) === note && note.snapshot() === root)
                check(store.editorCount == 1 && note.totalStrokeCount == 1)
                back(recreated)
                check(noteField.get(recreated) == null && store.editorCount == 0 && store.activeDocumentId == null)
            }
            eventually("Real Activity departure did not save remaining editor's command") { store.saveStates().isEmpty() }
            check(PreviewRecoveryCodec.decode(File(directory, "$documentId.ncp").readText(Charsets.UTF_8)).root.scene.objectCount == 1)
        } finally {
            main { activities.forEach { if (!it.isDestroyed) it.finish() } }
            test.waitForIdleSync()
            try { eventually("Test Activities did not finish") { activities.all { it.isDestroyed } } }
            finally { main { singleton.set(null, previous) } }
        }
    }
}
