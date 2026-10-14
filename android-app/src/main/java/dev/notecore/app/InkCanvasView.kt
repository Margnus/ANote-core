package dev.notecore.app

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import dev.notecore.canvas.preview.PreviewNotebook

/** Thin native input/render adapter. Paths are cached per committed revision, not rebuilt per MOVE. */
@android.annotation.SuppressLint("ViewConstructor") // deliberately programmatic: a canonical notebook is mandatory
class InkCanvasView(context: Context, val notebook: PreviewNotebook, private val changed: () -> Unit, private val error: (String) -> Unit) : View(context) {
    var fingerDrawing = true
    var penColor = 0xff253247.toInt()
    var penWidth = 2.5
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val paper = Paint().apply { color = Color.WHITE }
    private val guide = Paint().apply { color = 0xffeaf0f5.toInt(); strokeWidth = 1f }
    private data class RenderStroke(val path: Path, val color: Int, val width: Float, val x: Float, val y: Float, val dot: Boolean)
    private var cachedVersion = -1L
    private val paths = mutableListOf<RenderStroke>()
    private var active: Path? = null
    private var activePointer = -1
    private var startX = 0f; private var startY = 0f
    private var moved = false
    private var lastX = 0f; private var lastY = 0f; private var lastPressure = 0.5f; private var lastTime = 0L
    private val scale: Float get() = minOf(width / 1000f, height / 1400f)
    private val offsetX: Float get() = (width - 1000f * scale) / 2
    init { setBackgroundColor(0xffedf2f6.toInt()); isFocusable = true; contentDescription = "手写画布，0笔" }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (scale <= 0f) return
        canvas.save(); canvas.translate(offsetX, 0f); canvas.scale(scale, scale)
        canvas.drawRect(0f, 0f, 1000f, 1400f, paper)
        var y = 80f
        while (y < 1400) { canvas.drawLine(35f, y, 965f, y, guide); y += 48f }
        canvas.clipRect(0f, 0f, 1000f, 1400f)
        if (cachedVersion != notebook.changeVersion) {
            paths.clear()
            for (s in 0 until notebook.strokeCount) {
                val path = Path(); val n = notebook.pointCount(s)
                val x = notebook.pointX(s, 0).toFloat(); val sy = notebook.pointY(s, 0).toFloat()
                path.moveTo(x, sy)
                for (p in 1 until n) path.lineTo(notebook.pointX(s, p).toFloat(), notebook.pointY(s, p).toFloat())
                paths.add(RenderStroke(path, notebook.strokeColor(s), notebook.strokeWidth(s).toFloat(), x, sy,
                    n == 1 || (0 until n).all { notebook.pointX(s, it).toFloat() == x && notebook.pointY(s, it).toFloat() == sy }))
            }
            cachedVersion = notebook.changeVersion
            contentDescription = context.getString(R.string.canvas_description, notebook.strokeCount)
        }
        for (stroke in paths) {
            ink.color = stroke.color; ink.strokeWidth = stroke.width; ink.style = Paint.Style.STROKE
            if (stroke.dot) { ink.style = Paint.Style.FILL; canvas.drawCircle(stroke.x, stroke.y, stroke.width / 2, ink) }
            else canvas.drawPath(stroke.path, ink)
        }
        active?.let {
            ink.style = Paint.Style.STROKE; ink.color = penColor; ink.strokeWidth = penWidth.toFloat()
            if (moved) canvas.drawPath(it, ink) else { ink.style = Paint.Style.FILL; canvas.drawCircle(startX, startY, penWidth.toFloat() / 2, ink) }
        }
        canvas.restore()
    }
    private fun point(event: MotionEvent, index: Int, history: Int = -1): Boolean {
        val x = ((if (history < 0) event.getX(index) else event.getHistoricalX(index, history)) - offsetX) / scale
        val y = (if (history < 0) event.getY(index) else event.getHistoricalY(index, history)) / scale
        val pressure = (if (history < 0) event.getPressure(index) else event.getHistoricalPressure(index, history)).coerceIn(0f, 1f)
        val time = (if (history < 0) event.eventTime else event.getHistoricalEventTime(history)) * 1000
        lastX = x; lastY = y; lastPressure = pressure; lastTime = time
        if (!notebook.appendStroke(x.toDouble(), y.toDouble(), pressure, time)) { cancelInk(); error(notebook.lastError); return false }
        active?.lineTo(x, y)
        moved = moved || kotlin.math.abs(x - startX) + kotlin.math.abs(y - startY) > 0.1f
        return true
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (scale <= 0f) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER) { error("预览版暂不支持橡皮尾端，请使用撤销"); return true }
                val stylus = event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
                if (!stylus && !fingerDrawing) return true
                val x = (event.x - offsetX) / scale; val y = event.y / scale
                if (x !in 0f..1000f || y !in 0f..1400f) return true
                notebook.selectPen(penColor, penWidth)
                if (!notebook.beginStroke(x.toDouble(), y.toDouble(), event.pressure.coerceIn(0f, 1f), event.eventTime * 1000)) {
                    error(notebook.lastError); return true
                }
                activePointer = event.getPointerId(0); startX = x; startY = y; moved = false
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
                    val x = (event.getX(index) - offsetX) / scale; val y = event.getY(index) / scale
                    val ok = notebook.endStroke(x.toDouble(), y.toDouble(), event.getPressure(index).coerceIn(0f, 1f), event.eventTime * 1000)
                    active = null; activePointer = -1
                    if (ok) changed() else error(notebook.lastError)
                    parent.requestDisallowInterceptTouchEvent(false); performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> cancelInk()
        }
        invalidate(); return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    fun cancelInk() { notebook.cancelStroke(); active = null; activePointer = -1; parent?.requestDisallowInterceptTouchEvent(false); invalidate() }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { if (oldw != 0) cancelInk() }
    override fun onDetachedFromWindow() { cancelInk(); super.onDetachedFromWindow() }
}
