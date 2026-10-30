package dev.notecore.sample.sketch

import android.app.Activity
import android.os.Bundle
import android.os.Build
import android.view.WindowInsets
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.HorizontalScrollView
import android.widget.TextView
import dev.notecore.document.PreviewEdit

/** Independent in-memory consumer. Configuration changes retain the original owner/session, never encode a second scene. */
class SketchActivity : Activity() {
    lateinit var session: SketchSession
        private set
    lateinit var canvasView: SketchView
        private set
    private lateinit var status: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = lastNonConfigurationInstance as? SketchSession ?: SketchSession.standard()
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        status = TextView(this)
        layout.addView(TextView(this).apply { text = "独立内存草图 · 未保存\n退出进程后内容消失" })
        val tools = LinearLayout(this)
        fun action(label: String, run: () -> Unit) { tools.addView(Button(this).apply { text = label; setOnClickListener { run(); update() } }) }
        for ((index, tool) in session.tools.withIndex()) action(tool.label) { session.selectTool(index) }
        action("只读/编辑") { session.setReadOnly(!session.readOnly) }
        action("撤销") { session.edit(PreviewEdit.Undo) }
        action("重试") { if (!session.readOnly) session.notebook.retryPendingStroke() }
        layout.addView(HorizontalScrollView(this).apply { addView(tools) })
        layout.addView(Button(this).apply {
            text = "取消当前未完成笔"
            setOnClickListener { canvasView.cancelActiveInput(); update() }
        })
        layout.addView(status)
        canvasView = SketchView(this, session) { message -> update(message) }
        layout.addView(canvasView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(layout); update()
    }
    private fun update(message: String = "") {
        status.text = "${if (session.readOnly) "只读" else session.tools[session.toolIndex].label} · ${session.notebook.totalStrokeCount} 笔 · 未保存 $message"
        canvasView.invalidate()
    }
    override fun onRetainNonConfigurationInstance(): Any = session
    override fun onPause() {
        canvasView.cancelActiveInput()
        super.onPause()
    }
    override fun onDestroy() {
        // Only an unfinished contact is cancelled. A completed pending command is never cleared here.
        canvasView.cancelActiveInput()
        super.onDestroy()
    }
}
