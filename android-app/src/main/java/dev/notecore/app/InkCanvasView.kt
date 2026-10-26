package dev.notecore.app

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import dev.notecore.document.*
import dev.notecore.canvas.prototype.PointerPhase
import dev.notecore.canvas.preview.PreviewViewport
import kotlin.math.hypot

/** Thin native input/render adapter. Paths are cached per committed revision, not rebuilt per MOVE. */
@android.annotation.SuppressLint("ViewConstructor") // deliberately programmatic: a canonical notebook is mandatory
class InkCanvasView(context: Context, val notebook: PagedPreviewNotebook, private val changed: () -> Unit, private val error: (String) -> Unit) : View(context) {
    internal var renderStore: NoteStore? = null
    internal var surfaceHost: InkSurfaceHost? = null
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
        }
        canvas.clipRect(0f,0f,notebook.currentPage.width.toFloat(),notebook.currentPage.height.toFloat())
        active?.let {
            ink.style = Paint.Style.STROKE; ink.color = penColor; ink.strokeWidth = penWidth.toFloat()
            if (moved) canvas.drawPath(it, ink) else { ink.style = Paint.Style.FILL; canvas.drawCircle(startX, startY, penWidth.toFloat() / 2, ink) }
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
            cancelInk(); error("输入批次超限，本笔未提交"); return null
        }
        val count = history + 1
        notebook.prepareRealBatch(phase, count)?.let {
            cancelInk(); error(notebook.lastError + "；本笔未提交"); return null
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
    private fun admit(batch: RealBatch, phase: PointerPhase): PreviewInputResult? {
        val result = batch.offer(notebook, phase)
        if (!result.admitted) { cancelInk(); error(notebook.lastError + "；本笔未提交"); return null }
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
        val input = InputTransform(viewport.scale, viewport.offsetX, viewport.offsetY)
        if (input.scale <= 0.0) return false
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
                val batch = realBatch(event, 0, input, PointerPhase.DOWN) ?: return true
                val x = batch.xs.first(); val y = batch.ys.first()
                if (!x.isFinite() || !y.isFinite()) { cancelInk(); error("输入数据无效，本笔未提交"); return true }
                if (x !in 0.0..notebook.currentPage.width || y !in 0.0..notebook.currentPage.height) return true
                if (!notebook.selectPen(penColor, penWidth)) { error(notebook.lastError); return true }
                admit(batch, PointerPhase.DOWN) ?: return true
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
                    active = null; activePointer = -1; activeStylus = false
                    suppressSequence = event.pointerCount > 1
                    if (result.disposition == PreviewInputDisposition.COMMITTED) changed() else error(notebook.lastError)
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
    override fun onDetachedFromWindow() { renderStore?.detachInk(this); cancelInk(); super.onDetachedFromWindow() }
}
