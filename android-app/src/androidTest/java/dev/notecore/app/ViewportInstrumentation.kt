package dev.notecore.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import dev.notecore.canvas.preview.PreviewNotebook
import dev.notecore.canvas.prototype.*
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
            verifyExecutor()
            result.putString("executor_owner_worker", "passed")
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
    private fun verifyExecutor() {
        // This owner is the instrumentation thread, not the UI thread; join never blocks the app UI.
        val executor = BoundedOwnerExecutor(2, 8, "NoteCore-ART-test")
        try {
            val owner = Thread.currentThread().id
            var allow = false; var callbacks = 0; var runs = 0
            val input = SceneId("ART-owned-input")
            val first = executor.submit(input, 4, { executor.checkWorkerThread(); runs++; Thread.currentThread().id }) { retained, outcome ->
                check(retained === input)
                executor.checkOwnerThread(); check(Thread.currentThread().id == owner)
                check((outcome as WorkResult.Succeeded<Long>).value != owner)
                if (!allow) ResultDisposition.RETRY_REQUIRED else { callbacks++; ResultDisposition.ACKNOWLEDGED }
            } as WorkAdmission.Accepted
            executor.submit(4, { executor.checkWorkerThread(); runs++; Thread.currentThread().id }) {
                check(Thread.currentThread().id == owner); check((it as WorkResult.Succeeded<Long>).value != owner)
                callbacks++; ResultDisposition.ACKNOWLEDGED
            }
            check(executor.submit(1, { error("Rejected work executed") }) { ResultDisposition.ACKNOWLEDGED } is WorkAdmission.Rejected)
            executor.stopAccepting(); check(executor.awaitWorkerStop(3000))
            check(executor.status().chargedBytes == 8L && !executor.status().isDrained)
            check(executor.drainResults().blocked === first.ticket && callbacks == 0 && runs == 2)
            allow = true; check(executor.retryDelivery(first.ticket)); check(executor.drainResults().acknowledged == 2)
            check(callbacks == 2 && runs == 2 && executor.status().isDrained)
            val document = DocumentCloseCoordinator<String, Any>(SceneId("ART-thread-check"), ownerCheck = executor::checkOwnerThread)
            var refused = false
            val foreign = Thread { try { document.beginClose() } catch (_: IllegalStateException) { refused = true } }
            foreign.start(); foreign.join(3000); check(!foreign.isAlive && refused)
            check(document.status().phase == DocumentClosePhase.OPEN); document.beginClose()
        } finally { executor.stopAccepting(); check(executor.awaitWorkerStop(3000)) }
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
