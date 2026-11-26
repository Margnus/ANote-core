package dev.notecore.app

import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import android.widget.FrameLayout
import dev.notecore.canvas.prototype.LayerId
import dev.notecore.canvas.prototype.SceneId
import dev.notecore.document.DocumentRoot
import dev.notecore.document.PageDescriptor
import dev.notecore.document.PageId
import dev.notecore.document.PagedPreviewNotebook
import dev.notecore.document.PagedPreviewCodec
import dev.notecore.document.PaperKind
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InkToolPathLimitTest {
    private class GestureFixture(
        val note: PagedPreviewNotebook = PagedPreviewNotebook(
            DocumentRoot.empty(SceneId("tool-limit"), "Tool limit",
                PageDescriptor(PageId("page"), LayerId("layer"), 600.0, 800.0)),
            "tool-limit",
        ),
        onChanged: () -> Unit = {},
    ) {
        private val context = RuntimeEnvironment.getApplication()
        val errors = mutableListOf<String>()
        var changes = 0
            private set
        val view = InkCanvasView(context, note, { changes++; onChanged() }, { errors.add(it) })
        private var time = 1000L
        private var downTime = time
        private val finger = MotionEvent.PointerProperties().apply {
            id = 4
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        private val secondFinger = MotionEvent.PointerProperties().apply {
            id = 7
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }

        init {
            FrameLayout(context).addView(view)
            view.layout(0, 0, 600, 800)
        }

        fun touch(action: Int, x: Float, y: Float = 400f, secondaryX: Float? = null) {
            if (action == MotionEvent.ACTION_DOWN) downTime = time
            fun position(screenX: Float) = MotionEvent.PointerCoords().apply {
                this.x = screenX; this.y = y; pressure = 1f; size = 1f
            }
            val pointers = if (secondaryX == null) arrayOf(finger) else arrayOf(finger, secondFinger)
            val positions = if (secondaryX == null) arrayOf(position(x)) else arrayOf(position(x), position(secondaryX))
            val event = MotionEvent.obtain(downTime, time++, action, pointers.size, pointers, positions,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try {
                assertTrue(view.onTouchEvent(event))
            } finally {
                event.recycle()
            }
        }
        fun drawStroke(y: Float) {
            touch(MotionEvent.ACTION_DOWN, 250f, y)
            touch(MotionEvent.ACTION_MOVE, 300f, y)
            touch(MotionEvent.ACTION_UP, 350f, y)
        }
    }

    @Test
    fun twoFingerNavigationCancelsAnUncommittedEraserGesture() = with(GestureFixture()) {
        drawStroke(400f)
        val revision = note.changeVersion
        val stroke = note.stroke(0)
        view.tool = InkTool.ERASER
        touch(MotionEvent.ACTION_DOWN, 200f)
        touch(MotionEvent.ACTION_MOVE, 320f)
        val secondPointer = 1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT
        touch(MotionEvent.ACTION_POINTER_DOWN or secondPointer, 320f, secondaryX = 400f)
        touch(MotionEvent.ACTION_POINTER_UP or secondPointer, 320f, secondaryX = 400f)
        touch(MotionEvent.ACTION_UP, 320f)
        assertEquals(revision, note.changeVersion)
        assertEquals(stroke, note.stroke(0))
        assertEquals(1, changes)

        touch(MotionEvent.ACTION_DOWN, 200f)
        touch(MotionEvent.ACTION_UP, 320f)
        assertEquals(0, note.strokeCount)
        assertEquals(2, changes)
        assertTrue(errors.isEmpty())
    }

    private fun await(description: String, ready: () -> Boolean) {
        val loop = shadowOf(Looper.getMainLooper())
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            loop.idle()
            if (ready()) return
            Thread.sleep(10)
        }
        assertTrue(ready(), description)
    }

    @Test
    fun lassoMoveCanUndoRedoAndSurvivesStoreReopen() {
        val context = RuntimeEnvironment.getApplication()
        val directory = Files.createTempDirectory("notecore-lasso-").toFile()
        val store = NoteStore.isolated(context, directory)
        try {
            val note = store.create()
            assertTrue(note.setPaper(600.0, 800.0, PaperKind.BLANK))
            var saved = -1L
            fun save() {
                assertTrue(store.save(note.capture()) { revision, failure ->
                    assertNull(failure)
                    saved = revision
                })
            }
            with(GestureFixture(note, ::save)) {
                drawStroke(400f)
                drawStroke(600f)
                val first = note.stroke(0)
                val second = note.stroke(1)
                view.tool = InkTool.LASSO
                touch(MotionEvent.ACTION_DOWN, 220f, 350f)
                touch(MotionEvent.ACTION_MOVE, 380f, 350f)
                touch(MotionEvent.ACTION_MOVE, 380f, 450f)
                touch(MotionEvent.ACTION_UP, 220f, 450f)
                assertEquals(listOf(first.id), note.selectedStrokeIds)
                touch(MotionEvent.ACTION_DOWN, 300f)
                touch(MotionEvent.ACTION_UP, 320f)
                assertEquals(first.transform.tx + 20.0, note.stroke(0).transform.tx, 1e-6)
                assertEquals(second, note.stroke(1))
                val moved = note.stroke(0).transform
                assertTrue(note.undo())
                assertEquals(first.transform, note.stroke(0).transform)
                assertTrue(note.redo())
                assertEquals(moved, note.stroke(0).transform)
                assertTrue(errors.isEmpty())
            }
            save()
            await("redone transform saved") { saved == note.changeVersion }
            val id = note.documentId
            val expected = PagedPreviewCodec.encode(note.snapshot())
            assertTrue(store.releaseActive(note))
            val reader = NoteStore.isolated(context, directory)
            try {
                var reopened: OpenedDocument? = null
                reader.open(id) { result, failure ->
                    assertNull(failure)
                    reopened = assertNotNull(result)
                }
                await("fresh store read the saved note") { reopened != null }
                assertEquals(expected, PagedPreviewCodec.encode(checkNotNull(reopened).root))
            } finally {
                reader.stopTestBackend()
                await("reader stopped") { reader.ownerRetirementStatus().workerStopped }
            }
        } finally {
            store.stopTestBackend()
            await("writer stopped") { store.ownerRetirementStatus().workerStopped }
            directory.deleteRecursively()
        }
    }

    @Test
    fun disabledFingerWritingStillAllowsEraserAndLasso() = with(GestureFixture()) {
        drawStroke(400f)
        val stroke = note.stroke(0).id
        val revision = note.changeVersion
        view.fingerDrawing = false
        drawStroke(600f)
        assertEquals(1, note.strokeCount)
        assertEquals(revision, note.changeVersion)

        view.tool = InkTool.LASSO
        touch(MotionEvent.ACTION_DOWN, 220f, 350f)
        touch(MotionEvent.ACTION_MOVE, 380f, 350f)
        touch(MotionEvent.ACTION_MOVE, 380f, 450f)
        touch(MotionEvent.ACTION_UP, 220f, 450f)
        assertEquals(listOf(stroke), note.selectedStrokeIds)
        assertEquals(revision, note.changeVersion)

        view.tool = InkTool.ERASER
        touch(MotionEvent.ACTION_DOWN, 200f)
        touch(MotionEvent.ACTION_UP, 300f)
        assertEquals(0, note.strokeCount)
        assertEquals(2, changes)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun overflowingEraserDoesNotCommitAndExactLimitCanRetry() = with(GestureFixture()) {
        drawStroke(400f)
        assertEquals(1, note.strokeCount)
        val revision = note.changeVersion
        val stroke = note.stroke(0).id
        assertEquals(1, changes)

        view.tool = InkTool.ERASER
        fun fillToLimit() {
            touch(MotionEvent.ACTION_DOWN, 300f)
            repeat(4095) { touch(MotionEvent.ACTION_MOVE, 300f) }
        }
        fillToLimit()
        assertTrue(errors.isEmpty())
        touch(MotionEvent.ACTION_MOVE, 300f)
        assertEquals(1, errors.size)
        assertTrue(errors.single().contains("4096"))
        touch(MotionEvent.ACTION_MOVE, 300f)
        touch(MotionEvent.ACTION_UP, 300f)
        assertEquals(1, errors.size)
        assertEquals(revision, note.changeVersion)
        assertEquals(stroke, note.stroke(0).id)
        assertEquals(1, changes)

        fillToLimit()
        touch(MotionEvent.ACTION_UP, 305f)
        assertEquals(2, errors.size)
        assertTrue(errors.last().contains("4096"))
        assertEquals(revision, note.changeVersion)
        assertEquals(stroke, note.stroke(0).id)

        fillToLimit()
        touch(MotionEvent.ACTION_UP, 300f)
        assertEquals(2, errors.size)
        assertEquals(0, note.strokeCount)
        assertEquals(2, changes)
        assertTrue(note.undo())
        assertEquals(stroke, note.stroke(0).id)

        touch(MotionEvent.ACTION_DOWN, 200f)
        touch(MotionEvent.ACTION_UP, 300f)
        assertEquals(0, note.strokeCount)
        assertEquals(3, changes)
    }

    @Test
    fun overflowingLassoPreservesSelectionAndExactLimitCanSelectAnotherStroke() = with(GestureFixture()) {
        drawStroke(400f)
        drawStroke(600f)
        assertEquals(2, note.strokeCount)
        val first = note.stroke(0).id
        val second = note.stroke(1).id
        val revision = note.changeVersion
        view.tool = InkTool.LASSO

        fun enclose(y: Float, points: Int) {
            touch(MotionEvent.ACTION_DOWN, 220f, y - 50f)
            touch(MotionEvent.ACTION_MOVE, 380f, y - 50f)
            touch(MotionEvent.ACTION_MOVE, 380f, y + 50f)
            touch(MotionEvent.ACTION_MOVE, 220f, y + 50f)
            repeat(points - 4) { index ->
                touch(MotionEvent.ACTION_MOVE, if (index % 2 == 0) 230f else 220f, y + 50f)
            }
        }
        enclose(400f, 4)
        touch(MotionEvent.ACTION_UP, 220f, 450f)
        assertEquals(listOf(first), note.selectedStrokeIds)

        enclose(600f, 4096)
        assertTrue(errors.isEmpty())
        touch(MotionEvent.ACTION_MOVE, 230f, 650f)
        assertEquals(1, errors.size)
        assertTrue(errors.single().contains("4096"))
        touch(MotionEvent.ACTION_UP, 230f, 650f)
        assertEquals(listOf(first), note.selectedStrokeIds)
        assertEquals(revision, note.changeVersion)

        enclose(600f, 4096)
        touch(MotionEvent.ACTION_UP, 220f, 650f)
        assertEquals(listOf(second), note.selectedStrokeIds)
        assertEquals(revision, note.changeVersion)
        assertEquals(2, changes)
        assertEquals(1, errors.size)

        val before = note.stroke(1).transform
        touch(MotionEvent.ACTION_DOWN, 300f, 600f)
        touch(MotionEvent.ACTION_UP, 320f, 600f)
        assertEquals(before.tx + 20.0, note.stroke(1).transform.tx, 1e-6)
        assertEquals(3, changes)
    }
}
