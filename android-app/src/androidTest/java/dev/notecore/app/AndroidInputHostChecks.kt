package dev.notecore.app

import android.app.Activity
import android.view.InputDevice
import android.view.MotionEvent
import android.widget.FrameLayout
import dev.notecore.document.PagedPreviewNotebook
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Synthetic MotionEvent callbacks on the original View, not OS/stylus/UI delivery evidence. */
internal class AndroidInputHostChecks(private val host: Activity) {
    private data class Sample(val x: Float, val y: Float, val time: Long, val pressure: Float = 0.5f)
    private class Fixture(val view: InkCanvasView, val note: PagedPreviewNotebook,
        val errors: MutableList<String>, val changes: IntArray)
    private fun fixture(): Fixture {
        val note = PagedPreviewNotebook.create("synthetic-input", "Synthetic callbacks", "input-session")
        check(note.setPaper(14400.0, 12345.0, dev.notecore.document.PaperKind.BLANK))
        val errors = mutableListOf<String>(); val changes = intArrayOf(0)
        val view = InkCanvasView(host, note, { changes[0]++ }, { errors.add(it) })
        // A parent is required by the original View's interception contract. No user notebook/file.
        FrameLayout(host).addView(view)
        view.layout(0, 0, 997, 751)
        return Fixture(view, note, errors, changes)
    }
    private fun send(view: InkCanvasView, action: Int, samples: List<Sample>): List<Sample> {
        check(samples.isNotEmpty())
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 7; toolType = MotionEvent.TOOL_TYPE_STYLUS })
        fun coords(p: Sample) = arrayOf(MotionEvent.PointerCoords().apply { x = p.x; y = p.y; pressure = p.pressure; size = 1f })
        val first = samples.first()
        val event = MotionEvent.obtain(1000, first.time, MotionEvent.ACTION_MOVE, 1, props, coords(first),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
        try {
            for (p in samples.drop(1)) event.addBatch(p.time, coords(p), 0)
            // Public API preserves the synthetic terminal batch's native history.
            event.action = action
            val observed = (0 until event.historySize).map { i ->
                Sample(event.getHistoricalX(0, i), event.getHistoricalY(0, i), event.getHistoricalEventTime(i), event.getHistoricalPressure(0, i))
            } + Sample(event.x, event.y, event.eventTime, event.pressure)
            check(view.onTouchEvent(event))
            return observed
        } finally { event.recycle() }
    }
    fun verify(): String {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        val checks = mutableListOf<String>()
        var oldFloatError: Double; var canonicalError: Double
        run {
            val f = fixture(); val v = f.view
            v.viewport.gesture(403.25, 321.5, 411.625, 319.875, 1.713)
            val p = Sample(421.12345f, 333.76544f, 1000)
            val scale = v.viewport.scale; val ox = v.viewport.offsetX; val oy = v.viewport.offsetY
            val native = send(v, MotionEvent.ACTION_DOWN, listOf(p)).single()
            val terminal = send(v, MotionEvent.ACTION_UP, listOf(p.copy(time = 1001))).single()
            check(native.x == terminal.x && native.y == terminal.y)
            // Compare the actual native event scalars, not pre-SDK PointerCoords setters.
            val expectedX = (native.x.toDouble() - ox) / scale
            val expectedY = (native.y.toDouble() - oy) / scale
            oldFloatError = maxOf(abs(((native.x - ox.toFloat()) / scale.toFloat()).toDouble() - expectedX),
                abs(((native.y - oy.toFloat()) / scale.toFloat()).toDouble() - expectedY))
            check(oldFloatError > 1e-5) { "Precision witness must distinguish float scene mapping: $oldFloatError" }
            check(f.note.strokeCount == 1 && f.note.pointCount(0) == 2 && f.changes[0] == 1 && f.errors.isEmpty())
            canonicalError = maxOf(abs(f.note.pointX(0, 0) - expectedX), abs(f.note.pointY(0, 0) - expectedY),
                abs(f.note.pointX(0, 1) - expectedX), abs(f.note.pointY(0, 1) - expectedY))
            check(canonicalError <= 1e-9) { "canonical-double-error=$canonicalError old-float-witness=$oldFloatError" }
            checks.add("double_scene_mapping_before_float_path")
        }
        run {
            val f = fixture(); val points = (0 until 7).map { i ->
                Sample(400f + i * 10, 300f + i * 3, 1000L + if (i == 2) 1 else i, 0.2f + i * 0.1f)
            }
            val native = send(f.view, MotionEvent.ACTION_DOWN, points.take(3)) +
                send(f.view, MotionEvent.ACTION_MOVE, points.subList(3, 5)) +
                send(f.view, MotionEvent.ACTION_UP, points.subList(5, 7))
            check(f.note.strokeCount == 1 && f.note.pointCount(0) == 7 && f.changes[0] == 1)
            for ((i, p) in native.withIndex()) {
                val expected = f.view.viewport.toScene(p.x.toDouble(), p.y.toDouble())
                check(abs(f.note.pointX(0, i) - expected.x) <= 1e-9 && abs(f.note.pointY(0, i) - expected.y) <= 1e-9)
                check(f.note.stroke(0).samples.timeMicros(i) == p.time * 1000 && f.note.pointPressure(0, i) == p.pressure)
            }
            check(f.note.undo() && f.note.strokeCount == 0)
            check(f.note.redo() && f.note.pointCount(0) == 7)
            checks.add("history_terminal_order_equal_time_and_undo")
        }
        run {
            val f = fixture(); val p = Sample(400f, 300f, 1000)
            send(f.view, MotionEvent.ACTION_DOWN, listOf(p))
            send(f.view, MotionEvent.ACTION_MOVE, listOf(p.copy(x = Float.NaN, time = 1001)))
            check(!f.note.hasActiveStroke && f.note.strokeCount == 0 && f.errors.size == 1)
            send(f.view, MotionEvent.ACTION_UP, listOf(p.copy(time = 1002)))
            check(f.note.strokeCount == 0)
            send(f.view, MotionEvent.ACTION_DOWN, listOf(p.copy(time = 1003)))
            send(f.view, MotionEvent.ACTION_UP, listOf(p.copy(time = 1004)))
            check(f.note.strokeCount == 1 && f.note.pointCount(0) == 2 && f.changes[0] == 1)
            checks.add("invalid_native_sample_cancels_and_recovers")
        }
        run {
            val f = fixture(); val p = Sample(400f, 300f, 1000)
            val before = f.note.capture().encode()
            send(f.view, MotionEvent.ACTION_DOWN, listOf(p))
            f.view.layout(0, 0, 1101, 751)
            check(!f.note.hasActiveStroke)
            send(f.view, MotionEvent.ACTION_UP, listOf(p.copy(time = 1001)))
            check(f.note.capture().encode() == before && f.note.strokeCount == 0 && f.changes[0] == 0)
            checks.add("resize_cancels_and_late_up_preserves_root")
        }
        run {
            val f = fixture(); val p = Sample(400f, 300f, 1000)
            send(f.view, MotionEvent.ACTION_DOWN, listOf(p))
            val oversized = (0..f.note.inputBatchLimit).map { p.copy(time = 1001L + it) }
            val observed = send(f.view, MotionEvent.ACTION_MOVE, oversized)
            check(observed.size > f.note.inputBatchLimit) // actual SDK history must exercise the admission limit
            check(!f.note.hasActiveStroke && f.note.totalStrokeCount == 0 && f.errors.single().contains("超限"))
            send(f.view, MotionEvent.ACTION_DOWN, listOf(p.copy(time = 3000)))
            send(f.view, MotionEvent.ACTION_UP, listOf(p.copy(time = 3001)))
            check(f.note.strokeCount == 1 && f.note.pointCount(0) == 2 && f.changes[0] == 1)
            checks.add("callback_batch_limit_explicit_cancel_and_recover")
        }
        return JSONObject().put("checks", JSONArray(checks)).put("old_float_witness_error", oldFloatError)
            .put("canonical_max_error", canonicalError).put("batch_sample_count", 7)
            .put("callback_real_batch_limit", 1024).put("down_history_included", true)
            .put("all_test_notes_in_memory", true).toString()
    }
}
