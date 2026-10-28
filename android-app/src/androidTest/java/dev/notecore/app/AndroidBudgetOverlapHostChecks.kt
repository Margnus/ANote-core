package dev.notecore.app

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.SystemClock
import android.view.View
import dev.notecore.document.*
import dev.notecore.canvas.preview.PreviewSaveLedger
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/** Original Store + actual compositor; private ledger reflection is read-only diagnostic evidence. */
internal class AndroidBudgetOverlapHostChecks(private val test: Instrumentation, private val host: Activity) {
    private fun main(action: () -> Unit) = test.runOnMainSync(action)
    private fun eventually(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 12000
        var done = false
        while (!done && SystemClock.uptimeMillis() < end) {
            main { done = condition() }; if (!done) SystemClock.sleep(20)
        }
        check(done) { message }
    }
    fun verify(): String {
        check(Build.VERSION.SDK_INT >= 33)
        val directory = File(host.cacheDir, "owner-budget-overlap-${java.util.UUID.randomUUID()}")
        check(directory.mkdirs())
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val frame = CountDownLatch(1)
        val armWrite = AtomicBoolean(false); val captureWrites = AtomicBoolean(false)
        val target = AtomicReference<File>(); val completedWrites = ConcurrentLinkedQueue<String>()
        var allowSettlement = false
        var nativeCloses = 0
        lateinit var owner: NoteStore; lateinit var note: PagedPreviewNotebook; lateinit var view: InkCanvasView
        lateinit var ledger: PreviewSaveLedger
        lateinit var old: PagedPreviewSnapshot; lateinit var latest: PagedPreviewSnapshot
        var nativeBytes = 0L; var inputBytes = 0L; var historyBytes = 0L; var middleBytes = 0L
        var callbacksBefore = 0; var publications = 0
        var finished = false
        try {
            main {
                val budget = checkNotNull(DocumentBudget.validated(maxObjects = 2, journalEntries = 4096,
                    journalBytes = 32L*1024*1024, identityBytes = 4L*1024*1024).budget)
                owner = NoteStore.isolated(host, directory, afterInkNativeClose = { nativeCloses++ },
                    maySettleInkRelease = { allowSettlement }, documentBudget = budget,
                    observeWriteBoundary = { boundary ->
                        if (boundary == PreviewWriteBoundary.SYNCED_BEFORE_REPLACE && armWrite.getAndSet(false)) {
                            entered.countDown(); release.await() // Explicit test release, never timeout receipt.
                        }
                        if (boundary == PreviewWriteBoundary.VERIFIED_BEFORE_RETURN && captureWrites.get()) {
                            check(completedWrites.size < 2)
                            completedWrites.add(checkNotNull(target.get()).readText(Charsets.UTF_8))
                        }
                    })
                note = owner.create(); check(note.setPaper(200.0, 200.0, PaperKind.BLANK))
                for (x in listOf(40.0, 80.0)) {
                    check(note.beginStroke(x, 40.0, .25f, 1)); check(note.endStroke(x+5, 45.0, .75f, 2))
                }
                target.set(File(directory, "${note.documentId}.ncp"))
                check(owner.save(note.captureRecovery()) { _, error -> check(error == null) })
                ledger = NoteStore::class.java.getDeclaredField("ledger").apply { isAccessible = true }.get(owner) as PreviewSaveLedger
                view = InkCanvasView(host, note, {}, { error(it) })
                host.setContentView(InkSurfaceHost(view, owner))
                view.viewTreeObserver.registerFrameCommitCallback { frame.countDown() }
            }
            test.waitForIdleSync(); check(frame.await(5, TimeUnit.SECONDS))
            eventually("Original native frame was not handed to ViewRoot") {
                owner.inkFramePublications > 0 && owner.inkFrameDraws > 0 && ledger.isDrained
            }
            // One finite owner call keeps ViewRoot from replacing the admitted reader between state checks.
            main {
                nativeBytes = owner.inkChargedBytes; callbacksBefore = owner.inkReleaseCallbacks
                publications = owner.inkFramePublications
                check(nativeBytes > 0 && nativeCloses == 0 && callbacksBefore == 0) { "Native reader already returned before the overlap" }
                check(note.rename("Old write still owns this root")); old = note.captureRecovery()
                captureWrites.set(true); armWrite.set(true)
                check(owner.save(old) { _, error -> check(error == null) })
                check(entered.await(5, TimeUnit.SECONDS)) { "Original IO did not reach observed sync boundary" }
                check(ledger.states().single().inFlight && ledger.chargedBytes == old.retainedByteCharge)
                val full = note.snapshot()
                check(!note.beginStroke(100.0, 100.0, .5f, 3) && note.snapshot() === full)
                check(!note.hasActiveStroke && !note.hasPendingStroke)
                check(note.undo() && note.undo() && note.totalStrokeCount == 1)
                historyBytes = note.authorityStatus().historyByteCharge; check(historyBytes > 0)
                val middle = note.captureRecovery(); middleBytes = middle.retainedByteCharge
                check(owner.save(middle) { _, error -> check(error == null) })
                check(ledger.chargedBytes == old.retainedByteCharge + middleBytes)
                check(note.beginStroke(120.0, 140.0, .125f, 7))
                val input = checkNotNull(note.activeInputOperationId); inputBytes = note.inputReservedByteCharge
                check(inputBytes > 0)
                val before = note.snapshot()
                val refusal = note.edit(PreviewEdit.SetPaper(Double.NaN, 200.0, PaperKind.BLANK))
                check(!refusal.accepted && refusal.failure?.kind == PreviewFailureKind.INVALID_ARGUMENT)
                check(owner.beginOwnerRetirement() == StoreRetirementRequest.INPUT_PENDING)
                check(note.snapshot() === before && note.activeInputOperationId == input && note.inputReservedByteCharge == inputBytes)
                check(ledger.chargedBytes == old.retainedByteCharge + middleBytes && owner.inkChargedBytes == nativeBytes)
                check(note.endStroke(150.0, 160.0, .875f, 9) && note.totalStrokeCount == 2 && note.inputReservedByteCharge == 0L)
                latest = note.captureRecovery(); check(owner.save(latest) { _, error -> check(error == null) })
                check(ledger.chargedBytes == old.retainedByteCharge + latest.retainedByteCharge)
                check(owner.beginOwnerRetirement { state ->
                    check(state.renderBytes == 0L && state.pendingWrites == 0 && state.workerStopped); finished = true
                } == StoreRetirementRequest.STARTED)
                check(owner.activeDocumentId == null && note.authorityStatus().historyByteCharge == 0L)
                check(ledger.chargedBytes > 0 && owner.inkChargedBytes > 0 && !finished)
                owner.detachInk(view); host.setContentView(View(host))
            }
            eventually("No actual compositor release receipt while original settlement was held") {
                owner.inkReleaseCallbacks > callbacksBefore && owner.inkReleaseDeferred
            }
            main { check(nativeCloses == 0 && owner.inkChargedBytes > 0 && !finished) }
            release.countDown()
            eventually("Original old/new writes did not finish") { ledger.isDrained }
            check(completedWrites.toList() == listOf(old.encode(), latest.encode())) { "Old ACK lost or reordered the latest content" }
            check(checkNotNull(target.get()).readText(Charsets.UTF_8) == latest.encode())
            main {
                check(owner.ownerRetirementStatus().phase == StoreOwnerPhase.RETIRING)
                check(!owner.ownerRetirementStatus().workerStopped && owner.inkChargedBytes > 0 && !finished)
                allowSettlement = true // Authorize original owner to consume the already observed real receipt.
            }
            eventually("Actual source release did not finish original worker retirement") {
                finished && owner.ownerRetirementStatus().workerStopped && owner.inkChargedBytes == 0L
            }
            main { check(nativeCloses == 1 && ledger.chargedBytes == 0L) }
            val decoded = PreviewFileIO(directory, host.contentResolver).readRecovery(latest.documentId)
            check(decoded.sameContent(latest) && decoded.pending == null)
            check(directory.deleteRecursively())
            return JSONObject().put("native_declared_bytes_at_overlap", nativeBytes).put("input_declared_bytes_at_overlap", inputBytes)
                .put("history_declared_bytes_at_overlap", historyBytes).put("old_flight_declared_bytes", old.retainedByteCharge)
                .put("new_latest_declared_bytes_at_overlap", middleBytes).put("native_publications", publications)
                .put("release_callbacks_before_overlap", callbacksBefore).put("actual_release_callback_observed", true)
                .put("owner_settlement_deliberately_deferred", true).put("unsignaled_gpu_fence_verified", false)
                .put("capacity_and_retirement_refusal_preserved_input", true).put("old_and_new_real_writes_verified", true)
                .put("final_strokes", 2).put("same_content_after_save", true).put("native_closed_exactly_once", true)
                .put("worker_stopped", true).put("unified_peak_admission_verified", false).put("physical_heap_measured", false).toString()
        } finally {
            release.countDown() // Failure cleanup; never counted as a successful IO/native receipt.
        }
    }
}
