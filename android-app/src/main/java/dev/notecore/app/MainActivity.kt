package dev.notecore.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.*
import dev.notecore.canvas.preview.PreviewNotebook
import java.text.DateFormat
import java.util.Date
import java.util.UUID

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var store: NoteStore
    private var notebook: PreviewNotebook? = null
    private var canvas: InkCanvasView? = null
    private var status: TextView? = null
    private var titleView: TextView? = null
    private var undoButton: Button? = null
    private var redoButton: Button? = null
    private var viewGeneration = 0L
    private val navy = 0xff253247.toInt()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = NoteStore.get(this)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xfff6f8fb.toInt()) }
        setContentView(root)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            val lightBars = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(lightBars, lightBars)
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                if (notebook != null) leaveEditor() else finish()
            }
        }
        val id = savedInstanceState?.getString("document")
        if (id == null) library() else open(id)
    }
    private fun text(value: String, size: Float = 16f) = TextView(this).apply {
        text = value; textSize = size; setTextColor(navy); gravity = Gravity.CENTER_VERTICAL
    }
    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value; isAllCaps = false; textSize = 14f; minHeight = dp(44); minimumWidth = dp(58); minWidth = dp(58)
        setPadding(dp(12), 0, dp(12), 0)
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(0xff9aa7b7.toInt(), navy)))
        elevation = 0f; stateListAnimator = null
        background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0xffe9eff6.toInt()) }
        backgroundTintList = ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_pressed), intArrayOf()),
            intArrayOf(0xfff0f3f7.toInt(), 0xffdce7f7.toInt(), 0xffe9eff6.toInt()))
        layoutParams = LinearLayout.LayoutParams(-2, dp(44)).apply { marginEnd = dp(6) }
        setOnClickListener { action() }
    }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(4)) }
    private fun library() {
        viewGeneration++; val generation = viewGeneration
        notebook = null; canvas = null; status = null; root.removeAllViews()
        root.addView(text("NoteCore", 30f).apply { setPadding(dp(24), dp(20), dp(24), dp(4)) })
        root.addView(text("离线手写 · 预览版", 14f).apply { setPadding(dp(24), 0, dp(24), dp(16)) })
        root.addView(button("新建笔记") {
            val note = PreviewNotebook(UUID.randomUUID().toString(), "未命名笔记")
            editor(note); save()
        }, LinearLayout.LayoutParams(-1, dp(56)).apply { setMargins(dp(20), 0, dp(20), dp(12)) })
        val scroll = ScrollView(this); val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), dp(20)) }
        scroll.addView(list); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        list.addView(text("正在读取笔记…"))
        store.list { notes ->
            if (generation != viewGeneration || isFinishing) return@list
            list.removeAllViews()
            if (notes.isEmpty()) list.addView(text("还没有笔记，写下第一个想法。", 16f).apply { setPadding(dp(4), dp(30), 0, 0) })
            notes.forEach { note ->
                list.addView(button("${note.title}\n${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(note.modified))}") { open(note.id) },
                    LinearLayout.LayoutParams(-1, dp(88)).apply { bottomMargin = dp(10) })
            }
        }
    }
    private fun open(id: String) {
        viewGeneration++; val generation = viewGeneration
        root.removeAllViews(); root.addView(text("正在打开…").apply { setPadding(dp(24), dp(24), 0, 0) })
        store.open(id) { note, failure ->
            if (generation != viewGeneration || isFinishing) return@open
            if (note != null) { editor(note); failure?.let(::showError) } else { library(); showError(failure ?: "无法打开笔记") }
        }
    }
    private fun editor(note: PreviewNotebook) {
        viewGeneration++; notebook = note; root.removeAllViews()
        val header = row()
        header.addView(button("返回") { leaveEditor() })
        titleView = text(note.title, 18f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(8), 0, dp(8), 0); setOnClickListener { rename() } }
        header.addView(titleView, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(button("保存") { save() }); root.addView(header)
        val tools = row()
        undoButton = button("撤销") { canvas?.cancelInk(); if (note.undo()) { canvas?.invalidate(); save(); updateControls() } else showError(note.lastError) }
        redoButton = button("重做") { canvas?.cancelInk(); if (note.redo()) { canvas?.invalidate(); save(); updateControls() } else showError(note.lastError) }
        tools.addView(undoButton); tools.addView(redoButton)
        tools.addView(Switch(this).apply { text = "手指书写"; isChecked = true; setOnCheckedChangeListener { _, enabled -> canvas?.cancelInk(); canvas?.fingerDrawing = enabled } })
        root.addView(tools)
        val palette = row()
        val colors = listOf("墨黑" to navy, "深蓝" to 0xff3568be.toInt(), "朱红" to 0xffce4d52.toInt(), "松绿" to 0xff21816b.toInt())
        colors.forEach { (name, color) -> palette.addView(button(name) { canvas?.cancelInk(); canvas?.penColor = color }.apply { setTextColor(color) }) }
        listOf("细" to 2.5, "中" to 5.0, "粗" to 9.0).forEach { (name, width) -> palette.addView(button(name) { canvas?.cancelInk(); canvas?.penWidth = width }) }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(palette) })
        status = text(getString(R.string.opened, note.strokeCount), 13f).apply { setPadding(dp(20), dp(4), dp(20), dp(8)) }
        root.addView(status)
        canvas = InkCanvasView(this, note, { save(); updateControls() }, ::showError)
        root.addView(canvas, LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(dp(12), 0, dp(12), dp(12)) })
        updateControls()
    }
    private fun updateControls() { undoButton?.isEnabled = notebook?.canUndo == true; redoButton?.isEnabled = notebook?.canRedo == true }
    private fun rename() {
        canvas?.cancelInk()
        val note = notebook ?: return
        val field = EditText(this).apply { setText(note.title); selectAll(); maxLines = 1 }
        AlertDialog.Builder(this).setTitle("笔记名称").setView(field).setNegativeButton("取消", null).setPositiveButton("完成") { _, _ ->
            note.rename(field.text.toString()); titleView?.text = note.title; save()
        }.show()
    }
    private fun save() {
        val note = notebook ?: return
        if (!note.retryPendingStroke()) { showError(note.lastError); return }
        val snapshot = note.capture(); val generation = viewGeneration
        status?.text = getString(R.string.saving, note.strokeCount)
        store.save(snapshot) { version, failure ->
            if (failure != null && !isFinishing) showError(failure)
            if (generation != viewGeneration || notebook !== note || isFinishing) return@save
            if (failure == null && version == note.changeVersion) status?.text = getString(R.string.saved, note.strokeCount)
        }
    }
    private fun leaveEditor() { canvas?.cancelInk(); save(); library() }
    private fun showError(message: String) {
        if (message.isBlank()) return
        status?.text = message; Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
    @Deprecated("Preview uses a single activity")
    override fun onBackPressed() { if (notebook != null) leaveEditor() else super.onBackPressed() }
    override fun onPause() { canvas?.cancelInk(); save(); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) { notebook?.let { outState.putString("document", it.documentId) }; super.onSaveInstanceState(outState) }
}
