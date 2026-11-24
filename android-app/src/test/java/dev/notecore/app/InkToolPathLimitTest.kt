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
    @Test
    fun overflowingEraserDoesNotCommitAndExactLimitCanRetry() {
        val context = RuntimeEnvironment.getApplication()
        val note = PagedPreviewNotebook(
            DocumentRoot.empty(SceneId("tool-limit"), "Tool limit",
                PageDescriptor(PageId("page"), LayerId("layer"), 600.0, 800.0)),
            "tool-limit",
        )
        val errors = mutableListOf<String>()
        var changes = 0
        val view = InkCanvasView(context, note, { changes++ }, { errors.add(it) })
        FrameLayout(context).addView(view)
        view.layout(0, 0, 600, 800)
        var time = 1000L
        var downTime = time
        fun touch(action: Int, x: Float) {
            if (action == MotionEvent.ACTION_DOWN) downTime = time
            val event = MotionEvent.obtain(downTime, time++, action, x, 400f, 0)
            try {
                assertTrue(view.onTouchEvent(event))
            } finally {
                event.recycle()
            }
        }
        touch(MotionEvent.ACTION_DOWN, 250f)
        touch(MotionEvent.ACTION_MOVE, 300f)
        touch(MotionEvent.ACTION_UP, 350f)
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
        touch(MotionEvent.ACTION_UP, 300f)
        assertEquals(1, errors.size)
        assertEquals(0, note.strokeCount)
        assertEquals(2, changes)
        assertTrue(note.undo())
        assertEquals(stroke, note.stroke(0).id)
    }
}
