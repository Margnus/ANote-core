package dev.notecore.document

import dev.notecore.canvas.prototype.*

/** UI-owner preview session. Exactly one authority; selected-page projection is a read-only cache. */
class PagedPreviewNotebook @Throws(IllegalArgumentException::class) constructor(initial: DocumentRoot, private val sessionNonce: String,
                           budget: DocumentBudget = DocumentBudget(), private val ownerCheck: () -> Unit = {}) {
    private val memory = MemoryDocumentAuthority(initial, budget, ownerCheck)
    private val inputObjectLimit = budget.maxObjects
    private val inputByteLimit = budget.inputBytes
    private var selected = initial.page(0).id
    private var sequence = 0L
    private val initialIds = (initial.pages().flatMap { listOf(it.id.value, it.layerId.value) } + initial.scene.objects().map { it.id.value }).toSet()
    private var input: InputReplaySession? = null
    private var inputSequence = 0L
    private var strokePage: PageId? = null
    private var strokeCommand: CommandId? = null
    private var strokeObject: ObjectId? = null
    private var pending: DocumentCommand.Canvas? = null
    private var color = 0xff253247.toInt()
    private var width = 2.5
    private var error = ""
    private var failure: PreviewSessionFailure? = null
    private var retired = false
    private var projectionRevision = -1L
    private var projectionPage: PageId? = null
    private var projection: List<StrokeObject> = emptyList()
    init {
        ownerCheck()
        require(sessionNonce.length in 1..48 && sessionNonce.all { it.isLetterOrDigit() && it.code < 128 || it == '-' })
        require(budget.maxPages <= PagedPreviewCodec.MAX_PAGES && budget.maxObjects <= PagedPreviewCodec.MAX_OBJECTS && budget.liveBytes <= 8L * 1024 * 1024)
        PagedPreviewCodec.retainedByteCharge(initial)
    }
    private fun root(): DocumentRoot { ownerCheck(); return memory.snapshot() }
    val documentId: String get() = root().id.value
    val title: String get() = root().title
    val changeVersion: Long get() = root().revision
    val pageCount: Int get() = root().pageCount
    val currentPageId: PageId get() { ownerCheck(); return selected }
    val currentPageIndex: Int get() = root().pages().indexOfFirst { it.id == selected }
    val currentPage: PageDescriptor get() = root().findPage(selected)!!
    val totalStrokeCount: Int get() = root().scene.objectCount
    val strokeCount: Int get() = visible().size
    val lastError: String get() { ownerCheck(); return error }
    val lastFailure: PreviewSessionFailure? get() { ownerCheck(); return failure }
    val hasActiveStroke: Boolean get() { ownerCheck(); return input != null }
    val hasPendingStroke: Boolean get() { ownerCheck(); return pending != null }
    /** Input ownership only; document/history/save/render budgets remain separate. */
    val inputReservedByteCharge: Long get() {
        ownerCheck()
        return input?.reservedByteCharge ?: (pending?.command as? CanvasCommand.AddStroke)?.samples?.logicalByteCharge ?: 0L
    }
    val canUndo: Boolean get() { ownerCheck(); return !retired && input == null && pending == null && memory.status().nextUndoOperationId != null }
    val canRedo: Boolean get() { ownerCheck(); return !retired && input == null && pending == null && memory.status().nextRedoOperationId != null }
    fun snapshot(): DocumentRoot = root()
    fun page(index: Int): PageDescriptor = root().page(index)
    fun authorityStatus(): DocumentAuthorityStatus { ownerCheck(); return memory.status() }
    private fun clearProjection() { projection = emptyList(); projectionRevision = -1L; projectionPage = null }
    private fun visible(): List<StrokeObject> {
        val root = root()
        if (projectionRevision != root.revision || projectionPage != selected) {
            projection = root.pageObjects(selected); projectionRevision = root.revision; projectionPage = selected
        }
        return projection
    }
    fun stroke(index: Int): StrokeObject = visible()[index]
    fun strokeId(index: Int): String = stroke(index).id.value
    fun strokeColor(index: Int): Int = stroke(index).brush.argb
    fun strokeWidth(index: Int): Double = stroke(index).brush.width
    fun pointCount(index: Int): Int = stroke(index).samples.size
    fun pointX(index: Int, point: Int): Double = stroke(index).let { it.transform.a * it.samples.x(point) + it.transform.c * it.samples.y(point) + it.transform.tx }
    fun pointY(index: Int, point: Int): Double = stroke(index).let { it.transform.b * it.samples.x(point) + it.transform.d * it.samples.y(point) + it.transform.ty }
    fun pointPressure(index: Int, point: Int): Float = stroke(index).samples.pressure(point)
    private fun clearError() { error = ""; failure = null }
    private fun fail(message: String, kind: PreviewFailureKind, documentReason: DocumentRejection? = null): Boolean {
        error = message; failure = PreviewSessionFailure(kind, documentReason); return false
    }
    /** All request payloads are raw values. Validation precedes cancellation or identity allocation. */
    fun edit(request: PreviewEdit): PreviewEditResult {
        ownerCheck()
        if (retired) {
            fail("此会话已关闭，请重新打开笔记", PreviewFailureKind.CLOSED)
            return PreviewEditResult(false, failure, root().revision)
        }
        val accepted = when (request) {
            is PreviewEdit.Rename -> rename(request.title)
            is PreviewEdit.SetPaper -> setPaper(request.width, request.height, request.paper)
            is PreviewEdit.SelectPen -> selectPen(request.argb, request.width)
            is PreviewEdit.SelectPage -> {
                if (!validPageId(request.pageId)) fail("页面编号无效", PreviewFailureKind.INVALID_ARGUMENT)
                else selectPage(PageId(request.pageId))
            }
            is PreviewEdit.MovePage -> {
                if (request.beforePageId != null && !validPageId(request.beforePageId)) fail("页面编号无效", PreviewFailureKind.INVALID_ARGUMENT)
                else moveCurrentPage(request.beforePageId?.let(::PageId))
            }
            PreviewEdit.InsertPage -> insertPage()
            PreviewEdit.CopyPage -> copyCurrentPage()
            PreviewEdit.DeletePage -> deleteCurrentPage()
            PreviewEdit.Undo -> undo()
            PreviewEdit.Redo -> redo()
        }
        if (accepted) clearError()
        check(accepted || failure != null)
        return PreviewEditResult(accepted, failure, root().revision)
    }
    private fun validPageId(value: String) = value.isNotBlank() && value.length <= 128 && value.hasValidUnicode()
    private fun nextIdentity(kind: String, after: Long = sequence): Pair<String, Long>? {
        var next = after
        while (next < Long.MAX_VALUE) {
            val candidate = "$sessionNonce-$kind-${++next}"
            if (candidate !in initialIds) return candidate to next
        }
        return null
    }
    private fun identity(kind: String): String? {
        nextIdentity(kind)?.let { (candidate, next) -> sequence = next; return candidate }
        fail("此会话编号已耗尽，请保留笔记并导出备份", PreviewFailureKind.IDENTITY_EXHAUSTED); return null
    }
    private fun operation(): CommandId? = identity("op")?.let(::CommandId)
    private fun prepareEdit(): Boolean {
        ownerCheck()
        if (retired) return fail("此会话已关闭，请重新打开笔记", PreviewFailureKind.CLOSED)
        if (pending != null) return fail("上一笔尚未提交，请先重试保存", PreviewFailureKind.PENDING_STROKE)
        clearError(); return true
    }
    private fun commit(command: DocumentCommand): Boolean = when (val result = memory.commit(command)) {
        is DocumentCommit.Accepted -> { clearProjection(); cancelStroke(); clearError(); true }
        is DocumentCommit.Rejected -> fail(when (result.reason) {
            DocumentRejection.JOURNAL_CAPACITY, DocumentRejection.HISTORY_CAPACITY, DocumentRejection.LIVE_BYTE_CAPACITY,
            DocumentRejection.IDENTITY_CAPACITY, DocumentRejection.OBJECT_CAPACITY, DocumentRejection.PAGE_CAPACITY -> "当前预览已达到编辑容量，请保留笔记并导出备份"
            else -> "操作未完成，原内容仍保留，请重试"
        }, PreviewFailureKind.DOCUMENT_REJECTED, result.reason)
    }
    fun selectPage(id: PageId): Boolean {
        ownerCheck()
        if (retired) return fail("此会话已关闭，请重新打开笔记", PreviewFailureKind.CLOSED)
        if (root().findPage(id) == null) return fail("页面不存在", PreviewFailureKind.MISSING_PAGE)
        if (id == selected) { clearError(); return true }
        if (!prepareEdit()) return false
        cancelStroke(); clearProjection(); selected = id; clearError(); return true // Presentation-only; no root commit.
    }
    fun insertPage(): Boolean {
        if (!prepareEdit()) return false
        val root = root(); val source = currentPage
        val page = PageId(identity("page") ?: return false); val layer = LayerId(identity("layer") ?: return false)
        val op = operation() ?: return false
        val before = root.pages().getOrNull(currentPageIndex + 1)?.id
        if (!commit(DocumentCommand.InsertPage(op, root.id, root.revision, PageDescriptor(page, layer, source.width, source.height, source.paper), before))) return false
        selected = page; return true
    }
    fun moveCurrentPage(before: PageId?): Boolean {
        if (!prepareEdit()) return false
        val root = root(); val op = operation() ?: return false
        return commit(DocumentCommand.MovePage(op, root.id, root.revision, selected, before))
    }
    fun copyCurrentPage(): Boolean {
        if (!prepareEdit()) return false
        val root = root(); val source = currentPage; val objects = root.pageObjects(selected)
        val currentPoints = root.scene.objects().sumOf { it.samples.size }
        if (objects.sumOf { it.samples.size } > PagedPreviewCodec.MAX_POINTS - currentPoints) return fail("复制后将超过预览容量，原页面仍保留", PreviewFailureKind.CAPACITY)
        val page = PageId(identity("page") ?: return false); val layer = LayerId(identity("layer") ?: return false)
        val mapping = linkedMapOf<ObjectId, ObjectId>()
        for (obj in objects) mapping[obj.id] = ObjectId(identity("ink") ?: return false)
        val op = operation() ?: return false
        if (!commit(DocumentCommand.CopyPage(op, root.id, root.revision, selected, source.contentRevision, page, layer,
                mapping, root.pages().getOrNull(currentPageIndex + 1)?.id))) return false
        selected = page; return true
    }
    fun deleteCurrentPage(): Boolean {
        if (!prepareEdit()) return false
        val root = root(); val index = currentPageIndex; val op = operation() ?: return false
        if (!commit(DocumentCommand.DeletePage(op, root.id, root.revision, selected))) return false
        repairSelection(index); return true
    }
    private fun repairSelection(previousIndex: Int) {
        val root = root()
        if (root.findPage(selected) == null) selected = root.page(previousIndex.coerceIn(0, root.pageCount - 1)).id
    }
    fun setPaper(width: Double, height: Double, paper: PaperKind): Boolean {
        if (!prepareEdit()) return false
        if (!width.isFinite() || !height.isFinite() || width !in 1.0..14_400.0 || height !in 1.0..14_400.0)
            return fail("纸张尺寸无效", PreviewFailureKind.INVALID_ARGUMENT)
        val root = root(); val op = operation() ?: return false
        return commit(DocumentCommand.SetPaper(op, root.id, root.revision, selected, width, height, paper))
    }
    fun rename(value: String): Boolean {
        if (!prepareEdit()) return false
        val trimmed = value.trim(); var end = minOf(120, trimmed.length)
        if (end < trimmed.length && end > 0 && trimmed[end - 1].isHighSurrogate() && trimmed[end].isLowSurrogate()) end--
        val title = trimmed.substring(0, end).ifBlank { "未命名笔记" }
        if (!title.hasValidUnicode()) return fail("标题包含无效字符", PreviewFailureKind.INVALID_ARGUMENT)
        val root = root(); if (title == root.title) return true
        val op = operation() ?: return false
        return commit(DocumentCommand.Rename(op, root.id, root.revision, title))
    }
    fun undo(): Boolean = traverse(true)
    fun redo(): Boolean = traverse(false)
    private fun traverse(undo: Boolean): Boolean {
        if (!prepareEdit()) return false
        val status = memory.status(); val top = (if (undo) status.nextUndoOperationId else status.nextRedoOperationId) ?: return fail("没有可撤销或重做的操作", PreviewFailureKind.HISTORY_EMPTY)
        val root = root(); val index = currentPageIndex; val op = operation() ?: return false
        val command = if (undo) DocumentCommand.Undo(op, root.id, root.revision, top) else DocumentCommand.Redo(op, root.id, root.revision, top)
        if (!commit(command)) return false
        repairSelection(index); return true
    }
    fun selectPen(argb: Int, width: Double): Boolean {
        ownerCheck()
        if (retired) return fail("此会话已关闭，请重新打开笔记", PreviewFailureKind.CLOSED)
        if (!width.isFinite() || width !in 0.5..16.0) return fail("笔宽无效", PreviewFailureKind.INVALID_ARGUMENT)
        color = argb; this.width = width; clearError(); return true
    }
    val inputBatchLimit: Int get() = PREVIEW_INPUT_BATCH_LIMIT
    /** Raw buffers are read synchronously and never retained; callers must not mutate during this call. */
    fun offerRealBatch(phase: PointerPhase, eventTimeMicros: Long, xs: DoubleArray, ys: DoubleArray,
                       pressures: FloatArray, times: LongArray): PreviewInputResult {
        realBatchGate(phase)?.let { return it }
        val count = xs.size
        if (count > PREVIEW_INPUT_BATCH_LIMIT) return rejectRealBatch(PreviewFailureKind.INPUT_BATCH_CAPACITY)
        if (count != ys.size || count != pressures.size || count != times.size || eventTimeMicros < 0 ||
            (phase == PointerPhase.CANCEL && count != 0) ||
            ((phase == PointerPhase.DOWN || phase == PointerPhase.MOVE) && count == 0))
            return rejectRealBatch(PreviewFailureKind.INVALID_INPUT, InputRejection.INVALID_BATCH)
        var time = -1L
        for (i in 0 until count) {
            if (!xs[i].isFinite() || !ys[i].isFinite() || kotlin.math.abs(xs[i]) > 1e9 || kotlin.math.abs(ys[i]) > 1e9 ||
                !pressures[i].isFinite() || pressures[i] !in 0f..1f || times[i] < 0 || times[i] < time || times[i] > eventTimeMicros)
                return rejectRealBatch(PreviewFailureKind.INVALID_INPUT, InputRejection.INVALID_BATCH)
            time = times[i]
        }
        val current = root()
        val session: InputReplaySession
        val sequence: Long
        var command = strokeCommand; var objectId = strokeObject; var page = strokePage
        if (phase == PointerPhase.DOWN) {
            val remaining = PagedPreviewCodec.MAX_POINTS - current.scene.objects().sumOf { it.samples.size }
            if (remaining <= 0 || current.scene.objectCount >= inputObjectLimit ||
                count > minOf(PagedPreviewCodec.MAX_STROKE_POINTS, remaining))
                return rejectRealBatch(PreviewFailureKind.CAPACITY, InputRejection.CAPACITY)
            // Peek both identities without consuming them: refused DOWN must leave retry/undo usable.
            val nextOperation = nextIdentity("op")
                ?: return rejectRealBatch(PreviewFailureKind.IDENTITY_EXHAUSTED)
            val nextObject = nextIdentity("ink", nextOperation.second)
                ?: return rejectRealBatch(PreviewFailureKind.IDENTITY_EXHAUSTED)
            val liveCapacity = memory.additionalStrokeSampleCapacity(ObjectId(nextObject.first), current.findPage(selected)!!.layerId)
            val pointCapacity = affordableInputPoints(minOf(PagedPreviewCodec.MAX_STROKE_POINTS, remaining, liveCapacity))
            if (count > pointCapacity) return rejectRealBatch(PreviewFailureKind.CAPACITY, InputRejection.CAPACITY)
            command = operation() ?: return inputResult(PreviewInputDisposition.REJECTED, 0)
            objectId = ObjectId(identity("ink") ?: return inputResult(PreviewInputDisposition.REJECTED, 0))
            page = selected; session = InputReplaySession(pointCapacity); sequence = 0
        } else {
            session = input!!; sequence = inputSequence
            if (sequence == Long.MAX_VALUE) return rejectRealBatch(PreviewFailureKind.IDENTITY_EXHAUSTED)
        }
        // All scalar/length/work admission checks precede this bounded copied list.
        val points = List(count) { InputSample(xs[it], ys[it], pressures[it], times[it]) }
        val result = session.consume(InputBatch(sequence, eventTimeMicros, 1, PointerTool.STYLUS, phase, points),
            StrokeTarget(current.id, current.findPage(page!!)!!.layerId, BrushSpec(BrushKind.MONOLINE, 1, width, color)))
        if (result is InputResult.Rejected) return rejectRealBatch(
            if (result.reason == InputRejection.CAPACITY) PreviewFailureKind.CAPACITY else PreviewFailureKind.INVALID_INPUT, result.reason)
        strokeCommand = command; strokeObject = objectId; strokePage = page
        inputSequence = sequence + 1
        if (result is InputResult.Cancelled) {
            input = null; clearError(); return inputResult(PreviewInputDisposition.CANCELLED, 0)
        }
        if (result is InputResult.Completed) {
            pending = DocumentCommand.Canvas(page, result.stroke.command(command!!, objectId!!, current.revision))
            input = null
            val committed = retryPendingStroke()
            return inputResult(if (committed) PreviewInputDisposition.COMMITTED else PreviewInputDisposition.PENDING_COMMIT, count)
        }
        input = session; clearError()
        return inputResult(PreviewInputDisposition.ACCEPTED, count)
    }
    /** Reserve the entire admitted stroke, including completion overlap, before allocating identities.
     * Rejection never lowers an active session's capacity or drops its accepted prefix.
     */
    private fun affordableInputPoints(maximum: Int): Int {
        var low = 0; var high = maximum
        while (low < high) {
            val mid = low + (high - low + 1) / 2
            if (InputReplaySession.reservationBytes(mid) <= inputByteLimit) low = mid else high = mid - 1
        }
        return low
    }
    internal fun realBatchGate(phase: PointerPhase): PreviewInputResult? {
        ownerCheck()
        if (retired) return rejectRealBatch(PreviewFailureKind.CLOSED)
        if (pending != null) return rejectRealBatch(PreviewFailureKind.PENDING_STROKE)
        if (phase == PointerPhase.DOWN && input != null) return rejectRealBatch(PreviewFailureKind.ACTIVE_STROKE)
        if (phase != PointerPhase.DOWN && input == null) return rejectRealBatch(PreviewFailureKind.NO_ACTIVE_STROKE)
        return null
    }
    internal fun rejectRealBatch(kind: PreviewFailureKind, reason: InputRejection? = null): PreviewInputResult {
        ownerCheck()
        fail(when (kind) {
            PreviewFailureKind.CLOSED -> "此会话已关闭，请重新打开笔记"
            PreviewFailureKind.PENDING_STROKE -> "请先重试当前笔画，本批未接收"
            PreviewFailureKind.ACTIVE_STROKE -> "请先完成当前笔画，本批未接收"
            PreviewFailureKind.NO_ACTIVE_STROKE -> "没有进行中的笔画"
            PreviewFailureKind.INPUT_BATCH_CAPACITY -> "输入批次超限，本批未接收"
            PreviewFailureKind.CAPACITY -> "此预览已达到笔迹容量，本批未接收"
            PreviewFailureKind.IDENTITY_EXHAUSTED -> "此会话编号已耗尽，本批未接收"
            else -> "输入数据无效，本批未接收"
        }, kind)
        return inputResult(PreviewInputDisposition.REJECTED, 0, reason)
    }
    private fun inputResult(disposition: PreviewInputDisposition, accepted: Int, reason: InputRejection? = null) =
        PreviewInputResult(disposition, accepted, input?.activeRealSamples ?: 0, failure, reason)

    /** Legacy Boolean adapters preserve their explicit unfinished-stroke cancellation on invalid input. */
    private fun legacyInput(phase: PointerPhase, x: Double, y: Double, pressure: Float, timeMicros: Long): Boolean {
        val result = offerRealBatch(phase, timeMicros, doubleArrayOf(x), doubleArrayOf(y), floatArrayOf(pressure), longArrayOf(timeMicros))
        if (result.disposition == PreviewInputDisposition.REJECTED && phase != PointerPhase.DOWN) cancelStroke()
        return result.disposition == PreviewInputDisposition.ACCEPTED || result.disposition == PreviewInputDisposition.COMMITTED
    }
    fun beginStroke(x: Double, y: Double, pressure: Float, timeMicros: Long): Boolean = legacyInput(PointerPhase.DOWN, x, y, pressure, timeMicros)
    fun appendStroke(x: Double, y: Double, pressure: Float, timeMicros: Long): Boolean = legacyInput(PointerPhase.MOVE, x, y, pressure, timeMicros)
    fun endStroke(x: Double, y: Double, pressure: Float, timeMicros: Long): Boolean = legacyInput(PointerPhase.UP, x, y, pressure, timeMicros)
    fun cancelStroke() { ownerCheck(); input?.close(); input = null }
    fun retryPendingStroke(): Boolean {
        ownerCheck(); val command = pending ?: run { clearError(); return true }
        if (!commit(command)) return false
        pending = null; return true
    }
    /** Immutable completed command remains here after capacity failure; cancel/selection cannot discard it. */
    fun pendingStroke(): DocumentCommand.Canvas? { ownerCheck(); return pending }
    /** Host calls only after immutable snapshot admission. Pending completed ink prevents retirement. */
    fun retire(): Boolean {
        ownerCheck()
        if (pending != null) return fail("仍有笔迹未提交，请保留当前页面并重试", PreviewFailureKind.PENDING_STROKE)
        cancelStroke(); clearProjection(); retired = true; return true
    }
    /** Committed content only. Capturing does not transfer responsibility for any pending completed stroke. */
    fun capture(): PagedPreviewSnapshot = PagedPreviewSnapshot(root())

    companion object {
        @Throws(IllegalArgumentException::class)
        fun create(documentId: String, title: String, sessionNonce: String): PagedPreviewNotebook = PagedPreviewNotebook(
            DocumentRoot.empty(SceneId(documentId), title, PageDescriptor(PageId("$sessionNonce-page-0"), LayerId("$sessionNonce-layer-0"), 595.0, 842.0)), sessionNonce)
    }
}

/** Pure immutable full-document capture for the existing bounded storage pipeline's next adapter. */
class PagedPreviewSnapshot internal constructor(val root: DocumentRoot) : PreviewSaveSnapshot {
    override val documentId: String get() = root.id.value
    override val title: String get() = root.title
    override val version: Long get() = root.revision
    override val retainedByteCharge: Long = PagedPreviewCodec.retainedByteCharge(root)
    override val encodingWorkByteCharge: Long = PagedPreviewCodec.encodingWorkByteCharge(root)
    override fun encode(): String = PagedPreviewCodec.encode(root)
    override fun sameContent(other: PreviewSaveSnapshot): Boolean = other is PagedPreviewSnapshot &&
        (root === other.root || documentId == other.documentId && version == other.version && title == other.title &&
            root.pages() == other.root.pages() && root.scene.objects() == other.root.scene.objects())
    companion object {
        @Throws(IllegalArgumentException::class)
        fun fromRoot(root: DocumentRoot): PagedPreviewSnapshot = PagedPreviewSnapshot(root)
    }
}
