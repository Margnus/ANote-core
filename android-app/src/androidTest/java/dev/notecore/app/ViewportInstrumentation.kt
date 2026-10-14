package dev.notecore.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import dev.notecore.canvas.preview.PreviewNotebook
import kotlin.math.abs

/** Dedicated emulator checks: inject real Android multi-pointer events through the window. */
class ViewportInstrumentation : Instrumentation() {
    private lateinit var host: Activity
    private lateinit var canvas: InkCanvasView
    private lateinit var note: PreviewNotebook
    private var downTime = 0L
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            host = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            setup()
            val cx = canvas.width / 2f; val cy = canvas.height / 2f
            event(MotionEvent.ACTION_DOWN, listOf(P(0, cx - 100, cy)))
            event(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), listOf(P(0, cx - 100, cy), P(1, cx + 100, cy)))
            event(MotionEvent.ACTION_MOVE, listOf(P(0, cx - 180, cy + 30), P(1, cx + 180, cy + 30)))
            event(MotionEvent.ACTION_POINTER_UP or (1 shl 8), listOf(P(0, cx - 180, cy + 30), P(1, cx + 180, cy + 30)))
            event(MotionEvent.ACTION_MOVE, listOf(P(0, cx - 160, cy + 50)))
            event(MotionEvent.ACTION_UP, listOf(P(0, cx - 160, cy + 50)))
            runOnMainSync { check(note.strokeCount == 0 && !note.hasActiveStroke); check(canvas.viewport.zoom > 1.5) }
            val start = canvas.viewport.toScene(cx.toDouble(), cy.toDouble())
            event(MotionEvent.ACTION_DOWN, listOf(P(0, cx, cy)))
            event(MotionEvent.ACTION_UP, listOf(P(0, cx + 30, cy + 30)))
            runOnMainSync {
                check(note.strokeCount == 1)
                check(abs(note.pointX(0, 0) - start.x) < 0.001 && abs(note.pointY(0, 0) - start.y) < 0.001)
                val bytes = note.capture().encode(); canvas.resetViewport()
                check(canvas.viewport.zoom == 1.0 && bytes == note.capture().encode())
            }
            result.putString("pinch_and_inverse_mapping", "passed")
            setup()
            val originalScale = canvas.viewport.scale
            event(MotionEvent.ACTION_DOWN, listOf(P(0, cx, cy, MotionEvent.TOOL_TYPE_STYLUS)))
            event(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), listOf(P(0, cx, cy, MotionEvent.TOOL_TYPE_STYLUS), P(1, cx - 100, cy)))
            event(MotionEvent.ACTION_POINTER_DOWN or (2 shl 8), listOf(P(0, cx, cy, MotionEvent.TOOL_TYPE_STYLUS), P(1, cx - 100, cy), P(2, cx + 100, cy)))
            event(MotionEvent.ACTION_MOVE, listOf(P(0, cx + 20, cy + 20, MotionEvent.TOOL_TYPE_STYLUS), P(1, cx - 200, cy), P(2, cx + 200, cy)))
            event(MotionEvent.ACTION_POINTER_UP or (2 shl 8), listOf(P(0, cx + 20, cy + 20, MotionEvent.TOOL_TYPE_STYLUS), P(1, cx - 200, cy), P(2, cx + 200, cy)))
            event(MotionEvent.ACTION_POINTER_UP or (1 shl 8), listOf(P(0, cx + 20, cy + 20, MotionEvent.TOOL_TYPE_STYLUS), P(1, cx - 200, cy)))
            event(MotionEvent.ACTION_UP, listOf(P(0, cx + 30, cy + 30, MotionEvent.TOOL_TYPE_STYLUS)))
            runOnMainSync { check(note.strokeCount == 1); check(canvas.viewport.scale == originalScale) }
            result.putString("stylus_transform_lock", "passed")
            setup()
            runOnMainSync { canvas.fingerDrawing = false; canvas.zoomViewport(2.0) }
            val originalX = canvas.viewport.offsetX
            val bytes = note.capture().encode()
            event(MotionEvent.ACTION_DOWN, listOf(P(0, cx, cy)))
            event(MotionEvent.ACTION_MOVE, listOf(P(0, cx + 60, cy + 30)))
            event(MotionEvent.ACTION_UP, listOf(P(0, cx + 60, cy + 30)))
            runOnMainSync {
                check(canvas.viewport.offsetX > originalX + 40)
                check(note.capture().encode() == bytes && !note.hasActiveStroke)
            }
            result.putString("finger_pan_without_ink", "passed")
            result.putString("viewport_status", "passed")
            finish(Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            result.putString("viewport_status", "failed"); result.putString("failure", failure.stackTraceToString())
            finish(Activity.RESULT_CANCELED, result)
        }
    }
    private fun setup() {
        runOnMainSync {
            note = PreviewNotebook("viewport-test", "Synthetic viewport test")
            canvas = InkCanvasView(host, note, {}, { error(it) })
            host.setContentView(canvas)
        }
        waitForIdleSync()
    }
    private data class P(val id: Int, val x: Float, val y: Float, val tool: Int = MotionEvent.TOOL_TYPE_FINGER)
    private fun event(action: Int, points: List<P>) {
        if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
        val location = IntArray(2); runOnMainSync { canvas.getLocationOnScreen(location) }
        val properties = points.map { MotionEvent.PointerProperties().apply { id = it.id; toolType = it.tool } }.toTypedArray()
        val coords = points.map { MotionEvent.PointerCoords().apply { x = it.x + location[0]; y = it.y + location[1]; pressure = 0.5f; size = 1f } }.toTypedArray()
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, points.size, properties, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { check(uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        waitForIdleSync()
    }
}
