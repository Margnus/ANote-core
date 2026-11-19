package dev.notecore.app

import android.app.Activity
import android.app.Instrumentation
import android.os.SystemClock
import dev.notecore.document.*
import dev.notecore.canvas.prototype.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

/** Original Store, ledger, worker and atomic IO; synthetic files confined to a fresh cache directory. */
internal class PendingRecoveryHostChecks(private val test: Instrumentation, private val host: Activity) {
    private fun main(action: () -> Unit) = test.runOnMainSync(action)
    private fun eventually(message: String, predicate: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 8000
        var done = false
        while (!done && SystemClock.uptimeMillis() < end) { main { done = predicate() }; if (!done) SystemClock.sleep(20) }
        check(done) { message }
    }
    fun verifyOpenBudget(pending: ByteArray): String {
        val directory = File(host.cacheDir, "recovery-open-budget-${java.util.UUID.randomUUID()}")
        check(directory.mkdir())
        val checkpoint = PreviewRecoveryCodec.decode(pending.toString(Charsets.UTF_8))
        val file = File(directory, "${checkpoint.documentId}.ncp")
        file.writeBytes(pending)
        lateinit var store: NoteStore
        lateinit var old: PagedPreviewNotebook
        lateinit var original: DocumentRoot
        lateinit var twoPages: PagedPreviewSnapshot
        main {
            store = NoteStore.isolated(host, directory, documentBudget = DocumentBudget(maxPages = 1, inputBytes = 2247))
            old = store.create()
            check(old.rename("Existing unsaved document"))
            original = old.snapshot()
            val source = PagedPreviewNotebook.create("two-pages", "Two pages", "source")
            check(source.insertPage())
            twoPages = source.capture()
            check(source.retire())
        }
        val oldFile = File(directory, "${original.id.value}.ncp")
        val pageFile = File(directory, "${twoPages.documentId}.ncp")
        val pageBytes = twoPages.encode().toByteArray(Charsets.UTF_8)
        pageFile.writeBytes(pageBytes)
        try {
            for ((id, expected) in listOf(checkpoint.documentId to PreviewOpenFailure.INPUT_CAPACITY,
                twoPages.documentId to PreviewOpenFailure.DOCUMENT_CAPACITY)) {
                var refused = false
                main {
                    store.open(id) { opened, error ->
                        check(error == null)
                        val adoption = store.adoptForEditor(checkNotNull(opened))
                        check(adoption.notebook == null && adoption.failure == expected)
                        check(store.activeDocumentId == old.documentId && old.snapshot() === original)
                        check(store.saveStates().isEmpty() && store.editorPresentation(old).phase == EditorSavePhase.UNSAVED)
                        refused = true
                    }
                }
                eventually("Budget refusal did not reach the original owner") { refused }
                check(!oldFile.exists() && file.readBytes().contentEquals(pending) && pageFile.readBytes().contentEquals(pageBytes))
            }
            var canonicalSaved = false
            main {
                store.stopTestBackend()
                store = NoteStore.isolated(host, directory, documentBudget = DocumentBudget(inputBytes = 2248))
                store.open(checkpoint.documentId) { opened, error ->
                    check(error == null)
                    val adoption = store.adoptForEditor(checkNotNull(opened))
                    check(adoption.failure == null)
                    val note = checkNotNull(adoption.notebook)
                    check(note.inputReservedByteCharge == 2248L && note.captureRecovery().encode().toByteArray(Charsets.UTF_8).contentEquals(pending))
                    check(note.retryPendingStroke() && note.retryPendingStroke() && note.totalStrokeCount == 1 && note.changeVersion == 2L)
                    AndroidInputHostChecks.checkProcessStroke(note.stroke(0).samples)
                    check(store.save(note.captureRecovery()) { _, failure -> check(failure == null); canonicalSaved = true })
                }
            }
            eventually("Exact-budget recovery did not confirm canonical save") { canonicalSaved && store.saveStates().isEmpty() }
            fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            return JSONObject().put("input_rejected", 2247).put("exact_input_admitted", 2248)
                .put("document_capacity_refused", true).put("prior_owner_preserved", true)
                .put("prior_unsaved_file_absent", true).put("pending_sha256", sha(pending))
                .put("canonical_sha256", sha(file.readBytes())).toString()
        } finally { main { store.stopTestBackend() }; directory.deleteRecursively() }
    }
    fun verifyWorkBudget(): String {
        val directory = File(host.cacheDir, "pending-work-budget-${java.util.UUID.randomUUID()}")
        check(directory.mkdir())
        val file = File(directory, "process-recovery.ncp")
        val occupied = CountDownLatch(1); val releaseOccupancy = CountDownLatch(1)
        val writeEntered = CountDownLatch(1); val releaseWrite = CountDownLatch(1)
        val writes = AtomicInteger()
        lateinit var store: NoteStore; lateinit var worker: BoundedOwnerExecutor
        var cleanupStore: NoteStore? = null
        lateinit var note: PagedPreviewNotebook; lateinit var checkpoint: PagedPreviewSnapshot
        var occupancyAck = false; var pendingAck = 0; var canonicalAck = 0
        val inputBytes = 2248L
        val workLimit = 256L * 1024 * 1024
        try {
            main {
                store = NoteStore.isolated(host, directory, observeWriteBoundary = { boundary ->
                    if (boundary == PreviewWriteBoundary.SYNCED_BEFORE_REPLACE && writes.incrementAndGet() == 1) {
                        writeEntered.countDown()
                        check(releaseWrite.await(12, TimeUnit.SECONDS))
                    }
                })
                cleanupStore = store
                worker = NoteStore::class.java.getDeclaredField("executor").apply { isAccessible = true }
                    .get(store) as BoundedOwnerExecutor
                val root = DocumentRoot.empty(SceneId("process-recovery"), "base",
                    PageDescriptor(PageId("page"), LayerId("layer"), 595.0, 842.0))
                note = PagedPreviewNotebook(root, "process-seed", ownerCheck = worker::checkOwnerThread)
                store.attachActive(note)
                check(note.rename("process checkpoint"))
                checkpoint = AndroidInputHostChecks(host).captureViewPendingForProcess(note)
                check(note.inputReservedByteCharge == inputBytes)
                check(checkpoint.retainedByteCharge == 3038L && checkpoint.encodingWorkByteCharge == 64990L)
            }
            val baseBytes = PagedPreviewSnapshot.fromRoot(checkpoint.root).encode()
            file.writeText(baseBytes, Charsets.UTF_8)
            eventually("Initial owner work did not drain") { worker.status().entries.isEmpty() }
            val needed = checkpoint.encodingWorkByteCharge
            val reservation = workLimit - needed + 1
            main {
                val occupancy = worker.submit(reservation, {
                    occupied.countDown()
                    check(releaseOccupancy.await(12, TimeUnit.SECONDS))
                }) { result ->
                    check(result is WorkResult.Succeeded)
                    occupancyAck = true
                    ResultDisposition.ACKNOWLEDGED
                } as WorkAdmission.Accepted
                check(worker.submit(needed, { error("Over-budget work ran") }) { ResultDisposition.ACKNOWLEDGED } ==
                    WorkAdmission.Rejected(WorkAdmissionFailure.BYTE_CAPACITY))
                check(store.save(checkpoint) { revision, error ->
                    check(revision == checkpoint.version && error == null); pendingAck++
                })
                val waiting = store.saveStates().single()
                check(waiting.snapshot === checkpoint && !waiting.inFlight && !waiting.retryRequired)
                check(worker.status().entries.single().ticket === occupancy.ticket && worker.status().chargedBytes == reservation)
                check(!store.releaseActive(note) && store.beginOwnerRetirement() == StoreRetirementRequest.INPUT_PENDING)
                check(note.snapshot() === checkpoint.root && note.pendingStroke() === checkpoint.pending)
            }
            check(occupied.await(5, TimeUnit.SECONDS))
            check(writes.get() == 0 && file.readText(Charsets.UTF_8) == baseBytes)
            releaseOccupancy.countDown()
            check(writeEntered.await(8, TimeUnit.SECONDS)) { "Worker ACK did not pump the retained checkpoint" }
            main {
                val running = store.saveStates().single()
                check(occupancyAck && pendingAck == 0 && running.snapshot === checkpoint && running.inFlight)
                check(worker.status().entries.size == 1 && worker.status().chargedBytes == needed)
                check(!store.releaseActive(note))
            }
            check(file.readText(Charsets.UTF_8) == baseBytes)
            releaseWrite.countDown()
            eventually("Exact pending write did not confirm") {
                pendingAck == 1 && store.saveStates().isEmpty() && worker.status().chargedBytes == 0L &&
                    store.editorPresentation(note).phase == EditorSavePhase.RECOVERY_SAVED
            }
            val pendingBytes = file.readBytes()
            check(pendingBytes.contentEquals(checkpoint.encode().toByteArray(Charsets.UTF_8)) && writes.get() == 1)
            main {
                check(store.releaseActive(note) && !note.retryPendingStroke())
                check(store.beginOwnerRetirement() == StoreRetirementRequest.STARTED)
            }
            eventually("Original owner did not retire") { store.ownerRetirementStatus().phase == StoreOwnerPhase.RETIRED }
            var opened = false
            main {
                store = NoteStore.isolated(host, directory, documentBudget = DocumentBudget(inputBytes = inputBytes))
                cleanupStore = store
                store.open(checkpoint.documentId) { value, error ->
                    check(error == null)
                    note = store.adopt(checkNotNull(value))
                    check(note.hasPendingStroke && note.inputReservedByteCharge == inputBytes)
                    check(note.captureRecovery().encode().toByteArray(Charsets.UTF_8).contentEquals(pendingBytes))
                    check(note.retryPendingStroke() && note.retryPendingStroke())
                    check(note.totalStrokeCount == 1 && note.changeVersion == 2L && note.inputReservedByteCharge == 0L)
                    AndroidInputHostChecks.checkProcessStroke(note.stroke(0).samples)
                    opened = true
                    check(store.save(note.captureRecovery()) { _, failure -> check(failure == null); canonicalAck++ })
                }
            }
            eventually("New owner did not confirm canonical retry") {
                opened && canonicalAck == 1 && store.saveStates().isEmpty() && store.editorPresentation(note).phase == EditorSavePhase.SAVED
            }
            val canonicalBytes = file.readBytes()
            val canonical = PreviewRecoveryCodec.decode(canonicalBytes.toString(Charsets.UTF_8))
            check(canonical.pending == null && canonical.version == 2L && canonical.root.scene.objectCount == 1)
            main {
                check(store.releaseActive(note))
                check(store.beginOwnerRetirement() == StoreRetirementRequest.STARTED)
            }
            eventually("New owner did not retire") { store.ownerRetirementStatus().phase == StoreOwnerPhase.RETIRED }
            check(directory.deleteRecursively())
            fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            return JSONObject().put("worker_budget", workLimit).put("available_when_blocked", needed - 1)
                .put("encoding_charge", needed).put("retained_charge", checkpoint.retainedByteCharge)
                .put("input_charge", inputBytes).put("pending_sha256", sha(pendingBytes))
                .put("canonical_sha256", sha(canonicalBytes)).put("pending_ack", pendingAck).put("canonical_ack", canonicalAck)
                .put("revision", canonical.version).put("strokes", 1).put("points", 4).toString()
        } finally {
            releaseOccupancy.countDown()
            releaseWrite.countDown()
            main { cleanupStore?.stopTestBackend() }
        }
    }
    fun verify() {
        verifyRecovery(BrushKind.MONOLINE)
        verifyRecovery(BrushKind.HIGHLIGHTER)
    }

