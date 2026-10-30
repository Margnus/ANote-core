package dev.notecore.sample.sketch

import android.graphics.Canvas
import android.graphics.Paint
import android.os.Looper
import dev.notecore.document.*
import java.util.UUID

/** Host-owned resource: borrowed Canvas is valid only during draw; never retained by the resource. */
fun interface PageBackground { fun draw(canvas: Canvas, width: Float, height: Float) }
data class SketchTool(val label: String, val argb: Int, val width: Double)

/** Unfrozen sample policy, not another document authority or a persistence service. */
class SketchSession(tools: List<SketchTool>, val background: PageBackground) {
    val tools = tools.toList()
    init { require(tools.isNotEmpty()) }
    private val owner: () -> Unit = { check(Looper.myLooper() == Looper.getMainLooper()) { "Sketch owner must be the main thread" } }
    val notebook = checkNotNull(PagedPreviewNotebook.createValidated(UUID.randomUUID().toString(), "内存草图",
        UUID.randomUUID().toString(), ownerCheck = owner).notebook)
    val readOnly: Boolean get() = notebook.isReadOnly
    var toolIndex = 0
        private set
    init { owner(); check(selectTool(0)) }
    fun selectTool(index: Int): Boolean {
        owner()
        if (readOnly || index !in tools.indices || notebook.hasActiveStroke || notebook.hasPendingStroke) return false
        val tool = tools[index]
        val result = notebook.edit(PreviewEdit.SelectPen(tool.argb, tool.width))
        if (result.accepted) toolIndex = index
        return result.accepted
    }
    fun setReadOnly(value: Boolean): Boolean {
        owner()
        return notebook.setReadOnly(value)
    }
    fun edit(request: PreviewEdit): Boolean { owner(); return !readOnly && notebook.edit(request).accepted }

    companion object {
        fun standard(): SketchSession {
            val paint = Paint().apply { color = 0xffd1c6a5.toInt(); strokeWidth = 1f }
            return SketchSession(listOf(SketchTool("蓝笔", 0xff2563eb.toInt(), 4.0), SketchTool("红笔", 0xffdc2626.toInt(), 7.0)),
            PageBackground { canvas, width, height ->
                canvas.drawColor(0xfffffbeb.toInt())
                var y = 32f
                while (y < height) { canvas.drawLine(0f, y, width, y, paint); y += 32f }
            })
        }
    }
}
