package dev.notecore.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import dev.notecore.canvas.preview.PreviewSaveLedger
import dev.notecore.canvas.preview.PreviewSaveAdmission
import dev.notecore.canvas.prototype.*
import dev.notecore.document.*
import kotlin.math.abs

/** Dedicated emulator checks: inject real Android multi-pointer events through the window. */
class ViewportInstrumentation : Instrumentation() {
    private lateinit var host: Activity
    private lateinit var canvas: InkCanvasView
    private lateinit var note: PagedPreviewNotebook
    private var downTime = 0L
    private var brushPathOnly = false
    private var renderTargetOnly = false
    override fun onCreate(arguments: Bundle?) {
        brushPathOnly = arguments?.getString("notecore_case") == "brush_path"
        renderTargetOnly = arguments?.getString("notecore_case") == "render_target"
        super.onCreate(arguments); start()
    }
    override fun onStart() {
        val result = Bundle()
        try {
            if (brushPathOnly) {
                result.putInt("brush_path_pixels", BrushPathOffscreen.verify(context.assets))
                result.putString("brush_path_status", "passed")
                finish(Activity.RESULT_OK, result)
                return
            }
            host = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            setup()
            if (renderTargetOnly) {
                val until = SystemClock.uptimeMillis() + 5000
                var observed: Boolean? = null
                while (observed == null && SystemClock.uptimeMillis() < until) {
                    runOnMainSync { observed = canvas.observedCanvasHardware }
                    if (observed == null) SystemClock.sleep(10)
                }
                check(observed == true) { "Requires actual hardware onDraw, not a bitmap host" }
                runOnMainSync {
                    check(canvas.layerType == android.view.View.LAYER_TYPE_NONE)
                    check(canvas.width > 0 && canvas.height > 0)
                    result.putInt("target_width", canvas.width); result.putInt("target_height", canvas.height)
                    result.putString("target_class", "InkCanvasView.onDraw")
                    result.putString("target_hardware", canvas.observedCanvasHardware.toString())
                    check(note.totalStrokeCount == 0 && !note.hasActiveStroke)
                }
                result.putInt("target_pid", android.os.Process.myPid())
                result.putString("render_target_status", "passed")
                result.putString("render_target_scope", "app-host isolated in-memory canonical page; no ordinary-package UI or new backend consumption")
                finish(Activity.RESULT_OK, result)
                return
            }
            val cx = canvas.width / 2f; val cy = canvas.height / 2f
            event(MotionEvent.ACTION_DOWN, listOf(P(0, cx - 100, cy)))
            event(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), listOf(P(0, cx - 100, cy), P(1, cx + 100, cy)))
            event(MotionEvent.ACTION_MOVE, listOf(P(0, cx - 180, cy + 30), P(1, cx + 180, cy + 30)))
            event(MotionEvent.ACTION_POINTER_UP or (1 shl 8), listOf(P(0, cx - 180, cy + 30), P(1, cx + 180, cy + 30)))
            event(MotionEvent.ACTION_MOVE, listOf(P(0, cx - 160, cy + 50)))
            event(MotionEvent.ACTION_UP, listOf(P(0, cx - 160, cy + 50)))
            runOnMainSync { check(note.strokeCount == 0 && !note.hasActiveStroke); check(canvas.viewport.zoom > 1.5) }
            val start = canvas.viewport.toScene(cx.toDouble(), cy.toDouble())
            event(MotionEvent.ACTION_DOWN, listOf(P(0, cx, cy)))
            event(MotionEvent.ACTION_UP, listOf(P(0, cx + 30, cy + 30)))
            runOnMainSync {
                check(note.strokeCount == 1)
                check(abs(note.pointX(0, 0) - start.x) < 0.001 && abs(note.pointY(0, 0) - start.y) < 0.001)
                val bytes = note.capture().encode(); canvas.resetViewport()
                check(canvas.viewport.zoom == 1.0 && bytes == note.capture().encode())
            }
            result.putString("pinch_and_inverse_mapping", "passed")
            setup()
            val originalScale = canvas.viewport.scale
            event(MotionEvent.ACTION_DOWN, listOf(P(0, cx, cy, MotionEvent.TOOL_TYPE_STYLUS)))
            event(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), listOf(P(0, cx, cy, MotionEvent.TOOL_TYPE_STYLUS), P(1, cx - 100, cy)))
            event(MotionEvent.ACTION_POINTER_DOWN or (2 shl 8), listOf(P(0, cx, cy, MotionEvent.TOOL_TYPE_STYLUS), P(1, cx - 100, cy), P(2, cx + 100, cy)))
            event(MotionEvent.ACTION_MOVE, listOf(P(0, cx + 20, cy + 20, MotionEvent.TOOL_TYPE_STYLUS), P(1, cx - 200, cy), P(2, cx + 200, cy)))
            event(MotionEvent.ACTION_POINTER_UP or (2 shl 8), listOf(P(0, cx + 20, cy + 20, MotionEvent.TOOL_TYPE_STYLUS), P(1, cx - 200, cy), P(2, cx + 200, cy)))
            event(MotionEvent.ACTION_POINTER_UP or (1 shl 8), listOf(P(0, cx + 20, cy + 20, MotionEvent.TOOL_TYPE_STYLUS), P(1, cx - 200, cy)))
            event(MotionEvent.ACTION_UP, listOf(P(0, cx + 30, cy + 30, MotionEvent.TOOL_TYPE_STYLUS)))
            runOnMainSync { check(note.strokeCount == 1); check(canvas.viewport.scale == originalScale) }
            result.putString("stylus_transform_lock", "passed")
            setup()
            runOnMainSync { canvas.fingerDrawing = false; canvas.zoomViewport(2.0) }
            val originalX = canvas.viewport.offsetX
            val bytes = note.capture().encode()
            event(MotionEvent.ACTION_DOWN, listOf(P(0, cx, cy)))
            event(MotionEvent.ACTION_MOVE, listOf(P(0, cx + 60, cy + 30)))
            event(MotionEvent.ACTION_UP, listOf(P(0, cx + 60, cy + 30)))
            runOnMainSync {
                check(canvas.viewport.offsetX > originalX + 40)
                check(note.capture().encode() == bytes && !note.hasActiveStroke)
            }
            result.putString("finger_pan_without_ink", "passed")
            verifyExecutor()
            result.putString("executor_owner_worker", "passed")
            verifyDocumentHistory()
            result.putString("document_delta_history_owner", "passed")
            result.putString("paged_preview_codec", "passed")
            verifyPagedSession()
            result.putString("paged_preview_session", "passed")
            verifyPagedStorage()
            result.putString("paged_snapshot_storage", "passed")
            verifyStorage()
            result.putString("storage_failure_recovery", "passed")
            result.putString("storage_bounded_read_and_active_owner", "passed")
            result.putString("storage_atomic_rename_failure", "passed")
            verifyCatalogReuse()
            result.putString("catalog_visible_row_recycling", "passed")
            result.putString("viewport_status", "passed")
            finish(Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            result.putString("viewport_status", "failed"); result.putString("failure", failure.stackTraceToString())
            finish(Activity.RESULT_CANCELED, result)
        }
    }
    private fun verifyPagedStorage() {
        val directory = java.io.File(targetContext.cacheDir, "paged-storage-${System.nanoTime()}")
        check(directory.mkdirs())
        try {
            val io = PreviewFileIO(directory, targetContext.contentResolver)
            val note = PagedPreviewNotebook.create("ART-paged-save", "分页保存", "ART-save-session")
            check(note.beginStroke(1.0, 2.0, 0.5f, 1)); check(note.endStroke(3.0, 4.0, 0.7f, 2))
            val old = note.capture(); val ledger = PreviewSaveLedger()
            check(ledger.offer(old) == PreviewSaveAdmission.ACCEPTED); val first = ledger.nextWrite()!!
            check(note.insertPage()); check(note.beginStroke(10.0, 20.0, 0.5f, 3)); check(note.endStroke(30.0, 40.0, 0.7f, 4))
            val latest = note.capture(); check(ledger.offer(latest) == PreviewSaveAdmission.ACCEPTED)
            io.write(first.snapshot); check(ledger.complete(first, true)); check(ledger.latest(note.documentId) === latest)
            val next = ledger.nextWrite()!!; io.write(next.snapshot); check(ledger.complete(next, true))
            val decoded = PagedPreviewCodec.decode(java.io.File(directory, "${note.documentId}.ncp").readText())
            check(decoded.pageCount == 2 && decoded.scene.objectCount == 2 && decoded.pages() == latest.root.pages())
            check(decoded.scene.objects() == latest.root.scene.objects())
            check(!ledger.complete(first, true))
            val blocked = java.io.File(directory, "blocked"); blocked.writeText("synthetic IO failure")
            val recoveryIO = PreviewFileIO(blocked, targetContext.contentResolver)
            check(ledger.offer(latest) == PreviewSaveAdmission.ACCEPTED); val failed = ledger.nextWrite()!!
            check(runCatching { recoveryIO.write(failed.snapshot) }.isFailure); check(ledger.complete(failed, false))
            check(ledger.latest(note.documentId) === latest && ledger.nextWrite() == null)
            check(blocked.delete()); check(blocked.mkdirs())
            val reopened = PagedPreviewNotebook(decoded, "ART-recovered-session").capture()
            check(ledger.offer(reopened) == PreviewSaveAdmission.ACCEPTED)
            val retry = ledger.nextWrite()!!; recoveryIO.write(retry.snapshot); check(ledger.complete(retry, true))
            check(java.io.File(blocked, "${note.documentId}.ncp").readText() == latest.encode())
        } finally { directory.deleteRecursively() }
    }
    private fun verifyPagedSession() {
        val note = PagedPreviewNotebook.create("ART-session", "分页", "ART-fresh")
        val first = note.currentPageId
        check(note.beginStroke(1.0, 2.0, 0.5f, 1)); check(note.endStroke(3.0, 4.0, 0.7f, 2))
        check(note.insertPage()); val second = note.currentPageId
        check(note.beginStroke(10.0, 20.0, 0.5f, 3)); check(note.selectPage(first))
        check(!note.endStroke(30.0, 40.0, 0.7f, 4)); check(note.totalStrokeCount == 1)
        check(note.copyCurrentPage()); val copied = note.currentPageId
        check(note.totalStrokeCount == 2 && note.pageCount == 3)
        check(note.deleteCurrentPage()); check(note.currentPageId == second)
        check(note.undo()); check(note.currentPageId == second); check(note.selectPage(copied))
        val reopened = PagedPreviewNotebook(PagedPreviewCodec.decode(note.capture().encode()), "ART-reopened")
        check(reopened.pageCount == 3 && reopened.totalStrokeCount == 2 && !reopened.canUndo)
    }
    private fun verifyDocumentHistory() {
        val executor = BoundedOwnerExecutor(2, 4096, "NoteCore-ART-history")
        val doc = SceneId("ART-history")
        val page = PageDescriptor(PageId("page"), LayerId("ink"), 595.0, 842.0, PaperKind.RULED)
        val owner = MemoryDocumentAuthority(DocumentRoot.empty(doc, "test", page), ownerCheck = executor::checkOwnerThread)
        val samples = StrokeSamples.copyOf(doubleArrayOf(1.0, 2.0), doubleArrayOf(3.0, 4.0), floatArrayOf(0.5f, 0.7f), longArrayOf(1, 2))
        val objectId = ObjectId("stroke")
        val add = DocumentCommand.Canvas(page.id, CanvasCommand.AddStroke(CommandId("add"), 0,
            StrokeTarget(doc, page.layerId, BrushSpec(BrushKind.MONOLINE, 1, 2.0, 0xff000000.toInt())), objectId, samples))
        check(owner.commit(add) is DocumentCommit.Accepted)
        val clear = DocumentCommand.DeletePage(CommandId("clear"), doc, 1, page.id)
        check(owner.commit(clear) is DocumentCommit.Accepted)
        val undo = DocumentCommand.Undo(CommandId("undo"), doc, 2, clear.id)
        val before = owner.status()
        try {
            executor.submit(undo, 1024, { owner.commit(it) }) { _, outcome ->
                check(outcome is WorkResult.OutcomeUnknown); check(owner.status() == before)
                ResultDisposition.ACKNOWLEDGED
            }
            executor.stopAccepting(); check(executor.awaitWorkerStop(3000)); check(executor.drainResults().acknowledged == 1)
            val restored = owner.commit(undo) as DocumentCommit.Accepted
            check(owner.snapshot().page(0).paper == PaperKind.RULED)
            check(owner.snapshot().scene.find(objectId)!!.objectRevision == 3L)
            check(owner.snapshot().scene.find(objectId)!!.samples === samples)
            val encoded = PagedPreviewCodec.encode(owner.snapshot())
            val decoded = PagedPreviewCodec.decode(encoded)
            check(decoded.pages() == owner.snapshot().pages() && decoded.scene.objects() == owner.snapshot().scene.objects())
            check(PagedPreviewCodec.encode(decoded) == encoded)
            check(PagedPreviewCodec.header(encoded.lineSequence().take(2).joinToString("\n")).formatVersion == 2)
            val legacy = "NCPREVIEW\t1\nD\tART-legacy\tlegacy\t2\t1\t1\nS\tink-1\t-1\t2.5\t2\nP\t1\t2\t0.5\t1\nP\t3\t4\t0.7\t2\nEND\n"
            val migrated = PagedPreviewCodec.decode(legacy)
            check(migrated.pageCount == 1 && migrated.scene.objects().single().samples.timeMicros(1) == 2L)
            check(owner.commit(undo) == restored)
            check(owner.commit(DocumentCommand.Redo(CommandId("redo"), doc, 3, clear.id)) is DocumentCommit.Accepted)
            check(owner.snapshot().scene.objectCount == 0 && owner.snapshot().revision == 4L)
        } finally { executor.stopAccepting(); check(executor.awaitWorkerStop(3000)) }
    }
    private fun verifyCatalogReuse() {
        lateinit var adapter: NoteListAdapter
        lateinit var list: android.widget.ListView
        var opened: String? = null
        val rows = (0 until 1000).map { NoteSummary("synthetic-$it", "Synthetic note $it", 0) }
        runOnMainSync {
            list = android.widget.ListView(host)
            adapter = NoteListAdapter((88 * host.resources.displayMetrics.density).toInt(), { android.widget.Button(host) }, { opened = it })
            list.adapter = adapter; host.setContentView(list); adapter.replace(rows)
        }
        waitForIdleSync()
        runOnMainSync {
            check(adapter.count == 1000 && list.childCount in 1..99)
            check(adapter.createdRowCount < 100) { "Catalog created widgets for invisible rows" }
            list.setSelection(999)
        }
        waitForIdleSync()
        runOnMainSync {
            val last = list.getChildAt(list.childCount - 1) as android.widget.Button
            check(last.text.startsWith("Synthetic note 999\n")); last.performClick()
            check(opened == "synthetic-999") { "Recycled row opened a stale document ID" }
            list.setSelection(0)
        }
        waitForIdleSync()
        runOnMainSync {
            val first = list.getChildAt(0) as android.widget.Button
            check(first.text.startsWith("Synthetic note 0\n")); first.performClick()
            check(opened == "synthetic-0"); check(adapter.createdRowCount < 100)
        }
    }
    private fun <T : Any> storageResult(request: ((T) -> Unit) -> Unit): T {
        val ready = java.util.concurrent.CountDownLatch(1)
        val value = java.util.concurrent.atomic.AtomicReference<T>()
        runOnMainSync { request { value.set(it); ready.countDown() } }
        check(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "Storage owner callback timed out" }
        return checkNotNull(value.get())
    }
    private fun verifyStorage() {
        // Isolated synthetic cache directory; never alter the preview's real note directory.
        val directory = java.io.File(targetContext.cacheDir, "storage-contract-${java.util.UUID.randomUUID()}")
        directory.writeText("A file blocks the required directory")
        lateinit var store: NoteStore
        lateinit var snapshot: PagedPreviewSnapshot
        lateinit var retainedInput: PreviewSaveSnapshot
        lateinit var notebook: PagedPreviewNotebook
        runOnMainSync {
            store = NoteStore.isolated(targetContext, directory)
            notebook = PagedPreviewNotebook.create("storage-contract", "Retained after failure", "storage-session")
            check(notebook.beginStroke(12.0, 15.0, 0.5f, 1))
            check(notebook.endStroke(20.0, 30.0, 0.6f, 2))
            snapshot = notebook.capture()
        }
        try {
            val failed = storageResult<Pair<Long, String?>> { done -> store.save(snapshot) { version, error -> done(version to error) } }
            check(failed.second != null && failed.first == snapshot.version)
            runOnMainSync {
                val retained = store.saveStates().single()
                retainedInput = retained.snapshot
                check(retained.retryRequired && retainedInput.sameContent(snapshot))
            }
            val reopened = storageResult<Pair<OpenedDocument?, String?>> { done -> store.open(snapshot.documentId) { note, error -> done(note to error) } }
            check(reopened.first?.root?.let(PagedPreviewCodec::encode) == snapshot.encode() && reopened.second != null)
            lateinit var reconstructed: PagedPreviewSnapshot
            lateinit var fork: PagedPreviewSnapshot
            runOnMainSync {
                reconstructed = PagedPreviewSnapshot.fromRoot(reopened.first!!.root)
                val other = PagedPreviewNotebook.create(snapshot.documentId, snapshot.title, "different-session")
                check(other.beginStroke(100.0, 200.0, 0.5f, 1)); check(other.endStroke(300.0, 400.0, 0.6f, 2))
                fork = other.capture()
            }
            val conflict = storageResult<Pair<Long, String?>> { done -> store.save(fork) { version, error -> done(version to error) } }
            check(conflict.second?.contains("冲突") == true)
            val failedRetry = storageResult<Pair<Long, String?>> { done -> store.save(reconstructed) { version, error -> done(version to error) } }
            check(failedRetry.second != null)
            runOnMainSync { check(store.saveStates().single().snapshot === retainedInput) }
            val listing = storageResult<Pair<List<NoteSummary>, String?>> { done -> store.list { notes, error -> done(notes to error) } }
            check(listing.first.single().title.contains("Retained after failure"))
            check(directory.delete()); check(directory.mkdirs())
            val saved = storageResult<Pair<Long, String?>> { done -> store.save(reconstructed) { version, error -> done(version to error) } }
            check(saved.second == null)
            check(java.io.File(directory, "storage-contract.ncp").readText() == snapshot.encode())
            runOnMainSync { check(store.saveStates().isEmpty()) }
            val failedTarget = java.io.File(directory, "rename-failure.ncp").apply { check(mkdir()) }
            val sentinel = java.io.File(failedTarget, "keep").apply { writeText("Preserve unrelated contents") }
            lateinit var renameSnapshot: PagedPreviewSnapshot
            runOnMainSync { renameSnapshot = PagedPreviewNotebook.create("rename-failure", "Must not claim saved", "rename-session").capture() }
            val renameFailure = storageResult<Pair<Long, String?>> { done -> store.save(renameSnapshot) { version, error -> done(version to error) } }
            check(renameFailure.second != null && sentinel.readText() == "Preserve unrelated contents")
            runOnMainSync { check(store.saveStates().single().snapshot.sameContent(renameSnapshot)) }
            check(sentinel.delete()); check(failedTarget.delete())
            val renameRetry = storageResult<Pair<Long, String?>> { done -> store.save(renameSnapshot) { version, error -> done(version to error) } }
            check(renameRetry.second == null && failedTarget.readText() == renameSnapshot.encode())
            val corrupt = java.io.File(directory, "invalid-utf.ncp").apply { writeBytes(byteArrayOf(0xff.toByte())) }
            val invalid = storageResult<Pair<OpenedDocument?, String?>> { done -> store.open("invalid-utf") { note, error -> done(note to error) } }
            check(invalid.first == null && invalid.second != null && corrupt.readBytes().contentEquals(byteArrayOf(0xff.toByte())))
            val oversized = java.io.File(directory, "oversized.ncp")
            java.io.RandomAccessFile(oversized, "rw").use { it.setLength(33L * 1024 * 1024) }
            val large = storageResult<Pair<OpenedDocument?, String?>> { done -> store.open("oversized") { note, error -> done(note to error) } }
            check(large.first == null && large.second != null && oversized.length() == 33L * 1024 * 1024)
            runOnMainSync {
                notebook = store.adopt(OpenedDocument(snapshot.root, true))
                // Activity recreation reuses the app-owned canonical notebook even when save admission could fail.
                store.open(notebook.documentId) { active, error -> check(store.adopt(active!!) === notebook && error == null) }
                store.releaseActive(notebook)
                check(!notebook.beginStroke(1.0, 2.0, 0.5f, 3))
            }
            val original = "NCPREVIEW\t1\nD\tlegacy-noop\tLegacy unchanged\t2\t1\t1\nS\tink-1\t-1\t2.5\t2\nP\t900\t1300\t0.5\t1\nP\t999\t1399\t0.5\t2\nEND\n"
            val legacyFile = java.io.File(directory, "legacy-noop.ncp").apply { writeText(original) }
            val read = storageResult<Pair<OpenedDocument?, String?>> { done -> store.open("legacy-noop") { opened, error -> done(opened to error) } }
            lateinit var migrated: PagedPreviewNotebook
            runOnMainSync {
                migrated = store.adopt(read.first!!)
                check(migrated.currentPage.width == 1000.0 && migrated.pointY(0, 1) == 1399.0)
            }
            val noOp = storageResult<Pair<Long, String?>> { done -> store.save(migrated.capture()) { version, error -> done(version to error) } }
            check(noOp.second == null && legacyFile.readText() == original)
            runOnMainSync { check(migrated.insertPage()) }
            val edited = storageResult<Pair<Long, String?>> { done -> store.save(migrated.capture()) { version, error -> done(version to error) } }
            check(edited.second == null)
            val migratedRoot = PagedPreviewCodec.decode(legacyFile.readText())
            check(migratedRoot.pageCount == 2 && migratedRoot.scene.objects().single().samples.y(1) == 1399.0)
            runOnMainSync { store.releaseActive(migrated) }
        } finally {
            runOnMainSync { store.stopTestBackend() }
            directory.deleteRecursively()
        }
    }
    private fun setup() {
        runOnMainSync {
            note = PagedPreviewNotebook(DocumentRoot.empty(SceneId("viewport-test"), "Synthetic viewport test",
                PageDescriptor(PageId("viewport-page"), LayerId("viewport-layer"), 1000.0, 1400.0)), "viewport-session")
            canvas = InkCanvasView(host, note, {}, { error(it) })
            host.setContentView(canvas)
        }
        waitForIdleSync()
    }
    private fun verifyExecutor() {
        // This owner is the instrumentation thread, not the UI thread; join never blocks the app UI.
        val executor = BoundedOwnerExecutor(2, 8, "NoteCore-ART-test")
        try {
            val owner = Thread.currentThread().id
            var allow = false; var callbacks = 0; var runs = 0
            val input = SceneId("ART-owned-input")
            val first = executor.submit(input, 4, { executor.checkWorkerThread(); runs++; Thread.currentThread().id }) { retained, outcome ->
                check(retained === input)
                executor.checkOwnerThread(); check(Thread.currentThread().id == owner)
                check((outcome as WorkResult.Succeeded<Long>).value != owner)
                if (!allow) ResultDisposition.RETRY_REQUIRED else { callbacks++; ResultDisposition.ACKNOWLEDGED }
            } as WorkAdmission.Accepted
            executor.submit(4, { executor.checkWorkerThread(); runs++; Thread.currentThread().id }) {
                check(Thread.currentThread().id == owner); check((it as WorkResult.Succeeded<Long>).value != owner)
                callbacks++; ResultDisposition.ACKNOWLEDGED
            }
            check(executor.submit(1, { error("Rejected work executed") }) { ResultDisposition.ACKNOWLEDGED } is WorkAdmission.Rejected)
            executor.stopAccepting(); check(executor.awaitWorkerStop(3000))
            check(executor.status().chargedBytes == 8L && !executor.status().isDrained)
            check(executor.drainResults().blocked === first.ticket && callbacks == 0 && runs == 2)
            allow = true; check(executor.retryDelivery(first.ticket)); check(executor.drainResults().acknowledged == 2)
            check(callbacks == 2 && runs == 2 && executor.status().isDrained)
            val document = DocumentCloseCoordinator<String, Any>(SceneId("ART-thread-check"), ownerCheck = executor::checkOwnerThread)
            var refused = false
            val foreign = Thread { try { document.beginClose() } catch (_: IllegalStateException) { refused = true } }
            foreign.start(); foreign.join(3000); check(!foreign.isAlive && refused)
            check(document.status().phase == DocumentClosePhase.OPEN); document.beginClose()
        } finally { executor.stopAccepting(); check(executor.awaitWorkerStop(3000)) }
    }
    private data class P(val id: Int, val x: Float, val y: Float, val tool: Int = MotionEvent.TOOL_TYPE_FINGER)
    private fun event(action: Int, points: List<P>) {
        if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
        val location = IntArray(2); runOnMainSync { canvas.getLocationOnScreen(location) }
        val properties = points.map { MotionEvent.PointerProperties().apply { id = it.id; toolType = it.tool } }.toTypedArray()
        val coords = points.map { MotionEvent.PointerCoords().apply { x = it.x + location[0]; y = it.y + location[1]; pressure = 0.5f; size = 1f } }.toTypedArray()
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, points.size, properties, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { check(uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        waitForIdleSync()
    }
}
