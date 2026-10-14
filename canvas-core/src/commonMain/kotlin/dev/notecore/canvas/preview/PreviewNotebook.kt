package dev.notecore.canvas.preview

import dev.notecore.canvas.prototype.*

/** Preview-only offline handwriting facade. All mutable operations belong to the UI owner. */
class PreviewNotebook private constructor(
    val documentId: String, initialTitle: String, initial: SceneSnapshot, private var nextObject: Long,
) {
    constructor(documentId: String, title: String) : this(documentId, title, SceneSnapshot.empty(SceneId(documentId)), 1)
    private var memory = MemorySceneCoordinator(initial, journalCapacity = 128)
    private var journalCharge = 0L
    private var visible = initial.objects()
    private var input: InputReplaySession? = null
    private var inputSequence = 0L
    private var commandSequence = 0L
    private val redo = mutableListOf<StrokeObject>()
    private var pendingStroke: CanvasCommand.AddStroke? = null
    private var currentColor: Int = 0xff253247.toInt()
    private var currentWidth: Double = 2.5
    var title: String = initialTitle; private set
    var changeVersion: Long = 0; private set
    var lastError: String = ""; private set
    val strokeCount: Int get() = visible.size
    val canUndo: Boolean get() = visible.isNotEmpty() && input == null
    val canRedo: Boolean get() = redo.isNotEmpty() && input == null
    val hasActiveStroke: Boolean get() = input != null
    val hasPendingStroke: Boolean get() = pendingStroke != null
    init { require(documentId.isNotBlank() && documentId.length <= 128 && initialTitle.length <= 120) }

    fun rename(value: String) {
        val normalized = value.trim().take(120).ifBlank { "未命名笔记" }
        if (normalized != title) { title = normalized; changeVersion++ }
    }
    fun selectPen(argb: Int, width: Double) {
        require(width.isFinite() && width in 0.5..16.0)
        currentColor = argb; currentWidth = width
    }
    fun strokeId(index: Int): String = visible[index].id.value
    fun strokeColor(index: Int): Int = visible[index].brush.argb
    fun strokeWidth(index: Int): Double = visible[index].brush.width
    fun pointCount(stroke: Int): Int = visible[stroke].samples.size
    fun pointX(stroke: Int, point: Int): Double = visible[stroke].samples.x(point)
    fun pointY(stroke: Int, point: Int): Double = visible[stroke].samples.y(point)
    fun pointPressure(stroke: Int, point: Int): Float = visible[stroke].samples.pressure(point)

    private fun failed(message: String): Boolean { lastError = message; return false }
    fun beginStroke(x: Double, y: Double, pressure: Float, timeMicros: Long): Boolean {
        if (input != null) return failed("请先结束当前笔画")
        if (pendingStroke != null) return failed("上一笔尚未提交，请先重试保存")
        if (visible.size >= MAX_STROKES || visible.sumOf { it.samples.size } >= MAX_POINTS)
            return failed("此预览页已达到容量限制，请新建笔记")
        val capacity = minOf(MAX_STROKE_POINTS, MAX_POINTS - visible.sumOf { it.samples.size })
        input = InputReplaySession(capacity); inputSequence = 0
        lastError = ""
        return consume(PointerPhase.DOWN, x, y, pressure, timeMicros)
    }
    fun appendStroke(x: Double, y: Double, pressure: Float, timeMicros: Long): Boolean =
        consume(PointerPhase.MOVE, x, y, pressure, timeMicros)
    fun endStroke(x: Double, y: Double, pressure: Float, timeMicros: Long): Boolean =
        consume(PointerPhase.UP, x, y, pressure, timeMicros)

    private fun consume(phase: PointerPhase, x: Double, y: Double, pressure: Float, timeMicros: Long): Boolean {
        val session = input ?: return failed("没有进行中的笔画")
        val result = try {
            session.consume(InputBatch(inputSequence++, timeMicros, 1, PointerTool.STYLUS, phase,
                listOf(InputSample(x, y, pressure, timeMicros))),
                StrokeTarget(memory.snapshot().id, LayerId("ink"), BrushSpec(BrushKind.MONOLINE, 1, currentWidth, currentColor)))
        } catch (_: IllegalArgumentException) {
            cancelStroke(); return failed("输入数据无效，本笔未保存")
        }
        if (result is InputResult.Rejected) {
            cancelStroke(); return failed("输入超限或乱序，本笔未保存，请重新书写")
        }
        if (result is InputResult.Completed) {
            input = null
            if (nextObject == Long.MAX_VALUE) return failed("笔画编号已耗尽")
            val id = ObjectId("ink-${nextObject++}")
            val command = result.stroke.command(nextCommand(), id, memory.snapshot().revision)
            pendingStroke = command
            if (!commit(command)) return false
            pendingStroke = null
            redo.clear()
        }
        return true
    }
    fun cancelStroke() { input?.close(); input = null }
    private fun nextCommand() = CommandId("preview-${++commandSequence}")
    private fun commit(command: CanvasCommand): Boolean {
        val prepared = SceneReducer.prepare(memory.snapshot(), command)
        if (prepared !is PrepareResult.Prepared) return failed("笔画提交失败，未保存更改")
        val charge = 512L + prepared.patch.changes().sumOf { (it.before?.samples?.size ?: 0).toLong() * 28 + (it.after?.samples?.size ?: 0).toLong() * 28 }
        if (charge > 4L * 1024 * 1024 - journalCharge) {
            memory = MemorySceneCoordinator(memory.snapshot(), journalCapacity = 128)
            journalCharge = 0
        }
        var result = memory.commit(prepared.patch)
        if (result is CommitResult.Rejected && result.reason == Rejection.JOURNAL_FULL) {
            // This preview has a synchronous private authority with no external attempts/receipts.
            // Retire its acknowledged in-memory dedup epoch; keep the exact canonical snapshot.
            memory = MemorySceneCoordinator(memory.snapshot(), journalCapacity = 128)
            journalCharge = 0
            result = memory.commit(prepared.patch)
        }
        if (result !is CommitResult.Accepted) return failed("笔画尚未提交，请重试保存")
        journalCharge += charge
        visible = memory.snapshot().objects(); changeVersion++
        return true
    }
    fun undo(): Boolean {
        if (!canUndo || pendingStroke != null) return false
        val last = visible.last()
        if (!commit(CanvasCommand.DeleteStroke(nextCommand(), memory.snapshot().id, memory.snapshot().revision, last.id, last.objectRevision))) return false
        redo.add(last); return true
    }
    fun redo(): Boolean {
        if (!canRedo || pendingStroke != null) return false
        val stroke = redo.last()
        val command = CanvasCommand.AddStroke(nextCommand(), memory.snapshot().revision,
            StrokeTarget(memory.snapshot().id, stroke.layerId, stroke.brush), stroke.id, stroke.samples)
        if (!commit(command)) return false
        redo.removeAt(redo.lastIndex); return true
    }
    fun retryPendingStroke(): Boolean {
        val command = pendingStroke ?: return true
        if (!commit(command)) return false
        pendingStroke = null; redo.clear(); return true
    }
    /** Immutable owner snapshot; encode it off the UI thread. Active preview ink is intentionally absent. */
    fun capture(): PreviewNotebookSnapshot = PreviewNotebookSnapshot(documentId, title, changeVersion, memory.snapshot(), nextObject)

    companion object {
        const val MAX_STROKES = 500
        const val MAX_POINTS = 200_000
        const val MAX_STROKE_POINTS = 20_000
        @Throws(IllegalArgumentException::class)
        fun restore(encoded: String): PreviewNotebook {
            require(encoded.length <= 16_000_000) { "Preview document too large" }
            val lines = encoded.lineSequence().iterator()
            fun row(): List<String> { require(lines.hasNext()) { "Truncated preview document" }; return lines.next().split('\t') }
            require(row() == listOf("NCPREVIEW", "1")) { "Unsupported preview document" }
            val meta = row(); require(meta.size == 6 && meta[0] == "D")
            val id = unescape(meta[1]); val title = unescape(meta[2]); val nextId = meta[3].toLong()
            val count = meta[4].toInt(); val version = meta[5].toLong()
            require(count in 0..MAX_STROKES && nextId > 0 && version >= 0 && title.length <= 120)
            val objects = linkedMapOf<ObjectId, StrokeObject>(); var total = 0
            repeat(count) {
                val head = row(); require(head.size == 5 && head[0] == "S")
                val objectId = ObjectId(head[1]); val numericId = head[1].removePrefix("ink-").toLong()
                require(head[1] == "ink-$numericId" && numericId in 1 until nextId && objectId !in objects)
                val color = head[2].toInt(); val width = head[3].toDouble(); val size = head[4].toInt()
                require(size in 1..MAX_STROKE_POINTS && width.isFinite() && width in 0.5..16.0)
                total += size; require(total <= MAX_POINTS)
                val xs = DoubleArray(size); val ys = DoubleArray(size); val ps = FloatArray(size); val ts = LongArray(size)
                repeat(size) { p ->
                    val point = row(); require(point.size == 5 && point[0] == "P")
                    xs[p] = point[1].toDouble(); ys[p] = point[2].toDouble(); ps[p] = point[3].toFloat(); ts[p] = point[4].toLong()
                }
                objects[objectId] = StrokeObject(objectId, 1, LayerId("ink"), BrushSpec(BrushKind.MONOLINE, 1, width, color), StrokeSamples.copyOf(xs, ys, ps, ts))
            }
            require(row() == listOf("END")) { "Missing preview document footer" }
            require(!lines.hasNext() || (lines.next().isEmpty() && !lines.hasNext())) { "Trailing preview data" }
            return PreviewNotebook(id, title, SceneSnapshot(SceneId(id), 0, objects), nextId).also { it.changeVersion = version }
        }
    }
}

