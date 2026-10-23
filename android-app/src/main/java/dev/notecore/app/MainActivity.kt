package dev.notecore.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.*
import dev.notecore.document.*

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var store: NoteStore
    private var notebook: PagedPreviewNotebook? = null
    private var canvas: InkCanvasView? = null
    private var status: TextView? = null
    private var titleView: TextView? = null
    private var undoButton: Button? = null
    private var redoButton: Button? = null
    private var pageLabel: TextView? = null
    private var previousPage: Button? = null
    private var nextPage: Button? = null
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
        val id = savedInstanceState?.getString("document") ?: store.activeDocumentId
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
            val note = store.create()
            editor(note); save()
        }, LinearLayout.LayoutParams(-1, dp(56)).apply { setMargins(dp(20), 0, dp(20), dp(12)) })
        root.addView(button("导入备份") {
            try {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                }, 102)
            } catch (_: android.content.ActivityNotFoundException) { showError("系统文件选择器不可用") }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(dp(20), 0, dp(20), dp(12)) })
        status = text("", 13f).apply { setPadding(dp(20), 0, dp(20), dp(8)) }
        root.addView(status)
        val frame = FrameLayout(this)
        val list = ListView(this).apply {
            setPadding(dp(20), 0, dp(20), dp(20)); clipToPadding = false
            divider = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT); dividerHeight = dp(10)
        }
        val empty = text("正在读取笔记…", 16f).apply {
            gravity = Gravity.CENTER; setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        frame.addView(list, FrameLayout.LayoutParams(-1, -1))
        frame.addView(empty, FrameLayout.LayoutParams(-1, -1))
        list.emptyView = empty
        val adapter = NoteListAdapter(dp(88), { button("", {}) }, ::open)
        list.adapter = adapter
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        store.list { notes, failure ->
            if (generation != viewGeneration || isFinishing || isDestroyed) return@list
            empty.text = if (failure == null) "还没有笔记，写下第一个想法。" else "暂时无法读取笔记，请稍后重试。"
            adapter.replace(notes)
            failure?.let(::showError)
        }
    }

    private fun open(id: String) {
        viewGeneration++; val generation = viewGeneration
        root.removeAllViews(); root.addView(text("正在打开…").apply { setPadding(dp(24), dp(24), 0, 0) })
        store.open(id) { note, failure ->
            if (generation != viewGeneration || isFinishing || isDestroyed) return@open
            if (note != null) { editor(store.adopt(note)); failure?.let(::showError) } else { library(); showError(failure ?: "无法打开笔记") }
        }
    }
    private fun editor(note: PagedPreviewNotebook) {
        store.attachActive(note)
        viewGeneration++; notebook = note; root.removeAllViews()
        val header = row()
        header.addView(button("返回") { leaveEditor() })
        titleView = text(note.title, 18f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(8), 0, dp(8), 0); setOnClickListener { rename() } }
        header.addView(titleView, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(button("保存") { save() })
        header.addView(button("导出") { exportBackup() }); root.addView(header)
        val tools = row()
        undoButton = button("撤销") { if (note.edit(PreviewEdit.Undo).accepted) { canvas?.refreshPage(); save(); updateControls() } else showError(note.lastError) }
        redoButton = button("重做") { if (note.edit(PreviewEdit.Redo).accepted) { canvas?.refreshPage(); save(); updateControls() } else showError(note.lastError) }
        tools.addView(undoButton); tools.addView(redoButton)
        tools.addView(Switch(this).apply { text = "手指书写"; isChecked = true; setOnCheckedChangeListener { _, enabled -> canvas?.cancelInk(); canvas?.fingerDrawing = enabled } })
        root.addView(tools)
        val palette = row()
        val colors = listOf("墨黑" to navy, "深蓝" to 0xff3568be.toInt(), "朱红" to 0xffce4d52.toInt(), "松绿" to 0xff21816b.toInt())
        colors.forEach { (name, color) -> palette.addView(button(name) { canvas?.cancelInk(); canvas?.penColor = color }.apply { setTextColor(color) }) }
        listOf("细" to 2.5, "中" to 5.0, "粗" to 9.0).forEach { (name, width) -> palette.addView(button(name) { canvas?.cancelInk(); canvas?.penWidth = width }) }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(palette) })
        val viewportTools = row()
        viewportTools.addView(button("缩小") { canvas?.zoomViewport(1.0 / 1.5) })
        viewportTools.addView(button("放大") { canvas?.zoomViewport(1.5) })
        viewportTools.addView(button("适合页面") { canvas?.resetViewport() })
        viewportTools.addView(text("双指缩放", 12f))
        root.addView(viewportTools)
        addPageTools(note)
        status = text(getString(R.string.opened, note.totalStrokeCount), 13f).apply { setPadding(dp(20), dp(4), dp(20), dp(8)) }
        root.addView(status)
        canvas = InkCanvasView(this, note, { save(); updateControls() }, ::showError)
        root.addView(if (android.os.Build.VERSION.SDK_INT >= 33) InkSurfaceHost(checkNotNull(canvas),store) else checkNotNull(canvas), LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(dp(12), 0, dp(12), dp(12)) })
        updateControls()
    }
    private fun addPageTools(note: PagedPreviewNotebook) {
        val pages = row()
        fun action(content: Boolean = true, edit: () -> PreviewEditResult) {
            if (edit().accepted) { canvas?.refreshPage(); updateControls(); if (content) save() }
            else showError(note.lastError)
        }
        previousPage = button("上一页") { action(false) { note.edit(PreviewEdit.SelectPage(note.page(note.currentPageIndex - 1).id.value)) } }
        nextPage = button("下一页") { action(false) { note.edit(PreviewEdit.SelectPage(note.page(note.currentPageIndex + 1).id.value)) } }
        pages.addView(previousPage)
        pageLabel = text("").apply { setPadding(dp(8), 0, dp(8), 0); contentDescription = "当前页码" }
        pages.addView(pageLabel); pages.addView(nextPage)
        pages.addView(button("加页") { action { note.edit(PreviewEdit.InsertPage) } })
        pages.addView(button("复制页") { action { note.edit(PreviewEdit.CopyPage) } })
        pages.addView(button("删页") {
            AlertDialog.Builder(this).setTitle(if (note.pageCount == 1) "清空最后一页？" else "删除当前页？")
                .setMessage("可以通过撤销恢复。关闭笔记后不保留撤销记录。")
                .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ -> action { note.edit(PreviewEdit.DeletePage) } }.show()
        })
        pages.addView(button("前移") { if (note.currentPageIndex > 0) action { note.edit(PreviewEdit.MovePage(note.page(note.currentPageIndex - 1).id.value)) } })
        pages.addView(button("后移") { if (note.currentPageIndex < note.pageCount - 1) action {
            note.edit(PreviewEdit.MovePage(if (note.currentPageIndex + 2 < note.pageCount) note.page(note.currentPageIndex + 2).id.value else null))
        } })
        pages.addView(button("纸张") {
            val kinds = listOf(PaperKind.BLANK, PaperKind.RULED, PaperKind.GRID, PaperKind.DOTS)
            AlertDialog.Builder(this).setTitle("纸张样式").setItems(arrayOf("空白", "横线", "方格", "点阵")) { _, index ->
                action { note.edit(PreviewEdit.SetPaper(note.currentPage.width, note.currentPage.height, kinds[index])) }
            }.show()
        })
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(pages) })
    }
    private fun updateControls() {
        val note = notebook ?: return
        undoButton?.isEnabled = note.canUndo; redoButton?.isEnabled = note.canRedo
        titleView?.text = note.title
        pageLabel?.text = "${note.currentPageIndex + 1}/${note.pageCount}"
        previousPage?.isEnabled = note.currentPageIndex > 0
        nextPage?.isEnabled = note.currentPageIndex < note.pageCount - 1
    }
    private fun rename() {
        canvas?.cancelInk()
        val note = notebook ?: return
        val field = EditText(this).apply { setText(note.title); selectAll(); maxLines = 1 }
        AlertDialog.Builder(this).setTitle("笔记名称").setView(field).setNegativeButton("取消", null).setPositiveButton("完成") { _, _ ->
            if (note.edit(PreviewEdit.Rename(field.text.toString())).accepted) { updateControls(); save() } else showError(note.lastError)
        }.show()
    }
    private fun save(): Boolean {
        val note = notebook ?: return true
        if (!note.retryPendingStroke()) { showError(note.lastError); return false }
        val snapshot = note.capture(); val generation = viewGeneration
        status?.text = getString(R.string.saving, note.totalStrokeCount)
        return store.save(snapshot) { version, failure ->
            if (generation != viewGeneration || notebook !== note || isFinishing) return@save
            if (failure != null) showError(failure)
            if (failure == null && version == note.changeVersion && !note.hasPendingStroke) status?.text = getString(R.string.saved, note.totalStrokeCount)
        }
    }
    private fun leaveEditor() {
        canvas?.cancelInk()
        val note = notebook ?: return
        if (save()) { store.releaseActive(note); library() }
    }
    private fun exportBackup() {
        val note = notebook ?: return
        canvas?.cancelInk()
        if (!note.retryPendingStroke()) { showError(note.lastError); return }
        if (!store.beginExport(note.capture())) { showError("备份导出尚未结束，请先完成或取消当前导出"); return }
        try {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "application/octet-stream"
                putExtra(Intent.EXTRA_TITLE, "NoteCore-${note.documentId}.ncp")
            }, 101)
        } catch (_: android.content.ActivityNotFoundException) {
            store.takeExportSnapshot(); showError("系统文件选择器不可用")
        }
    }
    @Deprecated("Framework result callback keeps the preview dependency-free")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 101) {
            val snapshot = store.takeExportSnapshot()
            if (resultCode != RESULT_OK) return
            val uri = data?.data ?: return showError("未获取导出位置，请重试")
            if (snapshot == null) { showError("会话已重建，请重新导出"); return }
            store.export(uri, snapshot) { failure -> if (!isFinishing && !isDestroyed) {
                if (failure != null) showError(failure) else Toast.makeText(this, "备份已导出", Toast.LENGTH_LONG).show()
            } }
        } else if (requestCode == 102 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return showError("未获取导入文件")
            val generation = viewGeneration
            store.importCopy(uri) { note, failure ->
                if (isFinishing || isDestroyed || generation != viewGeneration) return@importCopy
                if (note != null) { val session = store.adopt(note); editor(session); status?.text = getString(R.string.imported, session.totalStrokeCount) }
                else showError(failure ?: "导入失败")
            }
        }
    }
    private fun showError(message: String) {
        if (message.isBlank()) return
        status?.text = message; Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
    @Deprecated("Preview uses a single activity")
    override fun onBackPressed() { if (notebook != null) leaveEditor() else super.onBackPressed() }
    override fun onPause() { canvas?.let { store.detachInk(it) }; canvas?.cancelInk(); save(); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) { notebook?.let { outState.putString("document", it.documentId) }; super.onSaveInstanceState(outState) }
}
