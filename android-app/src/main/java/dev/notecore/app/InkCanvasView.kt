package dev.notecore.app

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import dev.notecore.document.*
import dev.notecore.canvas.prototype.*
import dev.notecore.canvas.preview.PreviewViewport
import kotlin.math.hypot

/** Thin native input/render adapter. Paths are cached per committed revision, not rebuilt per MOVE. */
@android.annotation.SuppressLint("ViewConstructor") // deliberately programmatic: a canonical notebook is mandatory
/** Preview tool routing: pen strokes, whole-stroke eraser gestures, or lasso selection. */
enum class InkTool { PEN, ERASER, LASSO }

class InkCanvasView(context: Context, val notebook: PagedPreviewNotebook, private val changed: () -> Unit, private val error: (String) -> Unit) : View(context) {
    internal var renderStore: NoteStore? = null
    internal var surfaceHost: InkSurfaceHost? = null
    var fingerDrawing = true
    var penColor = 0xff253247.toInt()
    var penWidth = 2.5
    var penKind = BrushKind.MONOLINE
    var tool = InkTool.PEN
    var eraserRadius = 8.0
    private val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = 0x66888888.toInt()
    }
    private val lassoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xff3568be.toInt(); strokeWidth = 1.5f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 6f), 0f)
    }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xff3568be.toInt(); strokeWidth = 1.5f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }
    // Same point ceiling as shared EraserRules/LassoRules; refuse before native retention.
    private val maxToolPathPoints = 4096
    private var eraserPoints: MutableList<PointD>? = null
    private var eraserPath: Path? = null
    private var lassoPolygon: MutableList<PointD>? = null
    private var lassoPath: Path? = null
    private var lassoDragStart: PointD? = null
    private var lassoDragCurrent: PointD? = null
    private var toolPointer = -1
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val paper = Paint().apply { color = Color.WHITE }
    private val guide = Paint().apply { color = 0xffeaf0f5.toInt(); strokeWidth = 1f }
    private data class RenderStroke(val path: Path, val color: Int, val width: Float, val x: Float, val y: Float, val dot: Boolean, val matrix: Matrix)
    private var cachedPage: PageId? = null
    private var configuredPage: PageId? = null
    private var cachedVersion = -1L
    private val paths = mutableListOf<RenderStroke>()
    private var active: Path? = null
    private var activePointer = -1
    private var inputOperation: String? = null
    private val ownsInput: Boolean get() = inputOperation != null && inputOperation == notebook.activeInputOperationId
    // Cross-layer compositor blending can differ from the committed CPU composite.
    // Keep translucent active ink and its background in one canonical Canvas draw.
    internal val needsUnifiedInkComposition: Boolean get() = active != null && ownsInput &&
        (BrushDisplayRules.displayArgb(BrushSpec(penKind, 1, penWidth, penColor)) ushr 24) < 255
    private var startX = 0f; private var startY = 0f
    private var moved = false
    val viewport = PreviewViewport()
    private val scale: Float get() = viewport.scale.toFloat()
    private val offsetX: Float get() = viewport.offsetX.toFloat()
    private val offsetY: Float get() = viewport.offsetY.toFloat()
    private var navigating = false
    private var navIds = emptyList<Int>()
    private var navX = 0.0; private var navY = 0.0; private var navSpan = 0.0
    private var activeStylus = false
    private var suppressSequence = false
    fun refreshPage() {
        cancelInk()
        val page = notebook.currentPage
        viewport.configurePage(page.width, page.height)
        if (configuredPage != page.id) viewport.reset()
        configuredPage = page.id
        cachedVersion = -1L
        contentDescription = context.getString(R.string.canvas_description, notebook.strokeCount)
        invalidate()
    }
    fun syncPresentation() {
        val page = notebook.currentPage
        if (configuredPage != page.id || viewport.pageWidth != page.width || viewport.pageHeight != page.height) refreshPage()
        else {
            contentDescription = context.getString(R.string.canvas_description, notebook.strokeCount)
            invalidate() // A save ACK on the same page must not cancel this View's accepted input.
        }
    }
    fun resetViewport() { cancelInk(); viewport.reset(); invalidate() }
    fun zoomViewport(ratio: Double) {
        cancelInk(); viewport.gesture(width / 2.0, height / 2.0, width / 2.0, height / 2.0, ratio); invalidate()
    }
    private fun navigate(event: MotionEvent, skip: Int = -1) {
        val fingers = (0 until event.pointerCount).filter { it != skip && event.getToolType(it) == MotionEvent.TOOL_TYPE_FINGER }.take(2)
        if (fingers.isEmpty()) { navIds = emptyList(); return }
        val ids = fingers.map { event.getPointerId(it) }
        val x = fingers.sumOf { event.getX(it).toDouble() } / fingers.size
        val y = fingers.sumOf { event.getY(it).toDouble() } / fingers.size
        val span = if (fingers.size == 2) hypot((event.getX(fingers[1]) - event.getX(fingers[0])).toDouble(), (event.getY(fingers[1]) - event.getY(fingers[0])).toDouble()) else 0.0
        if (ids == navIds && event.actionMasked == MotionEvent.ACTION_MOVE) {
            viewport.gesture(navX, navY, x, y, if (span > 1 && navSpan > 1) span / navSpan else 1.0)
        }
        navIds = ids; navX = x; navY = y; navSpan = span
    }
    init { setBackgroundColor(Color.TRANSPARENT); isFocusable = true; contentDescription = "手写画布，0笔" }

    internal var observedCanvasHardware: Boolean? = null
        private set
    private var targetObserved = false
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        observedCanvasHardware = canvas.isHardwareAccelerated
        // Diagnostic only: inspect the actual destination, not View.isHardwareAccelerated.
        // No document identity/content; the renderer remains the complete canonical Path.
        if (!targetObserved && context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            targetObserved = true
            android.util.Log.d("NoteCoreRender", "target-observed hardware=${canvas.isHardwareAccelerated} backend=canonical-path width=$width height=$height")
        }
        if (scale <= 0f) return
        val staticFrame = renderStore?.drawCommittedInk(this, canvas) == true
        if (!staticFrame) canvas.drawColor(0xffedf2f6.toInt())
        canvas.save(); canvas.translate(offsetX, offsetY); canvas.scale(scale, scale)
        val page = notebook.currentPage
        if (!staticFrame) {
            paintPaperInScene(canvas)
            if (cachedVersion != notebook.changeVersion || cachedPage != page.id) {
                paths.clear()
                for (s in 0 until notebook.strokeCount) {
                    val stroke = notebook.stroke(s); val samples = stroke.samples; val n = samples.size
                    val path = Path(); val x = samples.x(0).toFloat(); val sy = samples.y(0).toFloat()
                    path.moveTo(x, sy)
                    for (p in 1 until n) path.lineTo(samples.x(p).toFloat(), samples.y(p).toFloat())
                    val m = stroke.transform
                    val matrix = Matrix().apply { setValues(floatArrayOf(m.a.toFloat(), m.c.toFloat(), m.tx.toFloat(),
                        m.b.toFloat(), m.d.toFloat(), m.ty.toFloat(), 0f, 0f, 1f)) }
                    paths.add(RenderStroke(path, BrushDisplayRules.displayArgb(stroke.brush), stroke.brush.width.toFloat(), x, sy,
                        n == 1 || (0 until n).all { samples.x(it).toFloat() == x && samples.y(it).toFloat() == sy }, matrix))
                }
                cachedVersion = notebook.changeVersion; cachedPage = page.id
                contentDescription = context.getString(R.string.canvas_description, notebook.strokeCount)
            }
            for (stroke in paths) {
                canvas.save(); canvas.concat(stroke.matrix)
                ink.color = stroke.color; ink.strokeWidth = stroke.width; ink.style = Paint.Style.STROKE
                if (stroke.dot) { ink.style = Paint.Style.FILL; canvas.drawCircle(stroke.x, stroke.y, stroke.width / 2, ink) }
                else canvas.drawPath(stroke.path, ink)
                canvas.restore()
            }
        }
        canvas.clipRect(0f,0f,notebook.currentPage.width.toFloat(),notebook.currentPage.height.toFloat())
        active?.takeIf { ownsInput }?.let {
            ink.style = Paint.Style.STROKE; ink.color = BrushDisplayRules.displayArgb(BrushSpec(penKind, 1, penWidth, penColor)); ink.strokeWidth = penWidth.toFloat()
            if (moved) canvas.drawPath(it, ink) else { ink.style = Paint.Style.FILL; canvas.drawCircle(startX, startY, penWidth.toFloat() / 2, ink) }
        }
        eraserPath?.let { eraserPaint.strokeWidth = (eraserRadius * 2).toFloat(); canvas.drawPath(it, eraserPaint) }
        lassoPath?.let { canvas.drawPath(it, lassoPaint) }
        selectionBounds()?.let { b ->
            val dx = (lassoDragCurrent?.x ?: 0.0) - (lassoDragStart?.x ?: 0.0)
            val dy = (lassoDragCurrent?.y ?: 0.0) - (lassoDragStart?.y ?: 0.0)
            canvas.drawRect((b.left + dx).toFloat(), (b.top + dy).toFloat(), (b.right + dx).toFloat(), (b.bottom + dy).toFloat(), selectionPaint)
        }
        canvas.restore()
    }
    internal fun paintStaticPaper(canvas: Canvas) {
        canvas.drawColor(0xffedf2f6.toInt())
        canvas.save(); canvas.translate(offsetX,offsetY); canvas.scale(scale,scale)
        paintPaperInScene(canvas); canvas.restore()
    }
    private fun paintPaperInScene(canvas: Canvas) {
        val page = notebook.currentPage; val pw = page.width.toFloat(); val ph = page.height.toFloat()
        canvas.drawRect(0f, 0f, pw, ph, paper)
        canvas.clipRect(0f, 0f, pw, ph)
        val spacing = 24f
        if (page.paper == PaperKind.RULED || page.paper == PaperKind.GRID) {
            var y = spacing
            while (y < ph) { canvas.drawLine(0f, y, pw, y, guide); y += spacing }
        }
        if (page.paper == PaperKind.GRID) {
            var x = spacing
            while (x < pw) { canvas.drawLine(x, 0f, x, ph, guide); x += spacing }
        }
        if (page.paper == PaperKind.DOTS) {
            var x = spacing
            while (x < pw) { var y = spacing; while (y < ph) { canvas.drawCircle(x, y, 0.8f, guide); y += spacing }; x += spacing }
        }
    }
    /** One owner-thread callback snapshot; float conversion belongs only to derived Path geometry. */
    private data class InputTransform(val scale: Double, val offsetX: Double, val offsetY: Double) {
        fun x(raw: Float) = (raw.toDouble() - offsetX) / scale
        fun y(raw: Float) = (raw.toDouble() - offsetY) / scale
    }
    private data class RealBatch(val xs: DoubleArray, val ys: DoubleArray, val pressures: FloatArray, val times: LongArray) {
        fun offer(note: PagedPreviewNotebook, phase: PointerPhase) = note.offerRealBatch(phase, times.last(), xs, ys, pressures, times)
    }
    /** Copy every real history/main scalar before one atomic admission; no MotionEvent survives this call. */
    private fun realBatch(event: MotionEvent, index: Int, input: InputTransform, phase: PointerPhase): RealBatch? {
        val history = event.historySize
        if (history < 0 || history >= notebook.inputBatchLimit) {
            rejectInput("输入批次超限"); return null
        }
        val count = history + 1
        notebook.prepareRealBatch(phase, count)?.let {
            rejectInput(it.failure.previewMessage()); return null
        }
        val xs = DoubleArray(count); val ys = DoubleArray(count); val pressures = FloatArray(count); val times = LongArray(count)
        for (i in 0 until count) {
            val current = i == history
            xs[i] = input.x(if (current) event.getX(index) else event.getHistoricalX(index, i))
            ys[i] = input.y(if (current) event.getY(index) else event.getHistoricalY(index, i))
            val pressure = if (current) event.getPressure(index) else event.getHistoricalPressure(index, i)
            pressures[i] = if (pressure.isFinite()) pressure.coerceIn(0f, 1f) else Float.NaN
            val millis = if (current) event.eventTime else event.getHistoricalEventTime(i)
            times[i] = if (millis >= 0 && millis <= Long.MAX_VALUE / 1000) millis * 1000 else -1
        }
        return RealBatch(xs, ys, pressures, times)
    }
    private fun rejectInput(message: String) {
        cancelInk()
        error(message + "；本笔未提交")
    }
    private fun admit(batch: RealBatch, phase: PointerPhase): PreviewInputResult? {
        val result = batch.offer(notebook, phase)
        if (!result.admitted) { rejectInput(result.failure.previewMessage()); return null }
        return result
    }
    private fun appendPath(batch: RealBatch, from: Int = 0) {
        for (i in from until batch.xs.size) {
            val x = batch.xs[i].toFloat(); val y = batch.ys[i].toFloat()
            active?.lineTo(x, y)
            moved = moved || kotlin.math.abs(x - startX) + kotlin.math.abs(y - startY) > 0.1f
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (active != null && !ownsInput) cancelInk()
        val input = InputTransform(viewport.scale, viewport.offsetX, viewport.offsetY)
        if (input.scale <= 0.0) return false
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) { navigating = false; suppressSequence = false; navIds = emptyList() }
        if (suppressSequence) { if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) suppressSequence = false; return true }
        val fingers = (0 until event.pointerCount).count { event.getToolType(it) == MotionEvent.TOOL_TYPE_FINGER }
        // A pencil keeps a stable transform; secondary fingers never move paper under its nib.
        if (!activeStylus && (navigating || fingers >= 2 || (tool == InkTool.PEN && !fingerDrawing && fingers > 0))) {
            if (!navigating) { cancelInk(); navigating = true; parent.requestDisallowInterceptTouchEvent(true) }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                navigating = false; navIds = emptyList(); parent.requestDisallowInterceptTouchEvent(false)
            } else navigate(event, if (action == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1)
            invalidate(); return true
        }
        if (tool != InkTool.PEN) { handleToolGesture(event, action, input); invalidate(); return true }
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                if (notebook.hasActiveStroke) { error("其他画布正在输入，请完成后重试"); return true }
                if (event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER) { error("预览版暂不支持橡皮尾端，请使用撤销"); return true }
                val stylus = event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
                if (!stylus && !fingerDrawing) return true
                val batch = realBatch(event, 0, input, PointerPhase.DOWN) ?: return true
                val x = batch.xs.first(); val y = batch.ys.first()
                if (!x.isFinite() || !y.isFinite()) { rejectInput("输入数据无效"); return true }
                if (x !in 0.0..notebook.currentPage.width || y !in 0.0..notebook.currentPage.height) return true
                val pen = notebook.edit(PreviewEdit.SelectPen(penColor, penWidth, penKind))
                if (!pen.accepted) { error(pen.failure.previewMessage()); return true }
                admit(batch, PointerPhase.DOWN) ?: return true
                inputOperation = notebook.activeInputOperationId
                activeStylus = stylus; activePointer = event.getPointerId(0); startX = x.toFloat(); startY = y.toFloat(); moved = false
                active = Path().apply { moveTo(startX, startY) }; appendPath(batch, from = 1)
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(activePointer)
                if (index >= 0 && active != null) {
                    val batch = realBatch(event, index, input, PointerPhase.MOVE) ?: return true
                    admit(batch, PointerPhase.MOVE) ?: return true
                    appendPath(batch)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                if (event.getPointerId(index) == activePointer && active != null) {
                    val batch = realBatch(event, index, input, PointerPhase.UP) ?: return true
                    val result = admit(batch, PointerPhase.UP) ?: return true
                    active = null; inputOperation = null; activePointer = -1; activeStylus = false
                    suppressSequence = event.pointerCount > 1
                    when (result.disposition) {
                        PreviewInputDisposition.COMMITTED -> changed()
                        PreviewInputDisposition.PENDING_COMMIT -> error("已完成笔迹尚未提交，已保留，请重试保存")
                        else -> error(result.failure.previewMessage())
                    }
                    parent.requestDisallowInterceptTouchEvent(false); performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> cancelInk()
        }
        invalidate(); return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    /** Eraser and lasso gestures stay in page coordinates through the same input transform as ink. */
    private fun handleToolGesture(event: MotionEvent, action: Int, input: InputTransform) {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                clearToolGesture()
                if (notebook.hasActiveStroke) { error("其他画布正在输入，请完成后重试"); return }
                val x = input.x(event.getX(0)); val y = input.y(event.getY(0))
                if (!x.isFinite() || !y.isFinite()) return
                toolPointer = event.getPointerId(0)
                if (tool == InkTool.ERASER) {
                    eraserPoints = mutableListOf(PointD(x, y))
                    eraserPath = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
                } else {
                    val selection = selectionBounds()
                    if (selection != null && x >= selection.left && x <= selection.right && y >= selection.top && y <= selection.bottom) {
                        lassoDragStart = PointD(x, y); lassoDragCurrent = lassoDragStart
                    } else {
                        lassoPolygon = mutableListOf(PointD(x, y))
                        lassoPath = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
                    }
                }
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(toolPointer)
                if (index < 0) return
                val history = event.historySize
                if (history >= maxToolPathPoints) { rejectToolGesture(); return }
                for (i in 0..history) {
                    val current = i == history
                    val x = input.x(if (current) event.getX(index) else event.getHistoricalX(index, i))
                    val y = input.y(if (current) event.getY(index) else event.getHistoricalY(index, i))
                    if (x.isFinite() && y.isFinite() && !appendToolPoint(PointD(x, y))) return
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (action == MotionEvent.ACTION_CANCEL) { clearToolGesture(); parent.requestDisallowInterceptTouchEvent(false); return }
                val index = event.actionIndex
                if (event.getPointerId(index) != toolPointer) return
                val x = input.x(event.getX(index)); val y = input.y(event.getY(index))
                if (x.isFinite() && y.isFinite() && !appendToolPoint(PointD(x, y), lift = true)) return
                if (tool == InkTool.ERASER) {
                    val points = eraserPoints
                    if (points != null) {
                        val before = notebook.changeVersion
                        val result = notebook.edit(PreviewEdit.EraseStrokes(points.toList(), eraserRadius))
                        if (!result.accepted) error(result.failure?.previewMessage() ?: "操作未完成，请重试")
                        else if (result.rootRevision != before) changed()
                    }
                } else if (lassoDragStart != null && lassoDragCurrent != null) {
                    val dx = lassoDragCurrent!!.x - lassoDragStart!!.x
                    val dy = lassoDragCurrent!!.y - lassoDragStart!!.y
                    if (dx != 0.0 || dy != 0.0) {
                        val result = notebook.edit(PreviewEdit.TransformSelection(dx, dy))
                        if (!result.accepted) error(result.failure?.previewMessage() ?: "操作未完成，请重试") else changed()
                    }
                } else if (lassoPolygon != null && lassoPolygon!!.size >= 3) {
                    val result = notebook.edit(PreviewEdit.LassoSelect(lassoPolygon!!.toList()))
                    if (!result.accepted) error(result.failure?.previewMessage() ?: "操作未完成，请重试")
                }
                clearToolGesture()
                parent.requestDisallowInterceptTouchEvent(false); performClick()
            }
        }
    }
    private fun appendToolPoint(point: PointD, lift: Boolean = false): Boolean {
        eraserPoints?.let { points ->
            if (lift && point.x == points.last().x && point.y == points.last().y) return true
            if (points.size >= maxToolPathPoints) { rejectToolGesture(); return false }
            points.add(point)
            eraserPath?.lineTo(point.x.toFloat(), point.y.toFloat())
            return true
        }
        if (lassoDragStart != null) {
            lassoDragCurrent = point
            return true
        }
        val polygon = lassoPolygon ?: return true
        val last = polygon.last()
        if (kotlin.math.abs(point.x - last.x) + kotlin.math.abs(point.y - last.y) <= 1.0) return true
        if (polygon.size >= maxToolPathPoints) { rejectToolGesture(); return false }
        polygon.add(point)
        lassoPath?.lineTo(point.x.toFloat(), point.y.toFloat())
        return true
    }
    private fun rejectToolGesture() {
        clearToolGesture()
        suppressSequence = true
        parent.requestDisallowInterceptTouchEvent(false)
        invalidate()
        error("工具轨迹超过4096点，本次操作未提交，请分段重试")
    }
    private fun clearToolGesture() {
        toolPointer = -1
        eraserPoints = null; eraserPath = null; lassoPolygon = null; lassoPath = null
        lassoDragStart = null; lassoDragCurrent = null
    }
    /** Page-space union bounds of the current lasso selection; null when nothing is selected. */
    private fun selectionBounds(): BoundsD? {
        val ids = notebook.selectedStrokeIds.toSet()
        if (ids.isEmpty()) return null
        var bounds: BoundsD? = null
        for (i in 0 until notebook.strokeCount) {
            val stroke = notebook.stroke(i)
            if (stroke.id in ids) bounds = bounds?.union(stroke.bounds()) ?: stroke.bounds()
        }
        return bounds
    }
    fun cancelInk() {
        if (ownsInput) notebook.cancelStroke()
        clearToolGesture()
        active = null; inputOperation = null; activePointer = -1; activeStylus = false; navigating = false; navIds = emptyList()
        parent?.requestDisallowInterceptTouchEvent(false); invalidate()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { refreshPage(); viewport.resize(w.toDouble(), h.toDouble()) }
    override fun onDetachedFromWindow() { renderStore?.detachInk(this); cancelInk(); super.onDetachedFromWindow() }
}