/** Versioned preview interchange, not the production NoteCore format or a durable receipt. */
class PreviewNotebookSnapshot internal constructor(
    val documentId: String, val title: String, val version: Long,
    private val scene: SceneSnapshot, private val nextObject: Long,
) {
    fun encode(): String = buildString {
        append("NCPREVIEW\t1\nD\t"); append(escape(documentId)); append('\t'); append(escape(title))
        append('\t'); append(nextObject); append('\t'); append(scene.objectCount); append('\t'); append(version); append('\n')
        for (stroke in scene.objects()) {
            append("S\t${stroke.id.value}\t${stroke.brush.argb}\t${stroke.brush.width}\t${stroke.samples.size}\n")
            for (p in 0 until stroke.samples.size) append("P\t${stroke.samples.x(p)}\t${stroke.samples.y(p)}\t${stroke.samples.pressure(p)}\t${stroke.samples.timeMicros(p)}\n")
        }
        append("END\n")
    }
}

private fun escape(value: String): String = buildString {
    for (c in value) append(when (c) { '\\' -> "\\\\"; '\t' -> "\\t"; '\n' -> "\\n"; '\r' -> "\\r"; else -> c.toString() })
}
private fun unescape(value: String): String = buildString {
    var index = 0
    while (index < value.length) {
        val c = value[index++]
        if (c != '\\') append(c) else {
            require(index < value.length)
            append(when (value[index++]) { '\\' -> '\\'; 't' -> '\t'; 'n' -> '\n'; 'r' -> '\r'; else -> throw IllegalArgumentException("Invalid escape") })
        }
    }
}
