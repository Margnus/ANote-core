package dev.notecore.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.notecore.canvas.preview.*
import dev.notecore.canvas.prototype.*
import dev.notecore.document.*
import java.util.UUID
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal enum class ExportPickerDisposition { READY, BUSY, CAPACITY, IDENTITY_EXHAUSTED, OWNER_CLOSED, PENDING_RECOVERY }
internal data class ExportPickerAdmission(val disposition: ExportPickerDisposition, val requestCode: Int? = null)
internal enum class StoreOwnerPhase { ACTIVE, RETIRING, RETIRED }
internal enum class StoreRetirementRequest { STARTED, ALREADY_RETIRING, RETIRED, INPUT_PENDING, TRANSFER_PENDING, WORK_PENDING, SAVE_REJECTED }
internal data class StoreRetirementStatus(val phase: StoreOwnerPhase, val pendingWrites: Int,
    val retryRequired: Int, val renderBytes: Long, val workerStopped: Boolean)

data class OpenedDocument(val root: DocumentRoot, val persisted: Boolean, val recovery: PagedPreviewSnapshot = PagedPreviewSnapshot.fromRoot(root))

data class NoteSummary(val id: String, val title: String, val modified: Long)

/** App lifetime storage owner is the main thread. Only immutable captures / exclusive read results cross IO. */
@OptIn(ExperimentalAndroidInkFrame::class)
class NoteStore private constructor(context: Context, private val directory: File = File(context.filesDir, "preview-notes"),
    private val beforeInkCompute: () -> Unit = {}, private val afterInkNativeClose: () -> Unit = {},
    private val afterInkBufferSubmitted: () -> Unit = {}, private val maySettleInkRelease: () -> Boolean = { true },
    private val exportPickerByteLimit: Long = 8L * 1024 * 1024, private val beforeOpenRead: () -> Unit = {}) {
    private val io = PreviewFileIO(directory, context.applicationContext.contentResolver, beforeOpenRead)
    private val main = Handler(Looper.getMainLooper())
    private val wakePending = AtomicBoolean()
    private val executor = BoundedOwnerExecutor(16, STORAGE_WORK_BYTES, "NoteCore-preview-storage", ::requestPump)
    private val ledger = PreviewSaveLedger(ownerCheck = executor::checkOwnerThread)
    private val saveTaskTooLarge = context.getString(R.string.save_task_too_large)
    private data class SaveCallback(val snapshot: PagedPreviewSnapshot, val receive: (Long, String?) -> Unit)
    private val saveCallbacks = mutableMapOf<String, SaveCallback>()
    private var viewEpoch = 0L
    private var activeNotebook: PagedPreviewNotebook? = null
    private var ownerRetiring = false
    private var ownerRetired = false
    private var ownerStopRequested = false
    private var requestsInFlight = 0
    private var retirementObserver: ((StoreRetirementStatus) -> Unit)? = null
    internal fun ownerRetirementStatus(): StoreRetirementStatus {
        executor.checkOwnerThread()
        val states = ledger.states()
        return StoreRetirementStatus(if (ownerRetired) StoreOwnerPhase.RETIRED else if (ownerRetiring) StoreOwnerPhase.RETIRING else StoreOwnerPhase.ACTIVE,
            states.size, states.count { it.retryRequired }, inkChargedBytes, executor.status().workerStopped)
    }
    internal fun beginOwnerRetirement(onRetired: ((StoreRetirementStatus) -> Unit)? = null): StoreRetirementRequest {
        executor.checkOwnerThread()
        if (ownerRetired) return StoreRetirementRequest.RETIRED
        if (ownerRetiring) return StoreRetirementRequest.ALREADY_RETIRING
        if (activeNotebook?.let { it.hasActiveStroke || it.hasPendingStroke } == true) return StoreRetirementRequest.INPUT_PENDING
        if (pendingExport != null) return StoreRetirementRequest.TRANSFER_PENDING
        if (requestsInFlight != 0 || executor.status().entries.any {
            it.stage == OwnedWorkStage.DELIVERING || it.stage == OwnedWorkStage.DELIVERY_BLOCKED
        }) return StoreRetirementRequest.WORK_PENDING
        val note = activeNotebook
        if (note != null && !save(note.capture()) { _, _ -> }) return StoreRetirementRequest.SAVE_REJECTED
        ownerRetiring = true; retirementObserver = onRetired; viewEpoch++
        if (note != null) releaseActive(note, lostEndpoint = true) else retireInk(lostEndpoint = true)
        inkView.clear()
        advanceOwnerRetirement()
        return StoreRetirementRequest.STARTED
    }
    internal fun retryRetainedWrites(): Boolean {
        executor.checkOwnerThread()
        val worker = executor.status()
        if (ownerRetired || ownerStopRequested || !worker.accepting || worker.entries.any {
            it.stage == OwnedWorkStage.DELIVERING || it.stage == OwnedWorkStage.DELIVERY_BLOCKED
        }) return false
        for (entry in ledger.states().filter { it.retryRequired }) {
            if (ledger.offer(entry.snapshot) != PreviewSaveAdmission.ACCEPTED) return false
        }
        pumpWrites(); advanceOwnerRetirement(); return true
    }
    private fun advanceOwnerRetirement() {
        executor.checkOutsideDrain()
        if (!ownerRetiring || ownerRetired) return
        val worker = executor.status()
        if (ledger.states().isNotEmpty() || inkRenderer != null || inkImage != null || worker.entries.isNotEmpty()) return
        if (!ownerStopRequested) { ownerStopRequested = true; executor.stopAccepting() }
        if (!executor.status().isDrained) return
        ownerRetired = true
        val observer = retirementObserver; retirementObserver = null
        if (observer != null) notifyUi { observer(ownerRetirementStatus()) }
    }
    internal val activeDocumentId: String? get() { executor.checkOwnerThread(); return activeNotebook?.documentId }
    // An identity-only optimization must not retain an obsolete canonical root.
    private var activeSavedRoot = java.lang.ref.WeakReference<DocumentRoot>(null)
    private var activeSavedPending = java.lang.ref.WeakReference<DocumentCommand.Canvas>(null)
    internal fun adopt(opened: OpenedDocument): PagedPreviewNotebook {
        executor.checkOwnerThread()
        check(!ownerRetiring) { "Store owner is retiring" }
        require(opened.root === opened.recovery.root) { "Checkpoint/root mismatch" }
        activeNotebook?.let { check(it.documentId == opened.root.id.value); return it }
        val result = PagedPreviewNotebook.openRecovery(opened.recovery, UUID.randomUUID().toString(),
            DocumentBudget(journalEntries = 4096, journalBytes = 32L * 1024 * 1024, identityBytes = 4L * 1024 * 1024),
            executor::checkOwnerThread)
        // This internal entry receives only the reader-validated root and fixed supported budget.
        val note = checkNotNull(result.notebook) { "Unsupported preview adoption: ${result.failure}/${result.documentReason}" }
        activeNotebook = note
        activeSavedRoot = java.lang.ref.WeakReference(opened.root.takeIf { opened.persisted && ledger.latest(note.documentId) == null })
        activeSavedPending = java.lang.ref.WeakReference(opened.recovery.pending.takeIf { opened.persisted && ledger.latest(note.documentId) == null })
        return note
    }
    internal fun create(): PagedPreviewNotebook {
        executor.checkOwnerThread()
        check(!ownerRetiring) { "Store owner is retiring" }
        check(activeNotebook == null) { "Release the previous owner only after snapshot admission" }
        val result = PagedPreviewNotebook.createValidated(UUID.randomUUID().toString(), "未命名笔记", UUID.randomUUID().toString(),
            budget = DocumentBudget(journalEntries = 4096, journalBytes = 32L * 1024 * 1024, identityBytes = 4L * 1024 * 1024),
            ownerCheck = executor::checkOwnerThread)
        val note = checkNotNull(result.notebook) { "Unsupported preview creation: ${result.failure}/${result.documentReason}" }
        activeNotebook = note
        activeSavedRoot.clear(); activeSavedPending.clear()
        return note
    }
    internal fun attachActive(note: PagedPreviewNotebook) {
        executor.checkOwnerThread()
        check(!ownerRetiring) { "Store owner is retiring" }
        check(activeNotebook == null || activeNotebook === note) { "Release the previous owner only after snapshot admission" }
        activeNotebook = note
    }
    internal fun releaseActive(note: PagedPreviewNotebook, lostEndpoint: Boolean = false): Boolean {
        executor.checkOwnerThread(); check(activeNotebook === note)
        if (note.hasPendingStroke) {
            if (activeSavedRoot.get() !== note.snapshot() || activeSavedPending.get() !== note.pendingStroke() || ledger.latest(note.documentId) != null) return false
            check(note.retireAfterRecovery(note.captureRecovery()))
        } else check(note.retire())
        retireInk(lostEndpoint); activeNotebook = null; activeSavedRoot.clear(); activeSavedPending.clear()
        return true
    }
    // One app-owner capture while the external picker runs. No timeout/Activity destruction release.
    internal val exportPickerSession = UUID.randomUUID().toString()
    private var nextExportRequestCode = 1000
    private var pendingExport: Pair<Int, PagedPreviewSnapshot>? = null
    internal val exportReservedByteCharge: Long get() {
        executor.checkOwnerThread(); return pendingExport?.second?.retainedByteCharge ?: 0L
    }
    internal fun beginExportPicker(snapshot: PagedPreviewSnapshot): ExportPickerAdmission {
        executor.checkOwnerThread()
        if (ownerRetiring) return ExportPickerAdmission(ExportPickerDisposition.OWNER_CLOSED)
        if (snapshot.pending != null) return ExportPickerAdmission(ExportPickerDisposition.PENDING_RECOVERY)
        if (pendingExport != null) return ExportPickerAdmission(ExportPickerDisposition.BUSY)
        if (snapshot.retainedByteCharge > exportPickerByteLimit) return ExportPickerAdmission(ExportPickerDisposition.CAPACITY)
        if (nextExportRequestCode > 65535) return ExportPickerAdmission(ExportPickerDisposition.IDENTITY_EXHAUSTED)
        val code = nextExportRequestCode++
        pendingExport = code to snapshot
        return ExportPickerAdmission(ExportPickerDisposition.READY, code)
    }
    /** Mismatched/duplicate results cannot release or substitute another accepted capture. */
    internal fun takeExportPicker(requestCode: Int): PagedPreviewSnapshot? {
        executor.checkOwnerThread()
        val capture = pendingExport?.takeIf { it.first == requestCode } ?: return null
        pendingExport = null; return capture.second
    }
    init { executor.checkOwnerThread(); require(exportPickerByteLimit >= 0) }
    private fun requestPump() {
        if (!wakePending.compareAndSet(false, true)) return
        if (!main.post {
            wakePending.set(false)
            executor.drainResults(16)
            pumpInk()
            pumpWrites()
            advanceOwnerRetirement()
            if (executor.status().entries.firstOrNull()?.stage == OwnedWorkStage.READY) requestPump()
        }) { wakePending.set(false); error("Storage owner event loop unavailable") }
    }
    private data class InkKey(val document: String, val page: String, val revision: Long,
        val surface: String, val width: Int, val height: Int, val x: Double, val y: Double, val scale: Double)
    private var inkRenderer: AndroidInkFrameExperiment? = null
    private var inkImage: AndroidInkFrameExperiment.SurfaceImage? = null
    private var inkPending: AndroidInkFrameExperiment.Request? = null
    private var inkKey: InkKey? = null
    private var inkView = java.lang.ref.WeakReference<InkCanvasView>(null)
    private var inkRetiring = false
    private var inkShown = false
    private var inkGeneration = 0L
    private var inkPollScheduled = false
    private var observedCallbacks = 0
    internal var inkFramePublications = 0; private set
    internal var inkFrameDraws = 0; private set
    internal var inkReleaseCallbacks = 0; private set
    internal var inkValidFenceObserved = false; private set
    internal var inkHandoffRecovered = false; private set
    internal var inkReleaseDeferred = false; private set
    internal val inkChargedBytes: Long get() = inkRenderer?.chargedBytes ?: 0
    internal val inkOwnerRetained: Boolean get() = inkRenderer != null
    private fun pollInkLater() {
        if (inkPollScheduled) return
        inkPollScheduled = true
        main.postDelayed({ inkPollScheduled = false; requestPump() },16)
    }
    private fun pumpInk() {
        executor.checkOutsideDrain()
        val renderer = inkRenderer ?: return
        // A lost presentation endpoint must retire on this same owner, including late worker results.
        // Keep the old pool/image charged until their original receipts drain; never create a replacement here.
        if (inkView.get() == null) {
            if (!inkRetiring) retireInk(lostEndpoint = true)
            else inkImage?.closeWithoutView() // Upgrade a prior known on-draw removal on this same image.
        }
        renderer.settleAfterDrain { request, ack ->
            if (request === inkPending) {
                inkPending = null
                val view = inkView.get(); val key = inkKey
                if (!inkRetiring && ack == "PUBLISHED" && view != null && key != null && key == keyFor(view)) {
                    val transform = android.graphics.Matrix().apply {
                        setValues(floatArrayOf(key.scale.toFloat(),0f,(key.x*-key.scale).toFloat(),
                            0f,key.scale.toFloat(),(key.y*-key.scale).toFloat(),0f,0f,1f))
                    }
                    val rect = android.graphics.RectF(key.x.toFloat(),key.y.toFloat(),
                        (key.x+key.width/key.scale).toFloat(),(key.y+key.height/key.scale).toFloat())
                    val page = view.notebook.currentPage
                    inkImage = renderer.prepare(request,view::paintStaticPaper,transform,rect,
                        android.graphics.RectF(0f,0f,page.width.toFloat(),page.height.toFloat()))
                    observedCallbacks = 0
                }
            }
        }
        inkImage?.let { image ->
            image.poll()
            if (image.callbackCount > observedCallbacks) {
                inkReleaseCallbacks += image.callbackCount-observedCallbacks; observedCallbacks = image.callbackCount
                logInk("release-receipt")
            }
            inkValidFenceObserved = inkValidFenceObserved || image.validFenceObserved
            inkHandoffRecovered = inkHandoffRecovered || image.handoffRecovered
            inkReleaseDeferred = inkReleaseDeferred || image.releaseDeferred
            if (image.released) { inkImage = null; inkShown = false; logInk("source-released") }
            else if (image.needsPoll) pollInkLater()
        }
        if (inkRetiring && renderer.isDrained) {
            inkRenderer = null; inkRetiring = false; logInk("owner-drained")
        }
        inkView.get()?.invalidate()
    }
    private fun logInk(event: String) {
        if (ioDebug) Log.d("NoteCoreRender","$event charged=$inkChargedBytes")
    }
    private val ioDebug = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    @android.annotation.TargetApi(33) // Only called after draw's API gate or by its existing renderer pump.
    private fun keyFor(view: InkCanvasView): InkKey? {
        val v = view.viewport; val host = view.surfaceHost ?: return null
        if (activeNotebook !== view.notebook || host.child?.isValid != true || view.width !in 1..4096 || view.height !in 1..4096 ||
            view.width.toLong()*view.height > 4194304 || !v.scale.isFinite() || 1/v.scale !in 0.0001..1e6 ||
            !v.offsetX.isFinite() || !v.offsetY.isFinite()) return null
        return InkKey(view.notebook.documentId,view.notebook.currentPageId.value,view.notebook.changeVersion,
            host.identity,view.width,view.height,-v.offsetX/v.scale,-v.offsetY/v.scale,v.scale)
    }
    @android.annotation.TargetApi(33)
    internal fun drawCommittedInk(view: InkCanvasView, canvas: android.graphics.Canvas): Boolean {
        executor.checkOwnerThread()
        if (ownerRetiring) return false
        if (android.os.Build.VERSION.SDK_INT < 33 || !canvas.isHardwareAccelerated) return false
        val key = keyFor(view) ?: return false
        if (inkView.get() !== view) { retireInk(); inkView = java.lang.ref.WeakReference(view) }
        if (inkRetiring) return false
        if (inkKey != null && inkKey != key) { retireInk(); return false }
        if (inkRenderer == null) {
            if (inkGeneration == Long.MAX_VALUE) return false
            inkGeneration++
            val scene = view.notebook.snapshot().scene
            val frame = FrameToken(scene.id,scene.revision,inkGeneration,inkGeneration,inkGeneration,inkGeneration)
            inkRenderer = AndroidInkFrameExperiment(frame,view.notebook.currentPage.layerId,executor,::requestPump,
                beforeInkCompute,afterInkNativeClose,afterInkBufferSubmitted,maySettleInkRelease)
            inkKey = key
            inkPending = inkRenderer!!.submit(scene,frame,view.notebook.currentPage.layerId,key.x,key.y,
                key.width,key.height,1/key.scale)
        }
        val image = inkImage ?: return false
        if (!image.ready) return false
        if (!inkShown) {
            val child = view.surfaceHost?.child ?: return false
            val root = view.rootSurfaceControl ?: return false
            if (!image.schedule(child,root)) { retireInk(); return false }
            inkShown = true; inkFramePublications++
            if (ioDebug) Log.d("NoteCoreRender","frame-scheduled w=${key.width} h=${key.height} charged=$inkChargedBytes backend=surface-control")
        }
        inkFrameDraws++
        return true
    }
    internal fun detachInk(view: InkCanvasView) {
        executor.checkOwnerThread()
        if (inkView.get() === view) retireInk()
    }
    private fun retireInk(lostEndpoint: Boolean = false) {
        executor.checkOwnerThread()
        inkShown = false; inkKey = null; inkPending = null
        inkImage?.let { if (lostEndpoint) it.closeWithoutView() else it.close(); if (it.released) inkImage = null }
        inkRenderer?.let { it.close(); inkRetiring = true }
        inkView.get()?.invalidate()
        requestPump()
    }
    internal fun reconcileInkRelease(): Boolean {
        executor.checkOutsideDrain()
        if (inkImage?.reconcileReleased() != true) return false
        inkImage = null; pumpInk(); advanceOwnerRetirement(); return true
    }
    private fun notifyUi(action: () -> Unit) {
        // Ownership was transferred to ledger / durable file before this advisory UI notification.
        runCatching(action).onFailure { Log.e("NoteCoreStorage", "UI notification failed after storage handoff", it) }
    }
    fun save(snapshot: PagedPreviewSnapshot, callback: (Long, String?) -> Unit): Boolean {
        executor.checkOwnerThread()
        if (ownerRetiring) {
            notifyUi { callback(snapshot.version, "当前会话已结束，原内容仍保留") }; return false
        }
        val active = activeNotebook
        if (active?.documentId == snapshot.documentId && active.hasPendingStroke &&
            (snapshot.root !== active.snapshot() || snapshot.pending !== active.pendingStroke())) {
            notifyUi { callback(snapshot.version, "仍有未提交笔迹，请保留完整恢复记录后重试") }; return false
        }
        if (activeNotebook?.documentId == snapshot.documentId && activeSavedRoot.get() === snapshot.root &&
            activeSavedPending.get() === snapshot.pending && ledger.latest(snapshot.documentId) == null) {
            notifyUi { callback(snapshot.version, null) }; return true
        }
        val previous = ledger.latest(snapshot.documentId)
        val admission = ledger.offer(snapshot)
        if (admission != PreviewSaveAdmission.ACCEPTED) {
            notifyUi { callback(snapshot.version, if (admission == PreviewSaveAdmission.VERSION_CONFLICT)
                "保存内容发生冲突，请保留当前笔记并导出备份" else "保存队列已满或内容版本过期，请保留当前笔记并重试") }
            return false
        }
        if (previous == null || !previous.sameContent(snapshot)) viewEpoch++
        saveCallbacks[snapshot.documentId] = SaveCallback(ledger.latest(snapshot.documentId) as PagedPreviewSnapshot, callback)
        pumpWrites()
        return true
    }
    private fun pumpWrites() {
        executor.checkOwnerThread()
        while (true) {
            val worker = executor.status()
            val dispatch = ledger.admitNextWrite(STORAGE_WORK_BYTES - worker.chargedBytes, STORAGE_WORK_BYTES,
                worker.accepting && worker.workerFailure == null)
            val attempt = dispatch.attempt
            if (attempt == null) {
                if (dispatch.disposition != PreviewWriteDisposition.IDLE && dispatch.disposition != PreviewWriteDisposition.BACKPRESSURE) {
                    val ids = if (dispatch.disposition == PreviewWriteDisposition.WORK_CAPACITY)
                        dispatch.documentId?.let(::listOf).orEmpty() else ledger.states()
                            .filter { !it.inFlight && !it.retryRequired }.map { it.snapshot.documentId }
                    // Terminal notification relinquishes only the advisory UI receiver; ledger keeps content.
                    for (id in ids) saveCallbacks.remove(id)?.let { receiver ->
                        notifyUi { receiver.receive(receiver.snapshot.version, if (dispatch.disposition == PreviewWriteDisposition.WORK_CAPACITY)
                            saveTaskTooLarge else "保存后端暂不可用，更改仍保留，请重试") }
                    }
                }
                return
            }
            val admission = executor.submit(attempt to io, attempt.snapshot.encodingWorkByteCharge, {
                it.second.write(it.first.snapshot); Unit
            }) { input, result ->
                val original = input.first
                val success = result is WorkResult.Succeeded
                check(ledger.complete(original, success))
                val id = original.snapshot.documentId
                if (success && activeNotebook?.documentId == id) {
                    val captured = original.snapshot as PagedPreviewSnapshot
                    activeSavedRoot = java.lang.ref.WeakReference(captured.root)
                    activeSavedPending = java.lang.ref.WeakReference(captured.pending)
                }
                saveCallbacks[id]?.takeIf { it.snapshot.sameContent(original.snapshot) }?.let { receiver ->
                    saveCallbacks.remove(id)
                    notifyUi { receiver.receive(original.snapshot.version, if (success) null else "保存失败，更改仍在本机会话中，请重试保存") }
                }
                ResultDisposition.ACKNOWLEDGED // Failure content is retained in ledger before ACK.
            }
            if (admission is WorkAdmission.Rejected) {
                check(ledger.returnUnstarted(attempt))
                // Already admitted snapshot stays pending; a completing job wakes and pumps it later.
                if (admission.reason == WorkAdmissionFailure.WORKER_TERMINATED || admission.reason == WorkAdmissionFailure.STOPPED) {
                    val id = attempt.snapshot.documentId
                    saveCallbacks[id]?.takeIf { it.snapshot.sameContent(attempt.snapshot) }?.let { receiver ->
                        saveCallbacks.remove(id)
                        notifyUi { receiver.receive(receiver.snapshot.version, "保存后端暂不可用，更改仍保留，请重试") }
                    }
                }
                return
            }
        }
    }
    private fun <I : Any, R : Any> request(input: I, bytes: Long, compute: (I) -> R,
                                          rejected: () -> Unit, receive: (WorkResult<R>) -> Unit) {
        executor.checkOwnerThread()
        if (ownerRetiring) { notifyUi(rejected); return }
        requestsInFlight++
        val admission = executor.submit(input, bytes, compute) { _, result ->
            requestsInFlight--
            notifyUi { receive(result) }; ResultDisposition.ACKNOWLEDGED
        }
        if (admission is WorkAdmission.Rejected) { requestsInFlight--; notifyUi(rejected) }
    }
    fun export(uri: Uri, snapshot: PagedPreviewSnapshot, callback: (String?) -> Unit) {
        request(Triple(uri, snapshot, io), snapshot.encodingWorkByteCharge, { input ->
            input.third.export(input.first, input.second); Unit
        }, { callback("后台队列繁忙，请重新导出") }) { result ->
            callback(if (result is WorkResult.Succeeded) null else "导出失败，目标文件可能不完整，请重新导出")
        }
    }
    fun importCopy(uri: Uri, callback: (OpenedDocument?, String?) -> Unit) {
        request(uri to io, READ_WORK_BYTES, { input -> input.second.importCopy(input.first) },
            { callback(null, "后台队列繁忙，请重新导入") }) { result ->
            callback((result as? WorkResult.Succeeded)?.value?.let { OpenedDocument(it, true) },
                if (result is WorkResult.Succeeded) null else "导入失败：文件无效、超过32MB或本机无法保存；已有笔记未覆盖")
        }
    }
    fun list(callback: (List<NoteSummary>, String?) -> Unit) {
        request(io, 2L * 1024 * 1024, { it.catalog() }, { callback(retainedSummaries(), "后台队列繁忙，请重新打开列表") }) { result ->
            val disk = (result as? WorkResult.Succeeded)?.value.orEmpty()
            // Overlay current owner captures at delivery, never read ledger from the worker.
            val retained = retainedSummaries(); val ids = retained.map { it.id }.toSet()
            callback((disk.filter { it.id !in ids } + retained).sortedByDescending { it.modified },
                if (result is WorkResult.Succeeded) null else "列表读取失败，原文件与未保存内容仍保留")
        }
    }
    private fun retainedSummaries(): List<NoteSummary> = ledger.states().map {
        NoteSummary(it.snapshot.documentId, it.snapshot.title + "（未保存）", System.currentTimeMillis())
    }
    fun open(id: String, callback: (OpenedDocument?, String?) -> Unit) {
        executor.checkOwnerThread()
        activeNotebook?.takeIf { it.documentId == id }?.let { callback(OpenedDocument(it.snapshot(), activeSavedRoot.get() === it.snapshot() && activeSavedPending.get() === it.pendingStroke(), it.captureRecovery()), null); return }
        val retained = ledger.latest(id); val epoch = viewEpoch
        request(Triple(id, retained, io), READ_WORK_BYTES, { input ->
            if (input.second == null) input.third.readOutcome(input.first) else input.third.acceptRecovery(input.second as PagedPreviewSnapshot, input.first)
        }, { callback(null, "后台队列繁忙，请重新打开笔记") }) { result ->
            if (viewEpoch != epoch) {
                // No mutable/read payload is posted; retry a pure read after this result is ACKed.
                main.post { open(id, callback) }
            } else when (result) {
                is WorkResult.Succeeded -> when (val reading = result.value) {
                    is LocalRecoveryRead.Opened -> callback(OpenedDocument(reading.snapshot.root, retained == null, reading.snapshot),
                        retained?.let { "有更改尚未保存，请点击保存重试" })
                    is LocalRecoveryRead.Refused -> callback(null, reading.reason.message)
                }
                else -> callback(null, "读取结果暂无法确认，请稍后重试")
            }
        }
    }
    internal fun saveStates(): List<PreviewSaveState> { executor.checkOwnerThread(); return ledger.states() }
    internal fun stopTestBackend() { executor.stopAccepting() }
    companion object {
        private const val STORAGE_WORK_BYTES = 256L * 1024 * 1024
        private const val READ_WORK_BYTES = 160L * 1024 * 1024 // Declared parsing/UTF/buffer reservation, not measured heap.
        @Volatile private var instance: NoteStore? = null
        fun get(context: Context): NoteStore = instance ?: synchronized(this) {
            instance ?: NoteStore(context.applicationContext).also { instance = it }
        }
        internal fun isolated(context: Context, directory: File, beforeInkCompute: () -> Unit = {},
            afterInkNativeClose: () -> Unit = {}, afterInkBufferSubmitted: () -> Unit = {},
            maySettleInkRelease: () -> Boolean = { true }, exportPickerByteLimit: Long = 8L * 1024 * 1024, beforeOpenRead: () -> Unit = {}): NoteStore =
            NoteStore(context,directory,beforeInkCompute,afterInkNativeClose,afterInkBufferSubmitted,maySettleInkRelease,exportPickerByteLimit,beforeOpenRead)
    }
}