    private fun verifyRecovery(kind: BrushKind) {
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
            check(note.rename("pending title")) // Editing history must survive refusal, then retire after confirmed recovery IO.
            check(store.save(note.capture()) { _, _ -> oldCallbacks++ })
            check(note.selectPen(0x7f123456, 2.5, kind))
            check(note.beginStroke(1.25, 2.5, .125f, 7)); check(note.appendStroke(3.0, 4.0, .625f, 9))
            armed = true
            try { note.endStroke(8.0, 6.0, 1f, 10); error("Expected owner fault") } catch (e: IllegalStateException) { check(!armed) }
            recovery = note.captureRecovery()
            val checkpoint = recovery.encode()
            val command = checkNotNull(recovery.pending)
            val stroke = command.command as CanvasCommand.AddStroke
            val charge = 2048L + 2L * (command.id.value.length + command.pageId.value.length +
                stroke.objectId.value.length + stroke.target.layerId.value.length) + 3L * 28
            for (bytes in listOf(0L, charge - 1, charge)) {
                val result = PagedPreviewNotebook.openRecovery(recovery, "consumer", DocumentBudget(inputBytes = bytes),
                    ownerCheck = { check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) })
                if (bytes < charge) check(result.notebook == null && result.failure == PreviewOpenFailure.INPUT_CAPACITY)
                else {
                    val restored = checkNotNull(result.notebook)
                    check(restored.inputReservedByteCharge == charge && restored.pendingStroke() === recovery.pending)
                    check(restored.retryPendingStroke() && restored.retryPendingStroke())
                    check(restored.totalStrokeCount == 1 && restored.inputReservedByteCharge == 0L)
                }
                check(recovery.encode() == checkpoint && note.hasPendingStroke)
            }
            check(note.authorityStatus().journalByteCharge > 0L && note.authorityStatus().historyByteCharge > 0L)
            check(store.save(recovery) { _, error -> check(error != null); failed++ })
            check(!store.releaseActive(note)) // Admission is never an atomic-write receipt.
        }
        try {
            eventually("Recovery failure was not retained") { failed == 1 && store.saveStates().single().retryRequired }
            main {
                check(oldCallbacks == 0 && note.hasPendingStroke); check(store.saveStates().single().snapshot.sameContent(recovery))
                check(note.authorityStatus().journalByteCharge > 0L && note.authorityStatus().historyByteCharge > 0L)
            }
            check(dir.delete()); check(dir.mkdir())
            main { check(store.save(note.captureRecovery()) { _, error -> check(error == null); completed = true }) }
            eventually("Recovery retry did not complete") { completed && store.saveStates().isEmpty() }
            val file = File(dir, "${recovery.documentId}.ncp")
            check(file.readText() == recovery.encode())
            val reading = PreviewRecoveryCodec.read(file.readText(Charsets.UTF_8))
            check(reading.failure == null && checkNotNull(reading.snapshot).sameContent(recovery) && reading.snapshot?.pending != null)
            main {
                val checkpoint = checkNotNull(reading.snapshot)
                val pending = checkNotNull(checkpoint.pending)
                val stroke = pending.command as CanvasCommand.AddStroke
                val charge = 2048L + (pending.id.value.length + pending.pageId.value.length + stroke.objectId.value.length +
                    stroke.target.layerId.value.length) * 2L + stroke.samples.size.toLong() * 28L
                val owner = { check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) }
                val refusedBudget = PagedPreviewNotebook.openRecovery(checkpoint, "budget", DocumentBudget(inputBytes = charge - 1), owner)
                check(refusedBudget.notebook == null && refusedBudget.failure == PreviewOpenFailure.INPUT_CAPACITY)
                check(store.activeDocumentId == note.documentId && note.hasPendingStroke)
                val exact = checkNotNull(PagedPreviewNotebook.openRecovery(checkpoint, "budget", DocumentBudget(inputBytes = charge), owner).notebook)
                check(exact.inputReservedByteCharge == charge && exact.pendingStroke() === pending)
                exact.cancelStroke(); check(exact.inputReservedByteCharge == charge)
                check(exact.retryPendingStroke() && exact.retryPendingStroke() && exact.totalStrokeCount == 1)
                check(exact.stroke(0).samples == stroke.samples && exact.inputReservedByteCharge == 0L && exact.retire())
                var refused = false
                check(!store.save(note.capture()) { _, error -> check(error != null); refused = true })
                check(refused && store.saveStates().isEmpty() && note.hasPendingStroke)
                check(store.beginExportPicker(note.captureRecovery()).disposition == ExportPickerDisposition.PENDING_RECOVERY)
            }
            check(file.readText() == recovery.encode())
            main {
                check(store.releaseActive(note)); check(!note.retryPendingStroke())
                val retired = note.authorityStatus()
                check(retired.journalByteCharge == 0L && retired.historyByteCharge == 0L && retired.identityByteCharge == 0L)
                check(note.snapshot() === recovery.root && note.pendingStroke() === recovery.pending && note.inputReservedByteCharge > 0L)
                check(note.captureRecovery().encode() == recovery.encode())
                store.stopTestBackend(); store = NoteStore.isolated(host, dir)
            }
            var opened = false
            main { store.open(recovery.documentId) { value, error ->
                check(error == null); note = store.adopt(checkNotNull(value)); check(note.hasPendingStroke)
                check(note.pendingStroke() == recovery.pending && note.pendingInk()?.brush?.kind == kind)
                check(note.totalStrokeCount == 0); opened = true
            } }
            eventually("New owner did not restore exact pending") { opened }
            completed = false
            main {
                check(note.retryPendingStroke()); check(note.retryPendingStroke()); check(note.totalStrokeCount == 1 && note.changeVersion == 2L)
                check(store.save(note.captureRecovery()) { _, error -> check(error == null); completed = true })
            }
            eventually("Committed replacement did not finish") { completed && store.saveStates().isEmpty() }
            check(file.readText().startsWith("NCPREVIEW\t2\n"))
            check(PreviewRecoveryCodec.decode(file.readText()).let { it.pending == null && it.root.scene.objectCount == 1 })
            main { check(store.releaseActive(note)); store.stopTestBackend(); store = NoteStore.isolated(host, dir) }
            opened = false
            main { store.open(recovery.documentId) { value, error ->
                check(error == null); note = store.adopt(checkNotNull(value))
                check(!note.hasPendingStroke && note.totalStrokeCount == 1 && note.stroke(0).brush.kind == kind)
                opened = true
            } }
            eventually("Canonical replacement did not reopen") { opened }
            val valid = file.readText()
            val encoded = recovery.encode(); val rows = encoded.split('\n', limit = 4); val frame = rows[2].split('\t')
            val signedOperation = rows[0] + "\n" + rows[1] + "\n" + frame[0] + "\t" + frame[1] + "\t-001" + frame[2].drop(4) + "\n" + rows[3]
            val signedLength = encoded.replace("\n${frame[0]}\t", "\n+${frame[0]}\t")
            main { check(store.releaseActive(note)); store.stopTestBackend(); store = NoteStore.isolated(host, dir) }
            val malformed = listOf(encoded.dropLast(1) to PreviewReadFailure.INVALID_OR_UNSUPPORTED_FORMAT,
                signedOperation to PreviewReadFailure.INVALID_OR_UNSUPPORTED_FORMAT,
                signedLength to PreviewReadFailure.INVALID_OR_UNSUPPORTED_FORMAT)
            val unsupported = if (kind == BrushKind.MONOLINE)
                listOf(encoded.replace("MONOLINE", "PRESSURE") to PreviewReadFailure.UNSUPPORTED_PREVIEW_CONTENT)
            else emptyList()
            for ((corrupt, reason) in malformed + unsupported) {
                val reading = PreviewRecoveryCodec.read(corrupt)
                check(reading.snapshot == null && reading.failure == reason)
                file.writeText(corrupt, Charsets.UTF_8)
                var refused = false
                main { store.open(recovery.documentId) { value, error -> check(value == null && error == (if (reason == PreviewReadFailure.UNSUPPORTED_PREVIEW_CONTENT) LocalReadFailure.UNSUPPORTED_CONTENT.message else LocalReadFailure.INVALID_FORMAT.message)); refused = true } }
                eventually("Malformed checkpoint was not refused") { refused }
                main { check(store.activeDocumentId == null) }
                check(file.readText(Charsets.UTF_8) == corrupt)
            }
            file.writeText(valid, Charsets.UTF_8)
        } finally { main { store.stopTestBackend() }; dir.deleteRecursively() }
    }
    /** Test process remains alive with its original owner/worker until the external driver force-stops it.
     * Separate confirmed writes from visible bytes before owner delivery; no partial-write or power-loss claim.
     */
    fun processCheckpoint(phase: String, scope: String, viewInput: Boolean = false): String {
        check(!viewInput || phase in setOf("process_checkpoint_prepare", "process_checkpoint_resume", "process_checkpoint_verify"))
        val points = if (viewInput) 4 else 3
        check(java.util.UUID.fromString(scope).toString() == scope)
        val case = File(host.cacheDir, "process-recovery-$scope")
        val directory = File(case, "document")
        val document = File(directory, "process-recovery.ncp")
        fun sha(file: File) = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
        fun marker(name: String, note: PagedPreviewNotebook) {
            val data = org.json.JSONObject().put("pid", android.os.Process.myPid()).put("scope", scope)
                .put("document_sha256", sha(document)).put("revision", note.changeVersion)
                .put("stroke_count", note.totalStrokeCount).put("pending", note.hasPendingStroke)
                .put("owner_not_retired", true).put("worker_not_stopped", true)
                .put("view_input", viewInput).put("save_receipt_observed", true)
            File(case, name).writeText(data.toString(), Charsets.UTF_8)
        }
        fun waitForExternalStop(store: NoteStore, note: PagedPreviewNotebook): Nothing {
            val until = SystemClock.uptimeMillis() + 90_000
            while (SystemClock.uptimeMillis() < until) {
                // Keep both original objects strongly reachable and check their live owner before death.
                main {
                    check(store.activeDocumentId == note.documentId && store.saveStates().isEmpty())
                    check(note.hasPendingStroke == (phase == "process_checkpoint_prepare"))
                }
                SystemClock.sleep(100)
            }
            error("External process-stop driver did not arrive")
        }
        if (phase == "process_checkpoint_prepare") {
            check(!case.exists() && case.mkdirs())
            lateinit var store: NoteStore; lateinit var note: PagedPreviewNotebook
            var armed = false; var probing = false; var saved = false
            lateinit var captured: PagedPreviewSnapshot
            main {
                store = NoteStore.isolated(host, directory)
                val root = DocumentRoot.empty(SceneId("process-recovery"), "base",
                    PageDescriptor(PageId("page"), LayerId("layer"), 595.0, 842.0))
                note = PagedPreviewNotebook(root, "process-seed", ownerCheck = {
                    check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
                    if (armed && !probing) {
                        probing = true; val pending = try { note.hasPendingStroke } finally { probing = false }
                        if (pending) { armed = false; error("Synthetic commit owner fault") }
                    }
                })
                store.attachActive(note); check(note.rename("process checkpoint"))
                captured = if (viewInput) {
                    AndroidInputHostChecks(host).captureViewPendingForProcess(note)
                } else {
                    check(note.beginStroke(1.25,2.5,.125f,7) && note.appendStroke(3.0,4.0,.625f,9))
                    armed = true
                    try { note.endStroke(8.0,6.0,1f,10); error("Expected owner fault") }
                    catch (_: IllegalStateException) { check(!armed) }
                    note.captureRecovery()
                }
                check(note.hasPendingStroke && note.totalStrokeCount == 0)
                check(captured.pending === note.pendingStroke() && captured.root === note.snapshot())
                check(store.save(captured) { revision, error -> check(error == null && revision == captured.version); saved = true })
            }
            eventually("Checkpoint write did not complete") { saved && store.saveStates().isEmpty() }
            main {
                check(store.activeDocumentId == note.documentId && note.hasPendingStroke)
                check(note.authorityStatus().historyByteCharge > 0L)
                check(document.readText(Charsets.UTF_8) == captured.encode())
                if (viewInput) check(store.editorPresentation(note).phase == EditorSavePhase.RECOVERY_SAVED)
                marker("checkpoint-ready.json", note)
            }
            waitForExternalStop(store, note)
        }
        check(case.isDirectory && document.isFile)
        lateinit var store: NoteStore; lateinit var note: PagedPreviewNotebook
        var opened = false
        main {
            store = NoteStore.isolated(host, directory)
            store.open("process-recovery") { value, error ->
                check(error == null); note = store.adopt(checkNotNull(value)); opened = true
            }
        }
        eventually("Process checkpoint did not reopen") { opened }
        if (phase == "process_checkpoint_resume") {
            val prior = org.json.JSONObject(File(case, "checkpoint-ready.json").readText(Charsets.UTF_8))
            check(prior.getInt("pid") != android.os.Process.myPid())
            check(sha(document) == prior.getString("document_sha256"))
            var saved = false
            main {
                check(note.hasPendingStroke && note.totalStrokeCount == 0 && note.changeVersion == 1L)
                val pending = checkNotNull(note.pendingStroke())
                if (viewInput) {
                    check(prior.getBoolean("view_input") && prior.getBoolean("save_receipt_observed"))
                    check(store.editorPresentation(note).phase == EditorSavePhase.RECOVERY_SAVED)
                    AndroidInputHostChecks.checkProcessStroke(checkNotNull(note.pendingInk()).samples)
                }
                check(note.retryPendingStroke() && note.retryPendingStroke())
                check(!note.hasPendingStroke && note.totalStrokeCount == 1 && note.changeVersion == 2L)
                check(note.stroke(0).samples == (pending.command as CanvasCommand.AddStroke).samples)
                check(store.save(note.captureRecovery()) { _, error -> check(error == null); saved = true })
            }
            eventually("Recovered canonical write did not complete") { saved && store.saveStates().isEmpty() }
            main {
                check(store.activeDocumentId == note.documentId)
                check(document.readText(Charsets.UTF_8) == note.capture().encode())
                if (viewInput) check(store.editorPresentation(note).phase == EditorSavePhase.SAVED)
                marker("committed-ready.json", note)
            }
            waitForExternalStop(store, note)
        }
        if (phase == "process_checkpoint_undelivered") {
            val prior = org.json.JSONObject(File(case,"committed-ready.json").readText(Charsets.UTF_8))
            check(sha(document) == prior.getString("document_sha256"))
            main {
                check(!note.hasPendingStroke && note.totalStrokeCount == 1 && note.changeVersion == 2L)
                check(note.beginStroke(20.0,30.0,.75f,12) && note.endStroke(40.0,50.0,1f,13))
                val snapshot = note.capture()
                check(snapshot.version == 3L && note.totalStrokeCount == 2)
                val expected = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(snapshot.encode().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
                var callbacks = 0
                check(store.save(snapshot) { _, _ -> callbacks++ })
                val state = store.saveStates().single()
                check(callbacks == 0 && state.inFlight && state.snapshot.sameContent(snapshot))
                val ready = org.json.JSONObject().put("pid",android.os.Process.myPid()).put("scope",scope)
                    .put("document_sha256",expected).put("previous_document_sha256",prior.getString("document_sha256"))
                    .put("revision",3).put("stroke_count",2).put("pending",false)
                    .put("save_callback_count",callbacks).put("ledger_in_flight",true)
                    .put("owner_not_retired",true).put("worker_not_stopped",true)
                    .put("owner_callback_blocked",true)
                File(case,"undelivered-ready.json").writeText(ready.toString(),Charsets.UTF_8)
                // Same owner action cannot deliver queued completion before returning. Worker IO continues.
                // Finally keeps both original objects and the callback state live throughout the wait.
                try { java.util.concurrent.CountDownLatch(1).await(90,java.util.concurrent.TimeUnit.SECONDS) }
                finally { check(store.activeDocumentId == note.documentId && callbacks == 0) }
                error("External process-stop driver did not arrive before owner wait ended")
            }
            error("Blocked owner unexpectedly returned")
        }
        if (phase == "process_checkpoint_verify_undelivered") {
            val prior = org.json.JSONObject(File(case,"undelivered-ready.json").readText(Charsets.UTF_8))
            check(prior.getInt("pid") != android.os.Process.myPid() && prior.getInt("save_callback_count") == 0)
            check(sha(document) == prior.getString("document_sha256"))
            main {
                check(!note.hasPendingStroke && note.totalStrokeCount == 2 && note.changeVersion == 3L)
                check(note.pointCount(0) == 3 && note.pointCount(1) == 2)
                check(note.pointX(0,0) == 1.25 && note.stroke(0).samples.timeMicros(2) == 10L)
                check(note.pointX(1,0) == 20.0 && note.pointY(1,1) == 50.0)
                check(note.pointPressure(1,0) == .75f && note.stroke(1).samples.timeMicros(1) == 13L)
                check(note.retryPendingStroke() && note.totalStrokeCount == 2 && note.changeVersion == 3L)
                check(document.readText(Charsets.UTF_8) == note.capture().encode())
                store.stopTestBackend()
            }
            return org.json.JSONObject().put("pid",android.os.Process.myPid()).put("scope",scope)
                .put("canonical_sha256",sha(document)).put("revision",3).put("stroke_count",2).put("point_count",5)
                .put("pending",false).put("owner_delivery_absent_before_kill",true)
                .put("partial_write_kill_verified",false).put("power_loss_verified",false).toString()
        }
        check(phase == "process_checkpoint_verify")
        val prior = org.json.JSONObject(File(case, "committed-ready.json").readText(Charsets.UTF_8))
        check(prior.getInt("pid") != android.os.Process.myPid())
        check(sha(document) == prior.getString("document_sha256"))
        main {
            check(!note.hasPendingStroke && note.totalStrokeCount == 1 && note.changeVersion == 2L)
            check(note.title == "process checkpoint" && note.pointCount(0) == points)
            if (viewInput) {
                check(prior.getBoolean("view_input") && prior.getBoolean("save_receipt_observed"))
                check(store.editorPresentation(note).phase == EditorSavePhase.SAVED)
                AndroidInputHostChecks.checkProcessStroke(note.stroke(0).samples)
            } else {
                check(note.pointX(0,0) == 1.25 && note.pointY(0,2) == 6.0)
                check(note.pointPressure(0,0) == .125f && note.stroke(0).samples.timeMicros(2) == 10L)
            }
            check(note.retryPendingStroke() && note.changeVersion == 2L && note.totalStrokeCount == 1)
            check(document.readText(Charsets.UTF_8) == note.capture().encode())
            store.stopTestBackend()
        }
        return org.json.JSONObject().put("pid",android.os.Process.myPid()).put("scope",scope)
            .put("canonical_sha256",sha(document)).put("stroke_count",1).put("point_count",points).put("view_input",viewInput)
            .put("revision",2).put("pending",false).put("checkpoint_worker_write_verified",true)
            .put("repeated_commit_did_not_duplicate",true).put("unconfirmed_io_kill_verified",false)
            .put("power_loss_verified",false).toString()
    }

}
