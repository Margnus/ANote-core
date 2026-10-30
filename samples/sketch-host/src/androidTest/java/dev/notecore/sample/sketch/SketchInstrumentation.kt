package dev.notecore.sample.sketch

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import dev.notecore.canvas.prototype.PointerPhase
import dev.notecore.document.*

/** Actual separate application consumer, not NoteCore App instrumentation or ordinary UI evidence. */
class SketchInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    private fun onOwner(action: () -> Unit) {
        var failure: Throwable? = null
        runOnMainSync { try { action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
    override fun onStart() {
        val result = Bundle()
        try {
            val activity = startActivitySync(Intent(targetContext, SketchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SketchActivity
            waitForIdleSync()
            lateinit var original: SketchSession
            lateinit var root: DocumentRoot
            onOwner {
                original = activity.session
                check(original.selectTool(1))
                val view = activity.canvasView
                check(view.width > 0 && view.height > 0)
                val scale = minOf(view.width / 595f, view.height / 842f)
                val start = SystemClock.uptimeMillis()
                val expectedX = mutableListOf<Double>()
                val expectedY = mutableListOf<Double>()
                val expectedTimes = mutableListOf<Long>()
                fun send(event: MotionEvent) {
                    for (i in 0..event.historySize) {
                        val current = i == event.historySize
                        expectedX += (if (current) event.x else event.getHistoricalX(i)).toDouble() / scale
                        expectedY += (if (current) event.y else event.getHistoricalY(i)).toDouble() / scale
                        expectedTimes += (if (current) event.eventTime else event.getHistoricalEventTime(i)) * 1000
                    }
                    try { check(view.dispatchTouchEvent(event)) } finally { event.recycle() }
                }
                send(MotionEvent.obtain(start, start, MotionEvent.ACTION_DOWN, 64 * scale, 96 * scale, 0))
                val move = MotionEvent.obtain(start, start + 10, MotionEvent.ACTION_MOVE, 80 * scale, 112 * scale, 0)
                move.addBatch(start + 20, 96 * scale, 128 * scale, 1f, 1f, 0)
                send(move)
                send(MotionEvent.obtain(start, start + 30, MotionEvent.ACTION_UP, 128 * scale, 160 * scale, 0))
                check(original.notebook.totalStrokeCount == 1)
                val stroke = original.notebook.stroke(0)
                check(stroke.samples.size == 4 && stroke.brush.argb == original.tools[1].argb && stroke.brush.width == 7.0)
                for (i in 0 until stroke.samples.size) {
                    check(stroke.samples.x(i) == expectedX[i] && stroke.samples.y(i) == expectedY[i] && stroke.samples.timeMicros(i) == expectedTimes[i])
                }
                check(expectedTimes[2] - expectedTimes[1] == 10_000L)
                result.putString("actual_points", expectedX.zip(expectedY).toString())
                root = original.notebook.snapshot()
                check(original.setReadOnly(true))
                check(!original.notebook.edit(PreviewEdit.Rename("readonly-bypass")).accepted) { "Readonly host exposes an editable notebook" }
                val rejected = MotionEvent.obtain(start, start + 40, MotionEvent.ACTION_DOWN, 32 * scale, 32 * scale, 0)
                try { check(!view.onTouchEvent(rejected)) } finally { rejected.recycle() }
                check(!original.edit(PreviewEdit.Undo) && !original.selectTool(0) && !original.selectTool(-1))
                check(original.notebook.snapshot() === root)
                check(original.setReadOnly(false))
                check(original.edit(PreviewEdit.Undo) && original.notebook.totalStrokeCount == 0)
                check(original.edit(PreviewEdit.Redo) && original.notebook.totalStrokeCount == 1)
                root = original.notebook.snapshot()
                // A policy rejection must not silently cancel an accepted prefix.
                check(original.notebook.offerRealBatch(PointerPhase.DOWN, 1, doubleArrayOf(1.0), doubleArrayOf(2.0), floatArrayOf(1f), longArrayOf(1)).admitted)
                check(!original.setReadOnly(true) && !original.selectTool(0) && !original.selectTool(999))
                check(original.notebook.hasActiveStroke && original.notebook.inputReservedByteCharge > 0)
                original.notebook.cancelStroke()
                // Inject a host resource and tool set into a separate session; no authority or App object shared.
                var draws = 0
                var backgroundSize = Pair(0f, 0f)
                val provided = mutableListOf(SketchTool("injected", 0xff125634.toInt(), 3.0))
                val other = SketchSession(provided, PageBackground { canvas, width, height ->
                    backgroundSize = Pair(width, height)
                    draws++; canvas.drawColor(0xfffedcba.toInt())
                    canvas.translate(1000f, 0f); canvas.clipRect(0f, 0f, 1f, 1f)
                })
                provided.clear()
                check(other.tools.size == 1 && other.notebook.documentId != original.notebook.documentId)
                val surface = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
                val injected = SketchView(activity, other) {}
                injected.layout(0, 0, 100, 100); injected.draw(Canvas(surface))
                check(draws == 1 && surface.getPixel(10, 10) == 0xfffedcba.toInt())
                surface.recycle()
                check(other.notebook.beginStroke(100.0, 100.0, 1f, 1))
                check(other.notebook.endStroke(100.0, 100.0, 1f, 2))
                val pageBitmap = Bitmap.createBitmap(595, 842, Bitmap.Config.ARGB_8888)
                try {
                    injected.layout(0, 0, 595, 842); injected.draw(Canvas(pageBitmap))
                    check(pageBitmap.getPixel(10, 10) == 0xfffedcba.toInt())
                    check(pageBitmap.getPixel(100, 100) == 0xff125634.toInt()) { "Background transform/clip leaked into canonical ink" }
                    val firstPage = other.notebook.currentPageId.value
                    check(other.edit(PreviewEdit.InsertPage))
                    val emptyPage = other.notebook.currentPageId.value
                    val pageRoot = other.notebook.snapshot()
                    injected.draw(Canvas(pageBitmap))
                    check(pageBitmap.getPixel(100, 100) == 0xfffedcba.toInt())
                    check(other.edit(PreviewEdit.SelectPage(firstPage)))
                    check(other.notebook.snapshot() === pageRoot)
                    injected.draw(Canvas(pageBitmap))
                    check(pageBitmap.getPixel(100, 100) == 0xff125634.toInt()) { "Selected page reused another page's cached paths at the same root revision" }
                    check(other.edit(PreviewEdit.SelectPage(emptyPage)))
                    check(other.notebook.snapshot() === pageRoot)
                    injected.draw(Canvas(pageBitmap))
                    check(pageBitmap.getPixel(100, 100) == 0xfffedcba.toInt())
                    result.putString("page_cache_identity", "passed: same immutable root selects ink/empty pages without cross-page stale paths")
                    check(other.edit(PreviewEdit.SetPaper(300.0, 600.0, PaperKind.BLANK)))
                    check(other.notebook.beginStroke(100.0, 100.0, 1f, 3))
                    check(other.notebook.endStroke(100.0, 100.0, 1f, 4))
                    injected.draw(Canvas(pageBitmap))
                    check(backgroundSize == Pair(300f, 600f)) { "Background did not receive the selected page dimensions" }
                    val resizedScale = minOf(595f / 300f, 842f / 600f)
                    val resizedCenter = (100f * resizedScale).toInt()
                    check(pageBitmap.getPixel(resizedCenter, resizedCenter) == 0xff125634.toInt()) { "Page size was ignored by the canonical ink transform" }
                    result.putString("page_geometry", "passed: 300x600 background bounds and canonical dot use current page scale")
                    val beforeOutside = other.notebook.snapshot()
                    val outside = MotionEvent.obtain(start, start + 100, MotionEvent.ACTION_DOWN, 400f * resizedScale, 100f * resizedScale, 0)
                    try { check(!injected.onTouchEvent(outside)) { "Small page accepted a DOWN outside its actual bounds" } }
                    finally { outside.recycle() }
                    check(other.notebook.snapshot() === beforeOutside && !other.notebook.hasActiveStroke && other.notebook.inputReservedByteCharge == 0L)
                    fun touchDot(x: Float, y: Float, pageScale: Float, offset: Long) {
                        val down = MotionEvent.obtain(start + offset, start + offset, MotionEvent.ACTION_DOWN, x * pageScale, y * pageScale, 0)
                        try { check(injected.onTouchEvent(down)) } finally { down.recycle() }
                        val up = MotionEvent.obtain(start + offset, start + offset + 1, MotionEvent.ACTION_UP, x * pageScale, y * pageScale, 0)
                        try { check(injected.onTouchEvent(up)) } finally { up.recycle() }
                        val last = other.notebook.strokeCount - 1
                        check(other.notebook.pointCount(last) == 2)
                        for (point in 0..1) {
                            check(kotlin.math.abs(other.notebook.pointX(last, point) - x) < 0.001)
                            check(kotlin.math.abs(other.notebook.pointY(last, point) - y) < 0.001)
                        }
                    }
                    touchDot(30f, 60f, resizedScale, 110)
                    check(other.edit(PreviewEdit.SetPaper(900.0, 1200.0, PaperKind.BLANK)))
                    touchDot(800f, 1000f, minOf(595f / 900f, 842f / 1200f), 120)
                    result.putString("page_input_bounds", "passed: small-page outside DOWN refused without input/root mutation; small/large actual MotionEvent dots keep canonical coordinates")
                } finally { pageBitmap.recycle() }
                result.putString("background_canvas_isolation", "passed: injected translate/clip preserves canonical dot and background pixels")
                result.putString("input_policy_resources", "passed: historical batch 4 real points; injected red tool; readonly root identity; undo/redo; active-prefix guard; resource pixels and copied tool list")
            }
            var ownerRejected = false
            try { original.notebook.snapshot() } catch (_: IllegalStateException) { ownerRejected = true }
            check(ownerRejected)
            val monitor = addMonitor(SketchActivity::class.java.name, null, false)
            onOwner { activity.recreate() }
            val recreated = waitForMonitorWithTimeout(monitor, 10_000) as? SketchActivity ?: error("Actual recreated Activity missing")
            removeMonitor(monitor); waitForIdleSync()
            onOwner {
                check(recreated !== activity && recreated.session === original && recreated.session.notebook.snapshot() === root)
                check(recreated.session.notebook.totalStrokeCount == 1 && recreated.session.toolIndex == 1)
                recreated.finish()
            }
            result.putString("lifecycle_owner", "passed: actual Activity recreation retains identical session/root/tool; non-owner thread rejected")
            result.putString("status", "passed")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("status", "failed"); result.putString("error", error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
