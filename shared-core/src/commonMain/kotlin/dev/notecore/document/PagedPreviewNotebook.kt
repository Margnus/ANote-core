package dev.notecore.document

import dev.notecore.canvas.prototype.*

/** UI-owner preview session. Exactly one authority; selected-page projection is a read-only cache. */
class PagedPreviewNotebook @Throws(IllegalArgumentException::class) constructor(initial: DocumentRoot, private val sessionNonce: String,
                           budget: DocumentBudget = DocumentBudget(), private val ownerCheck: () -> Unit = {}) {
    private val memory = MemoryDocumentAuthority(initial, budget, ownerCheck)
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
    val hasActiveStroke: Boolean get() { ownerCheck(); return input != null }
    val hasPendingStroke: Boolean get() { ownerCheck(); return pending != null }
    val canUndo: Boolean get() { ownerCheck(); return input == null && pending == null && memory.status().nextUndoOperationId != null }
    val canRedo: Boolean get() { ownerCheck(); return input == null && pending == null && memory.status().nextRedoOperationId != null }
    fun snapshot(): DocumentRoot = root()
    fun page(index: Int): PageDescriptor = root().page(index)
    fun authorityStatus(): DocumentAuthorityStatus { ownerCheck(); return memory.status() }
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
    private fun fail(message: String): Boolean { error = message; return false }
    private fun identity(kind: String): String? {
        while (sequence < Long.MAX_VALUE) {
            val candidate = "$sessionNonce-$kind-${++sequence}"
            if (candidate !in initialIds) return candidate
        }
        error = "此会话编号已耗尽，请保留笔记并导出备份"; return null
    }
    private fun operation(): CommandId? = identity("op")?.let(::CommandId)
    private fun prepareEdit(): Boolean {
        ownerCheck()
        if (pending != null) return fail("上一笔尚未提交，请先重试保存")
        cancelStroke(); error = ""; return true
    }
    private fun commit(command: DocumentCommand): Boolean = when (val result = memory.commit(command)) {
        is DocumentCommit.Accepted -> { error = ""; true }
        is DocumentCommit.Rejected -> fail(when (result.reason) {
            DocumentRejection.JOURNAL_CAPACITY, DocumentRejection.HISTORY_CAPACITY, DocumentRejection.LIVE_BYTE_CAPACITY,
            DocumentRejection.IDENTITY_CAPACITY, DocumentRejection.OBJECT_CAPACITY, DocumentRejection.PAGE_CAPACITY -> "当前预览已达到编辑容量，请保留笔记并导出备份"
            else -> "操作未完成，原内容仍保留，请重试"
        })
    }
    fun selectPage(id: PageId): Boolean {
        ownerCheck()
        if (root().findPage(id) == null) return fail("页面不存在")
        if (id == selected) return true
        if (!prepareEdit()) return false
        selected = id; return true // Presentation-only; no root commit.
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
        if (objects.sumOf { it.samples.size } > PagedPreviewCodec.MAX_POINTS - currentPoints) return fail("复制后将超过预览容量，原页面仍保留")
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
        val root = root(); val op = operation() ?: return false
        return commit(DocumentCommand.SetPaper(op, root.id, root.revision, selected, width, height, paper))
    }
    fun rename(value: String): Boolean {
        if (!prepareEdit()) return false
        val trimmed = value.trim(); var end = minOf(120, trimmed.length)
        if (end < trimmed.length && end > 0 && trimmed[end - 1].isHighSurrogate() && trimmed[end].isLowSurrogate()) end--
        val title = trimmed.substring(0, end).ifBlank { "未命名笔记" }
        var i = 0
        while (i < title.length) {
            val c = title[i++]
            if (c.isHighSurrogate()) { if (i == title.length || !title[i++].isLowSurrogate()) return fail("标题包含无效字符") }
            else if (c.isLowSurrogate()) return fail("标题包含无效字符")
        }
        val root = root(); if (title == root.title) return true
        val op = operation() ?: return false
        return commit(DocumentCommand.Rename(op, root.id, root.revision, title))
    }
    fun undo(): Boolean = traverse(true)
    fun redo(): Boolean = traverse(false)
    private fun traverse(undo: Boolean): Boolean {
        if (!prepareEdit()) return false
        val status = memory.status(); val top = (if (undo) status.nextUndoOperationId else status.nextRedoOperationId) ?: return false
        val root = root(); val index = currentPageIndex; val op = operation() ?: return false
        val command = if (undo) DocumentCommand.Undo(op, root.id, root.revision, top) else DocumentCommand.Redo(op, root.id, root.revision, top)
        if (!commit(command)) return false
        repairSelection(index); return true
    }
    fun selectPen(argb: Int, width: Double): Boolean {
        ownerCheck()
        if (!width.isFinite() || width !in 0.5..16.0) return fail("笔宽无效")
        color = argb; this.width = width; return true
    }
    fun beginStroke(x: Double, y: Double, pressure: Float, timeMicros: Long): Boolean {
        ownerCheck()
        if (pending != null || input != null) return fail("请先完成或重试当前笔画")
        val root = root(); val remaining = PagedPreviewCodec.MAX_POINTS - root.scene.objects().sumOf { it.samples.size }
        if (remaining <= 0 || root.scene.objectCount >= PagedPreviewCodec.MAX_OBJECTS) return fail("此预览已达到笔迹容量")
        // Reserve identities before accepting real points. Completion never loses its output to an exhausted allocator.
        strokeCommand = operation() ?: return false; strokeObject = ObjectId(identity("ink") ?: return false)
        strokePage = selected; input = InputReplaySession(minOf(PagedPreviewCodec.MAX_STROKE_POINTS, remaining)); inputSequence = 0
        return consume(PointerPhase.DOWN, x, y, pressure, timeMicros)
    }
    fun appendStroke(x: Double, y: Double, pressure: Float, timeMicros: Long): Boolean = consume(PointerPhase.MOVE, x, y, pressure, timeMicros)
    fun endStroke(x: Double, y: Double, pressure: Float, timeMicros: Long): Boolean = consume(PointerPhase.UP, x, y, pressure, timeMicros)
    private fun consume(phase: PointerPhase, x: Double, y: Double, pressure: Float, timeMicros: Long): Boolean {
        ownerCheck(); val session = input ?: return fail("没有进行中的笔画")
        val result = try {
            session.consume(InputBatch(inputSequence++, timeMicros, 1, PointerTool.STYLUS, phase, listOf(InputSample(x, y, pressure, timeMicros))),
                StrokeTarget(root().id, root().findPage(strokePage!!)!!.layerId, BrushSpec(BrushKind.MONOLINE, 1, width, color)))
        } catch (_: IllegalArgumentException) { cancelStroke(); return fail("输入数据无效，本笔未提交") }
        if (result is InputResult.Rejected) { cancelStroke(); return fail("输入超限或乱序，本笔未提交") }
        if (result is InputResult.Completed) {
            pending = DocumentCommand.Canvas(strokePage!!, result.stroke.command(strokeCommand!!, strokeObject!!, root().revision))
            input = null
            return retryPendingStroke()
        }
        error = ""; return true
    }
    fun cancelStroke() { ownerCheck(); input?.close(); input = null }
    fun retryPendingStroke(): Boolean {
        ownerCheck(); val command = pending ?: return true
        if (!commit(command)) return false
        pending = null; return true
    }
    /** Immutable completed command remains here after capacity failure; cancel/selection cannot discard it. */
    fun pendingStroke(): DocumentCommand.Canvas? { ownerCheck(); return pending }
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
}
