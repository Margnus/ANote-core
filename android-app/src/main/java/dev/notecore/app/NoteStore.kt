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

data class OpenedDocument(val root: DocumentRoot, val persisted: Boolean)

data class NoteSummary(val id: String, val title: String, val modified: Long)

/** App lifetime storage owner is the main thread. Only immutable captures / exclusive read results cross IO. */
@OptIn(ExperimentalAndroidInkFrame::class)
class NoteStore private constructor(context: Context, private val directory: File = File(context.filesDir, "preview-notes"),
    private val beforeInkCompute: () -> Unit = {}, private val afterInkNativeClose: () -> Unit = {},
    private val afterInkBufferSubmitted: () -> Unit = {}, private val maySettleInkRelease: () -> Boolean = { true }) {
    private val io = PreviewFileIO(directory, context.applicationContext.contentResolver)
    private val main = Handler(Looper.getMainLooper())
    private val wakePending = AtomicBoolean()
    private val executor = BoundedOwnerExecutor(16, 256L * 1024 * 1024, "NoteCore-preview-storage", ::requestPump)
    private val ledger = PreviewSaveLedger()
    private data class SaveCallback(val version: Long, val receive: (Long, String?) -> Unit)
    private val saveCallbacks = mutableMapOf<String, SaveCallback>()
    private var viewEpoch = 0L
    private var activeNotebook: PagedPreviewNotebook? = null
    internal val activeDocumentId: String? get() { executor.checkOwnerThread(); return activeNotebook?.documentId }
    private var activeSavedRoot: DocumentRoot? = null
    internal fun adopt(opened: OpenedDocument): PagedPreviewNotebook {
        executor.checkOwnerThread()
        activeNotebook?.let { check(it.documentId == opened.root.id.value); return it }
        val note = PagedPreviewNotebook(opened.root, UUID.randomUUID().toString(),
            DocumentBudget(journalEntries = 4096, journalBytes = 32L * 1024 * 1024, identityBytes = 4L * 1024 * 1024),
            executor::checkOwnerThread)
        activeNotebook = note
        activeSavedRoot = opened.root.takeIf { opened.persisted && ledger.latest(note.documentId) == null }
        return note
    }
    internal fun create(): PagedPreviewNotebook {
        executor.checkOwnerThread()
        val nonce = UUID.randomUUID().toString()
        return adopt(OpenedDocument(DocumentRoot.empty(SceneId(UUID.randomUUID().toString()), "未命名笔记",
            PageDescriptor(PageId("$nonce-page"), LayerId("$nonce-layer"), 595.0, 842.0)), false))
    }
    internal fun attachActive(note: PagedPreviewNotebook) {
        executor.checkOwnerThread()
        check(activeNotebook == null || activeNotebook === note) { "Release the previous owner only after snapshot admission" }
        activeNotebook = note
    }
    internal fun releaseActive(note: PagedPreviewNotebook) {
        executor.checkOwnerThread(); check(activeNotebook === note); retireInk(); check(note.retire()); activeNotebook = null; activeSavedRoot = null
    }
    var exportSnapshot: PagedPreviewSnapshot? = null
        get() { executor.checkOwnerThread(); return field }
        set(value) { executor.checkOwnerThread(); field = value }
    init { executor.checkOwnerThread() }
    private fun requestPump() {
        if (!wakePending.compareAndSet(false, true)) return
        if (!main.post {
            wakePending.set(false)
            executor.drainResults(16)
            pumpInk()
            pumpWrites()
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
    private fun retireInk() {
        executor.checkOwnerThread()
        inkShown = false; inkKey = null; inkPending = null
        inkImage?.let { it.close(); if (it.released) inkImage = null }
        inkRenderer?.let { it.close(); inkRetiring = true }
        inkView.get()?.invalidate()
        requestPump()
    }
    internal fun reconcileInkRelease(): Boolean {
        executor.checkOwnerThread()
        if (inkImage?.reconcileReleased() != true) return false
        inkImage = null; pumpInk(); return true
    }
    private fun notifyUi(action: () -> Unit) {
        // Ownership was transferred to ledger / durable file before this advisory UI notification.
        runCatching(action).onFailure { Log.e("NoteCoreStorage", "UI notification failed after storage handoff", it) }
    }
    fun save(snapshot: PagedPreviewSnapshot, callback: (Long, String?) -> Unit): Boolean {
        executor.checkOwnerThread()
        if (activeNotebook?.documentId == snapshot.documentId && activeSavedRoot === snapshot.root && ledger.latest(snapshot.documentId) == null) {
            notifyUi { callback(snapshot.version, null) }; return true
        }
        val previous = ledger.latest(snapshot.documentId)
        val admission = ledger.offer(snapshot)
        if (admission != PreviewSaveAdmission.ACCEPTED) {
            notifyUi { callback(snapshot.version, if (admission == PreviewSaveAdmission.VERSION_CONFLICT)
                "保存内容发生冲突，请保留当前笔记并导出备份" else "保存队列已满或内容版本过期，请保留当前笔记并重试") }
            return false
        }
        if (previous?.version != snapshot.version) viewEpoch++
        saveCallbacks[snapshot.documentId] = SaveCallback(snapshot.version, callback)
        pumpWrites()
        return true
    }
    private fun pumpWrites() {
        executor.checkOwnerThread()
        while (true) {
            val attempt = ledger.nextWrite() ?: return
            val admission = executor.submit(attempt to io, attempt.snapshot.encodingWorkByteCharge, {
                it.second.write(it.first.snapshot); Unit
            }) { input, result ->
                val original = input.first
                val success = result is WorkResult.Succeeded
                check(ledger.complete(original, success))
                val id = original.snapshot.documentId
                if (success && activeNotebook?.documentId == id) activeSavedRoot = (original.snapshot as PagedPreviewSnapshot).root
                saveCallbacks[id]?.takeIf { it.version == original.snapshot.version }?.let { receiver ->
                    saveCallbacks.remove(id)
                    notifyUi { receiver.receive(original.snapshot.version, if (success) null else "保存失败，更改仍在本机会话中，请重试保存") }
                }
                ResultDisposition.ACKNOWLEDGED // Failure content is retained in ledger before ACK.
            }
            if (admission is WorkAdmission.Rejected) {
                check(ledger.returnUnstarted(attempt))
                // Already admitted snapshot stays pending; a completing job wakes and pumps it later.
                // An individually oversized encoding cannot fit: surface it and retain for recovery.
                if (attempt.snapshot.encodingWorkByteCharge > 256L * 1024 * 1024 ||
                    admission.reason == WorkAdmissionFailure.WORKER_TERMINATED || admission.reason == WorkAdmissionFailure.STOPPED) {
                    notifyUi { saveCallbacks[attempt.snapshot.documentId]?.receive?.invoke(attempt.snapshot.version,
                        "保存后端暂不可用，更改仍保留，请重试") }
                }
                return
            }
        }
    }
    private fun <I : Any, R : Any> request(input: I, bytes: Long, compute: (I) -> R,
                                          rejected: () -> Unit, receive: (WorkResult<R>) -> Unit) {
        executor.checkOwnerThread()
        val admission = executor.submit(input, bytes, compute) { _, result ->
            notifyUi { receive(result) }; ResultDisposition.ACKNOWLEDGED
        }
        if (admission is WorkAdmission.Rejected) notifyUi(rejected)
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
        activeNotebook?.takeIf { it.documentId == id }?.let { callback(OpenedDocument(it.snapshot(), activeSavedRoot === it.snapshot()), null); return }
        val retained = ledger.latest(id); val epoch = viewEpoch
        request(Triple(id, retained, io), READ_WORK_BYTES, { input ->
            if (input.second == null) input.third.read(input.first) else (input.second as PagedPreviewSnapshot).root
        }, { callback(null, "后台队列繁忙，请重新打开笔记") }) { result ->
            if (viewEpoch != epoch) {
                // No mutable/read payload is posted; retry a pure read after this result is ACKed.
                main.post { open(id, callback) }
            } else callback((result as? WorkResult.Succeeded)?.value?.let { OpenedDocument(it, retained == null) },
                if (result !is WorkResult.Succeeded) "笔记读取失败或含暂不支持的笔刷，原文件已保留"
                else retained?.let { "有更改尚未保存，请点击保存重试" })
        }
    }
    internal fun saveStates(): List<PreviewSaveState> { executor.checkOwnerThread(); return ledger.states() }
    internal fun stopTestBackend() { executor.stopAccepting() }
    companion object {
        private const val READ_WORK_BYTES = 160L * 1024 * 1024 // Declared parsing/UTF/buffer reservation, not measured heap.
        @Volatile private var instance: NoteStore? = null
        fun get(context: Context): NoteStore = instance ?: synchronized(this) {
            instance ?: NoteStore(context.applicationContext).also { instance = it }
        }
        internal fun isolated(context: Context, directory: File, beforeInkCompute: () -> Unit = {},
            afterInkNativeClose: () -> Unit = {}, afterInkBufferSubmitted: () -> Unit = {},
            maySettleInkRelease: () -> Boolean = { true }): NoteStore =
            NoteStore(context,directory,beforeInkCompute,afterInkNativeClose,afterInkBufferSubmitted,maySettleInkRelease)
    }
}
