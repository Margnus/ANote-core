package dev.notecore.app

import android.app.Activity
import android.app.Instrumentation
import android.os.SystemClock
import dev.notecore.document.*
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Original main-owner Store and real file worker; no original note or OS UI is modified. */
internal class OwnerRetirementHostChecks(private val test: Instrumentation, private val host: Activity) {
    private fun main(action: () -> Unit) = test.runOnMainSync(action)
    private fun eventually(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        var done = false
        while (!done && SystemClock.uptimeMillis() < deadline) {
            main { done = condition() }
            if (!done) SystemClock.sleep(20)
        }
        check(done) { message }
    }
    private fun draw(note: PagedPreviewNotebook) {
        check(note.beginStroke(1.0, 2.0, .5f, 1)); check(note.endStroke(3.0, 4.0, .5f, 2))
    }
    fun verify(): String {
        val root = File(host.cacheDir, "owner-retirement-${java.util.UUID.randomUUID()}")
        check(root.mkdirs())
        val stores = mutableListOf<NoteStore>()
        val checks = mutableListOf<String>()
        fun store(path: File): NoteStore = NoteStore.isolated(host, path).also { stores.add(it) }
        try {
            run {
                lateinit var original: NoteStore
                main { original = NoteStore.get(host) }
                check(runCatching { NoteStore.isolated(host, File(root, "wrong-owner")) }.exceptionOrNull() is IllegalStateException)
                check(runCatching { NoteStore.get(host) }.exceptionOrNull() is IllegalStateException)
                check(!File(root, "wrong-owner").exists())
                main { check(NoteStore.get(host) === original) }
                checks.add("wrong_thread_store_creation_and_lookup_refused")
            }
            main {
                val before = Thread.getAllStackTraces().keys.toSet()
                check(runCatching {
                    NoteStore.isolated(host, File(root, "bad-budget"), exportPickerByteLimit = -1)
                }.exceptionOrNull() is IllegalArgumentException)
                check(Thread.getAllStackTraces().keys.none { it !in before && it.name == "NoteCore-preview-storage" })
                check(!File(root, "bad-budget").exists())
                checks.add("invalid_store_configuration_does_not_start_worker")
            }
            run {
                lateinit var owner: NoteStore; lateinit var note: PagedPreviewNotebook
                main {
                    owner=store(File(root,"unadmitted")); note=owner.create(); draw(note)
                    val before=note.snapshot(); val status=note.authorityStatus()
                    check(!owner.releaseActive(note)) { "Unadmitted content was retired" }
                    check(owner.activeDocumentId==note.documentId && note.snapshot()===before && note.authorityStatus()==status)
                    check(note.rename("Still owned"))
                    check(owner.save(note.capture()) { _,_ -> })
                    check(note.beginStroke(5.0,6.0,.5f,3))
                    check(!owner.releaseActive(note) && note.hasActiveStroke)
                    note.cancelStroke() // Explicit fixture cancellation; release itself must preserve accepted input.
                    check(owner.releaseActive(note)); check(owner.activeDocumentId==null)
                }
                eventually("Admitted content did not reach disk") { owner.saveStates().isEmpty() }
                checks.add("unadmitted_release_refuses_before_retiring_authority")
            }
            run {
                val directory = File(root, "accepted")
                lateinit var owner: NoteStore; lateinit var note: PagedPreviewNotebook
                lateinit var snapshot: PagedPreviewSnapshot
                main { owner = store(directory); note = owner.create(); draw(note); snapshot = note.capture() }
                check(runCatching { owner.beginOwnerRetirement() }.exceptionOrNull() is IllegalStateException)
                main { check(owner.ownerRetirementStatus().phase == StoreOwnerPhase.ACTIVE) }
                checks.add("wrong_thread_retirement_rejected")
                var callbacks = 0
                main {
                    check(owner.beginOwnerRetirement { state ->
                        check(state.phase == StoreOwnerPhase.RETIRED && state.workerStopped)
                        check(state.pendingWrites == 0 && state.renderBytes == 0L); callbacks++
                        error("advisory completion failure after real retirement")
                    } == StoreRetirementRequest.STARTED)
                    check(owner.beginOwnerRetirement() == StoreRetirementRequest.ALREADY_RETIRING)
                    check(owner.activeDocumentId == null)
                    val retired = note.authorityStatus()
                    check(retired.journalByteCharge == 0L && retired.historyByteCharge == 0L && retired.identityByteCharge == 0L)
                    check(retired.operationCount == 0 && retired.undoCount == 0 && retired.redoCount == 0)
                    check(note.snapshot() === snapshot.root && retired.liveByteCharge == snapshot.retainedByteCharge)
                    var refused = 0
                    check(!owner.save(snapshot) { _, error -> check(error != null); refused++ })
                    check(refused == 1)
                    check(owner.beginExportPicker(snapshot).disposition == ExportPickerDisposition.OWNER_CLOSED)
                    check(runCatching { owner.create() }.exceptionOrNull() is IllegalStateException)
                }
                eventually("Accepted root did not finish before worker stop") { callbacks == 1 && owner.ownerRetirementStatus().phase == StoreOwnerPhase.RETIRED }
                main {
                    check(owner.beginOwnerRetirement { callbacks++ } == StoreRetirementRequest.RETIRED)
                    check(callbacks == 1 && !owner.retryRetainedWrites())
                }
                check(File(directory, "${snapshot.documentId}.ncp").readText(Charsets.UTF_8) == snapshot.encode())
                checks.add("accepted_root_saved_before_terminal_worker_stop")
            }
            run {
                val directory = File(root, "failed"); directory.writeText("blocked", Charsets.UTF_8)
                lateinit var owner: NoteStore; lateinit var snapshot: PagedPreviewSnapshot
                var completed = false
                main {
                    owner = store(directory); val note = owner.create(); draw(note); snapshot = note.capture()
                    check(owner.beginOwnerRetirement { completed = true } == StoreRetirementRequest.STARTED)
                    val retired = note.authorityStatus()
                    check(retired.journalByteCharge == 0L && retired.historyByteCharge == 0L && retired.identityByteCharge == 0L)
                    check(note.snapshot() === snapshot.root && note.capture().encode() == snapshot.encode())
                }
                eventually("Failed root was not retained by original ledger") { owner.ownerRetirementStatus().retryRequired == 1 }
                main { check(!completed && !owner.ownerRetirementStatus().workerStopped && owner.ownerRetirementStatus().pendingWrites == 1) }
                check(directory.delete()); check(directory.mkdir())
                main { check(owner.retryRetainedWrites()) }
                eventually("Repaired root did not retire original worker") { completed && owner.ownerRetirementStatus().workerStopped }
                check(File(directory, "${snapshot.documentId}.ncp").readText(Charsets.UTF_8) == snapshot.encode())
                checks.add("failed_root_retained_until_explicit_retry")
            }
            run {
                val directory = File(root, "refusal")
                lateinit var owner: NoteStore; lateinit var note: PagedPreviewNotebook
                lateinit var snapshot: PagedPreviewSnapshot
                var saved = false
                main {
                    owner = store(directory); note = owner.create()
                    check(note.beginStroke(1.0, 2.0, .5f, 1))
                    check(owner.beginOwnerRetirement() == StoreRetirementRequest.INPUT_PENDING && note.hasActiveStroke)
                    check(note.endStroke(3.0, 4.0, .5f, 2)); snapshot = note.capture()
                    val picker = owner.beginExportPicker(snapshot)
                    check(picker.disposition == ExportPickerDisposition.READY)
                    check(owner.beginOwnerRetirement() == StoreRetirementRequest.TRANSFER_PENDING)
                    check(owner.exportReservedByteCharge == snapshot.retainedByteCharge)
                    check(owner.takeExportPicker(checkNotNull(picker.requestCode)) === snapshot)
                    check(owner.save(snapshot) { _, error -> check(error == null); saved = true })
                }
                eventually("Refusal witness did not save accepted input") { saved }
                checks.add("active_input_and_picker_refuse_without_loss")
                var read = false
                main {
                    owner.releaseActive(note)
                    owner.open(snapshot.documentId) { opened, error ->
                        check(error == null); note = owner.adopt(checkNotNull(opened))
                        check(owner.beginOwnerRetirement() == StoreRetirementRequest.WORK_PENDING)
                        check(!owner.retryRetainedWrites()); read = true
                    }
                    check(owner.beginOwnerRetirement() == StoreRetirementRequest.WORK_PENDING)
                }
                eventually("Read delivery was not retained until callback returned") { read }
                var completed = false
                main { check(owner.beginOwnerRetirement { completed = true } == StoreRetirementRequest.STARTED) }
                eventually("Read refusal did not leave owner usable for later retirement") { completed }
                check(File(directory, "${snapshot.documentId}.ncp").readText(Charsets.UTF_8) == snapshot.encode())
                checks.add("pending_read_and_delivery_refuse_retirement")
            }
            run {
                val directory = File(root, "full"); directory.writeText("blocked", Charsets.UTF_8)
                lateinit var owner: NoteStore
                val accepted = mutableMapOf<String, String>()
                main {
                    owner = store(directory)
                    repeat(8) {
                        val note = owner.create(); val snapshot = note.capture()
                        accepted[note.documentId] = snapshot.encode()
                        check(owner.save(snapshot) { _, _ -> }); owner.releaseActive(note)
                    }
                    val ninth = owner.create(); accepted[ninth.documentId] = ninth.capture().encode()
                    check(owner.beginOwnerRetirement() == StoreRetirementRequest.SAVE_REJECTED)
                    check(owner.activeDocumentId == ninth.documentId && owner.ownerRetirementStatus().phase == StoreOwnerPhase.ACTIVE)
                }
                eventually("Eight failed captures did not remain retryable") { owner.ownerRetirementStatus().retryRequired == 8 }
                check(directory.delete()); check(directory.mkdir())
                main { check(owner.retryRetainedWrites()) }
                eventually("Retry did not free ledger slots") { owner.ownerRetirementStatus().pendingWrites == 0 }
                var completed = false
                main { check(owner.beginOwnerRetirement { completed = true } == StoreRetirementRequest.STARTED) }
                eventually("Ninth capture did not finish before retirement") { completed && owner.ownerRetirementStatus().workerStopped }
                accepted.forEach { (id, encoded) -> check(File(directory, "$id.ncp").readText(Charsets.UTF_8) == encoded) }
                checks.add("full_ledger_retry_frees_slot_before_retirement")
            }
            return JSONObject().put("checks", JSONArray(checks)).put("original_note_files_used", false)
                .put("abrupt_owner_or_thread_loss_verified", false).put("gc_timing_verified", false).toString()
        } finally {
            main { stores.forEach { it.stopTestBackend() } }
            root.deleteRecursively()
        }
    }
}
