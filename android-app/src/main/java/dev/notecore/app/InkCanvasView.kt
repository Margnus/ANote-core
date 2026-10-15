package dev.notecore.app

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import dev.notecore.document.*
import dev.notecore.canvas.preview.PreviewViewport
import kotlin.math.hypot

/** Thin native input/render adapter. Paths are cached per committed revision, not rebuilt per MOVE. */
@android.annotation.SuppressLint("ViewConstructor") // deliberately programmatic: a canonical notebook is mandatory
class InkCanvasView(context: Context, val notebook: PagedPreviewNotebook, private val changed: () -> Unit, private val error: (String) -> Unit) : View(context) {
    var fingerDrawing = true
    var penColor = 0xff253247.toInt()
    var penWidth = 2.5
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
    init { setBackgroundColor(0xffedf2f6.toInt()); isFocusable = true; contentDescription = "手写画布，0笔" }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (scale <= 0f) return
        canvas.save(); canvas.translate(offsetX, offsetY); canvas.scale(scale, scale)
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
                paths.add(RenderStroke(path, stroke.brush.argb, stroke.brush.width.toFloat(), x, sy,
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
        active?.let {
            ink.style = Paint.Style.STROKE; ink.color = penColor; ink.strokeWidth = penWidth.toFloat()
            if (moved) canvas.drawPath(it, ink) else { ink.style = Paint.Style.FILL; canvas.drawCircle(startX, startY, penWidth.toFloat() / 2, ink) }
        }
        canvas.restore()
    }
    private fun point(event: MotionEvent, index: Int, history: Int = -1): Boolean {
        val x = ((if (history < 0) event.getX(index) else event.getHistoricalX(index, history)) - offsetX) / scale
        val y = ((if (history < 0) event.getY(index) else event.getHistoricalY(index, history)) - offsetY) / scale
        val pressure = (if (history < 0) event.getPressure(index) else event.getHistoricalPressure(index, history)).coerceIn(0f, 1f)
        val time = (if (history < 0) event.eventTime else event.getHistoricalEventTime(history)) * 1000
        if (!notebook.appendStroke(x.toDouble(), y.toDouble(), pressure, time)) { cancelInk(); error(notebook.lastError); return false }
        active?.lineTo(x, y)
        moved = moved || kotlin.math.abs(x - startX) + kotlin.math.abs(y - startY) > 0.1f
        return true
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (scale <= 0f) return false
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) { navigating = false; suppressSequence = false; navIds = emptyList() }
        if (suppressSequence) { if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) suppressSequence = false; return true }
        val fingers = (0 until event.pointerCount).count { event.getToolType(it) == MotionEvent.TOOL_TYPE_FINGER }
        // A pencil keeps a stable transform; secondary fingers never move paper under its nib.
        if (!activeStylus && (navigating || fingers >= 2 || (!fingerDrawing && fingers > 0))) {
            if (!navigating) { cancelInk(); navigating = true; parent.requestDisallowInterceptTouchEvent(true) }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                navigating = false; navIds = emptyList(); parent.requestDisallowInterceptTouchEvent(false)
            } else navigate(event, if (action == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1)
            invalidate(); return true
        }
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                if (event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER) { error("预览版暂不支持橡皮尾端，请使用撤销"); return true }
                val stylus = event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
                if (!stylus && !fingerDrawing) return true
                val x = (event.x - offsetX) / scale; val y = (event.y - offsetY) / scale
                if (x !in 0f..notebook.currentPage.width.toFloat() || y !in 0f..notebook.currentPage.height.toFloat()) return true
                notebook.selectPen(penColor, penWidth)
                if (!notebook.beginStroke(x.toDouble(), y.toDouble(), event.pressure.coerceIn(0f, 1f), event.eventTime * 1000)) {
                    error(notebook.lastError); return true
                }
                activeStylus = stylus; activePointer = event.getPointerId(0); startX = x; startY = y; moved = false
                active = Path().apply { moveTo(x, y) }; parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(activePointer)
                if (index >= 0 && active != null) {
                    for (h in 0 until event.historySize) if (!point(event, index, h)) return true
                    point(event, index)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                if (event.getPointerId(index) == activePointer && active != null) {
                    for (h in 0 until event.historySize) if (!point(event, index, h)) return true
                    val x = (event.getX(index) - offsetX) / scale; val y = (event.getY(index) - offsetY) / scale
                    val ok = notebook.endStroke(x.toDouble(), y.toDouble(), event.getPressure(index).coerceIn(0f, 1f), event.eventTime * 1000)
                    active = null; activePointer = -1; activeStylus = false
                    suppressSequence = event.pointerCount > 1
                    if (ok) changed() else error(notebook.lastError)
                    parent.requestDisallowInterceptTouchEvent(false); performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> cancelInk()
        }
        invalidate(); return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    fun cancelInk() { notebook.cancelStroke(); active = null; activePointer = -1; activeStylus = false; navigating = false; navIds = emptyList(); parent?.requestDisallowInterceptTouchEvent(false); invalidate() }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { refreshPage(); viewport.resize(w.toDouble(), h.toDouble()) }
    override fun onDetachedFromWindow() { cancelInk(); super.onDetachedFromWindow() }
}
