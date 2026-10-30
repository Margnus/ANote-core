package dev.notecore.sample.sketch

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import dev.notecore.canvas.prototype.PointerPhase
import dev.notecore.document.PreviewInputDisposition
import kotlin.math.min

/** Canonical MONOLINE Path consumer; no App imports, native pool, worker, or persistence ownership. */
@SuppressLint("ViewConstructor")
class SketchView(context: Context, private val session: SketchSession, private val changed: (String) -> Unit) : View(context) {
    private val note get() = session.notebook
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private var active: Path? = null
    private var pointer = -1
    private var blocked = false
    private var startX = 0f
    private var startY = 0f
    private var moved = false
    private val scale get() = min(width / note.currentPage.width.toFloat(), height / note.currentPage.height.toFloat())
    private data class RenderStroke(val path: Path, val matrix: Matrix, val argb: Int, val width: Float, val x: Float, val y: Float, val dot: Boolean)
    private val paths = mutableListOf<RenderStroke>()
    private var cachedRevision = -1L
    private var cachedPageId: String? = null
    private fun refreshPaths() {
        if (cachedRevision == note.changeVersion && cachedPageId == note.currentPageId.value) return
        paths.clear()
        for (i in 0 until note.strokeCount) {
            val stroke = note.stroke(i); val samples = stroke.samples
            val path = Path(); val x = samples.x(0).toFloat(); val y = samples.y(0).toFloat()
            path.moveTo(x, y)
            var dot = true
            for (p in 1 until samples.size) {
                val px = samples.x(p).toFloat(); val py = samples.y(p).toFloat()
                path.lineTo(px, py); if (px != x || py != y) dot = false
            }
            val transform = stroke.transform
            val matrix = Matrix().apply { setValues(floatArrayOf(transform.a.toFloat(), transform.c.toFloat(), transform.tx.toFloat(),
                transform.b.toFloat(), transform.d.toFloat(), transform.ty.toFloat(), 0f, 0f, 1f)) }
            paths += RenderStroke(path, matrix, stroke.brush.argb, stroke.brush.width.toFloat(), x, y, dot)
        }
        cachedRevision = note.changeVersion
        cachedPageId = note.currentPageId.value
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (scale <= 0) return
        val pageState = canvas.save()
        try {
            canvas.scale(scale, scale); canvas.clipRect(0f, 0f, note.currentPage.width.toFloat(), note.currentPage.height.toFloat())
            val backgroundState = canvas.save()
            try { session.background.draw(canvas, note.currentPage.width.toFloat(), note.currentPage.height.toFloat()) }
            finally { canvas.restoreToCount(backgroundState) }
            refreshPaths()
            for (stroke in paths) {
                canvas.save(); canvas.concat(stroke.matrix)
                paint.color = stroke.argb; paint.strokeWidth = stroke.width
                paint.style = if (stroke.dot) Paint.Style.FILL else Paint.Style.STROKE
                if (stroke.dot) canvas.drawCircle(stroke.x, stroke.y, stroke.width / 2, paint) else canvas.drawPath(stroke.path, paint)
                canvas.restore()
            }
            active?.let { path ->
                val tool = session.tools[session.toolIndex]
                paint.color = tool.argb; paint.strokeWidth = tool.width.toFloat()
                paint.style = if (moved) Paint.Style.STROKE else Paint.Style.FILL
                if (moved) canvas.drawPath(path, paint) else canvas.drawCircle(startX, startY, paint.strokeWidth / 2, paint)
            }
        } finally { canvas.restoreToCount(pageState) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (session.readOnly || scale <= 0) return false
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) { cancelActiveInput(); return true }
        if (blocked) return true // Keep the accepted prefix until explicit cancellation; never commit a partial gesture.
        val phase = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> PointerPhase.DOWN
            MotionEvent.ACTION_MOVE -> PointerPhase.MOVE
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> PointerPhase.UP
            else -> return true
        }
        val index = if (phase == PointerPhase.DOWN) 0 else event.findPointerIndex(pointer)
        if (index < 0 || (phase == PointerPhase.UP && index != event.actionIndex)) return true
        if (event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER) return false
        val count = event.historySize + 1
        if (count > note.inputBatchLimit) { blockInput(); return true }
        if (note.prepareRealBatch(phase, count) != null) { blockInput(); return true }
        val xs = DoubleArray(count); val ys = DoubleArray(count); val pressures = FloatArray(count); val times = LongArray(count)
        for (i in 0 until count) {
            val current = i == count - 1
            xs[i] = (if (current) event.getX(index) else event.getHistoricalX(index, i)).toDouble() / scale
            ys[i] = (if (current) event.getY(index) else event.getHistoricalY(index, i)).toDouble() / scale
            pressures[i] = (if (current) event.getPressure(index) else event.getHistoricalPressure(index, i)).let { if (it.isFinite()) it.coerceIn(0f, 1f) else Float.NaN }
            val millis = if (current) event.eventTime else event.getHistoricalEventTime(i)
            times[i] = if (millis in 0..Long.MAX_VALUE / 1000) millis * 1000 else -1
        }
        if (phase == PointerPhase.DOWN && (xs[0] !in 0.0..note.currentPage.width || ys[0] !in 0.0..note.currentPage.height)) return false
        val result = note.offerRealBatch(phase, times.last(), xs, ys, pressures, times)
        if (!result.admitted) { blockInput(); return true }
        if (phase == PointerPhase.DOWN) {
            pointer = event.getPointerId(index); startX = xs[0].toFloat(); startY = ys[0].toFloat(); moved = false
            active = Path().apply { moveTo(startX, startY) }
        }
        for (i in xs.indices) {
            active?.lineTo(xs[i].toFloat(), ys[i].toFloat())
            if (xs[i].toFloat() != startX || ys[i].toFloat() != startY) moved = true
        }
        if (phase == PointerPhase.UP) {
            active = null; pointer = -1
            changed(if (result.disposition == PreviewInputDisposition.COMMITTED) "" else "完成笔待提交，原命令保留")
            performClick()
        }
        invalidate(); return true
    }
    private fun blockInput() { blocked = true; changed("输入被拒绝，已接受部分保留；请明确取消当前笔") }
    fun cancelActiveInput() { note.cancelStroke(); active = null; pointer = -1; blocked = false; invalidate() }
    override fun performClick(): Boolean { super.performClick(); return true }
}
