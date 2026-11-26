package dev.notecore.app

import android.view.MotionEvent
import android.widget.FrameLayout
import dev.notecore.canvas.prototype.LayerId
import dev.notecore.canvas.prototype.SceneId
import dev.notecore.document.DocumentRoot
import dev.notecore.document.PageDescriptor
import dev.notecore.document.PageId
import dev.notecore.document.PagedPreviewNotebook
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InkToolPathLimitTest {
    private class GestureFixture {
        private val context = RuntimeEnvironment.getApplication()
        val note = PagedPreviewNotebook(
            DocumentRoot.empty(SceneId("tool-limit"), "Tool limit",
                PageDescriptor(PageId("page"), LayerId("layer"), 600.0, 800.0)),
            "tool-limit",
        )
        val errors = mutableListOf<String>()
        var changes = 0
            private set
        val view = InkCanvasView(context, note, { changes++ }, { errors.add(it) })
        private var time = 1000L
        private var downTime = time

        init {
            FrameLayout(context).addView(view)
            view.layout(0, 0, 600, 800)
        }

        fun touch(action: Int, x: Float, y: Float = 400f) {
            if (action == MotionEvent.ACTION_DOWN) downTime = time
            val event = MotionEvent.obtain(downTime, time++, action, x, y, 0)
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
