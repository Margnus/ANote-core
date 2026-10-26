package dev.notecore.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import dev.notecore.document.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Original owner/ledger/worker and real Activities; all files are unique synthetic cache fixtures. */
internal class EditorPresentationHostChecks(private val test: Instrumentation, private val host: Activity) {
    private fun main(action: () -> Unit) = test.runOnMainSync(action)
    private fun eventually(message: String, predicate: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 10000
        var done = false
        while (!done && SystemClock.uptimeMillis() < end) { main { done = predicate() }; if (!done) SystemClock.sleep(20) }
        check(done) { message }
    }
    private class Reader(val read: () -> EditorPresentation) : EditorObserver {
        val states = mutableListOf<EditorPresentation>()
        override fun changed() { states.add(read()) }
    }
    fun verify(): List<String> {
        val directory = File(host.cacheDir, "editor-presentation-${java.util.UUID.randomUUID()}")
        check(directory.mkdirs())
        try {
            verifyState(File(directory, "state"))
            verifyBackend(File(directory, "backend"))
            verifyActivities(File(directory, "activities"))
            return listOf("editor_original_ledger_old_ack_failure_and_retry", "editor_stopped_backend_feedback", "actual_activity_shared_presentation_update")
        } finally { directory.deleteRecursively() }
    }
    private fun gateFile(directory: File) {
        check(directory.mkdirs())
        File(directory, "gate.ncp").writeText("NCPREVIEW\t2\nD\tgate\tgate\t1\t0\t0\nG\tpage\tlayer\t595.0\t842.0\tBLANK\t0\nEND\n", Charsets.UTF_8)
    }
    private fun draw(note: PagedPreviewNotebook) {
        check(note.beginStroke(1.0, 2.0, .5f, 1)); check(note.endStroke(3.0, 4.0, .5f, 2))
    }
    private fun verifyState(directory: File) {
        gateFile(directory)
        val started = CountDownLatch(1); val gate = CountDownLatch(1)
        lateinit var owner: NoteStore; lateinit var note: PagedPreviewNotebook
        lateinit var first: PagedPreviewSnapshot; lateinit var latest: PagedPreviewSnapshot
        lateinit var a: Reader; lateinit var b: Reader; lateinit var broken: EditorObserver
        var thrown = 0
        main {
            owner = NoteStore.isolated(host, directory, beforeOpenRead = { started.countDown(); check(gate.await(10, TimeUnit.SECONDS)) })
            owner.open("gate") { value, error -> check(value != null && error == null) }
        }
        try {
            check(started.await(10, TimeUnit.SECONDS))
            main {
                note = owner.create(); draw(note); first = note.capture()
                a = Reader { owner.editorPresentation(note) }; b = Reader { owner.editorPresentation(note) }
                broken = EditorObserver { thrown++; error("advisory editor failure") }
                check(owner.attachEditor(note, "broken", broken) && owner.attachEditor(note, "A", a) && owner.attachEditor(note, "B", b))
                check(owner.editorPresentation(note).phase == EditorSavePhase.UNSAVED)
                check(owner.save(first) { _, _ -> })
                check(note.rename("latest")); latest = note.capture()
                // Before admission the latest canonical content cannot be labelled as the old queued root.
                check(owner.editorPresentation(note).phase == EditorSavePhase.UNSAVED)
                check(owner.save(latest) { _, _ -> })
                check(a.states.last().phase == EditorSavePhase.SAVING && b.states.last().revision == latest.version)
            }
            gate.countDown()
            eventually("Both editors did not observe latest real IO confirmation") {
                a.states.lastOrNull()?.phase == EditorSavePhase.SAVED && b.states.lastOrNull()?.phase == EditorSavePhase.SAVED
            }
            main {
                for (reader in listOf(a, b)) {
                    check(reader.states.any { it.savedRevision == first.version && it.revision == latest.version && it.phase == EditorSavePhase.SAVING })
                    check(reader.states.none { it.revision == latest.version && it.savedRevision != latest.version && it.phase == EditorSavePhase.SAVED })
                }
                check(thrown > 0 && owner.saveStates().isEmpty())
            }
            val file = File(directory, "${latest.documentId}.ncp")
            check(file.readText(Charsets.UTF_8) == latest.encode())
            val moved = File(directory.parentFile, "state-moved"); check(directory.renameTo(moved))
            directory.writeText("blocked", Charsets.UTF_8)
            var aCount = 0; lateinit var failed: PagedPreviewSnapshot
            main {
                owner.forgetEditor(note, "A"); aCount = a.states.size
                check(note.rename("failure retained")); failed = note.capture()
                check(owner.save(failed) { _, error -> check(error != null) })
            }
            eventually("Other editor did not observe original failed ledger") { b.states.lastOrNull()?.phase == EditorSavePhase.RETRY_REQUIRED }
            main { check(a.states.size == aCount && owner.saveStates().single().snapshot.sameContent(failed)) }
            check(File(moved, file.name).readText(Charsets.UTF_8) == latest.encode())
            check(directory.delete()); check(moved.renameTo(directory))
            main { check(owner.retryRetainedWrites()) }
            eventually("Remaining editor did not observe original repaired write") { b.states.lastOrNull()?.phase == EditorSavePhase.SAVED && owner.saveStates().isEmpty() }
            check(file.readText(Charsets.UTF_8) == failed.encode())
            main {
                check(a.states.size == aCount); owner.forgetEditor(note, "B"); owner.forgetEditor(note, "broken")
                check(owner.beginOwnerRetirement() == StoreRetirementRequest.STARTED)
            }
            eventually("Original presentation owner did not stop after real writes") { owner.ownerRetirementStatus().phase == StoreOwnerPhase.RETIRED }
            main { check(owner.editorPresentation(note).phase == EditorSavePhase.CLOSED) }
        } finally { gate.countDown(); main { owner.stopTestBackend() } }
    }
    private fun verifyBackend(directory: File) {
        lateinit var owner: NoteStore; lateinit var note: PagedPreviewNotebook; lateinit var saved: PagedPreviewSnapshot
        lateinit var a: Reader; lateinit var b: Reader
        main {
            owner = NoteStore.isolated(host, directory); note = owner.create(); saved = note.capture()
            a = Reader { owner.editorPresentation(note) }; b = Reader { owner.editorPresentation(note) }
            check(owner.attachEditor(note, "A", a) && owner.attachEditor(note, "B", b))
            check(owner.save(saved) { _, error -> check(error == null) })
        }
        try {
            eventually("Backend fixture did not confirm initial content") { b.states.lastOrNull()?.phase == EditorSavePhase.SAVED }
            lateinit var retained: PagedPreviewSnapshot
            main {
                owner.stopTestBackend(); check(note.rename("unsaved after stop")); retained = note.capture()
                check(owner.save(retained) { _, error -> check(error != null) })
                check(a.states.last().phase == EditorSavePhase.BACKEND_UNAVAILABLE && b.states.last().phase == EditorSavePhase.BACKEND_UNAVAILABLE)
                check(owner.saveStates().single().snapshot.sameContent(retained))
                owner.forgetEditor(note, "A"); owner.forgetEditor(note, "B")
            }
            check(File(directory, "${saved.documentId}.ncp").readText(Charsets.UTF_8) == saved.encode())
            // Deliberately stopped synthetic backend: proves refusal/retention, not restart recovery.
        } finally { main { owner.stopTestBackend() } }
    }
    private fun verifyActivities(directory: File) {
        gateFile(directory)
        val singleton = NoteStore::class.java.getDeclaredField("instance").apply { isAccessible = true }
        fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        val noteField = field("notebook"); val statusField = field("status"); val pageField = field("pageLabel")
        val titleField = field("titleView"); val undoField = field("undoButton"); val canvasField = field("canvas")
        val activities = mutableListOf<MainActivity>(); val gate = CountDownLatch(1); val started = CountDownLatch(1)
        lateinit var owner: NoteStore; lateinit var note: PagedPreviewNotebook; lateinit var expected: PagedPreviewSnapshot
        var previous: Any? = null
        fun launch() = (test.startActivitySync(Intent(host, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) as MainActivity).also { activities.add(it) }
        fun press(activity: Activity, label: String) {
            fun find(view: View): Button? {
                if (view is Button && view.text.toString() == label) return view
                if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                return null
            }
            check(checkNotNull(find(activity.window.decorView)).performClick())
        }
        fun text(activity: Activity, slot: java.lang.reflect.Field) = (slot.get(activity) as TextView).text.toString()
        main {
            check(noteField.get(host) == null); previous = singleton.get(null)
            owner = NoteStore.isolated(host, directory, beforeOpenRead = { started.countDown(); check(gate.await(10, TimeUnit.SECONDS)) })
            owner.open("gate") { value, error -> check(value != null && error == null) }
            note = owner.create(); draw(note); singleton.set(null, owner)
        }
        try {
            check(started.await(10, TimeUnit.SECONDS))
            val a = launch(); val b = launch()
            eventually("Real sibling canvas did not lay out") { (canvasField.get(b) as InkCanvasView).let { it.width > 0 && it.height > 0 } }
            var operation: String? = null
            var canvasWidth = 0; var canvasHeight = 0
            main {
                check(noteField.get(a) === note && noteField.get(b) === note && owner.editorCount == 2)
                check(note.rename("shared title"))
                val canvas = canvasField.get(b) as InkCanvasView
                canvasWidth = canvas.width; canvasHeight = canvas.height
                send(canvas, MotionEvent.ACTION_DOWN, 1000)
                operation = note.activeInputOperationId; check(operation != null)
                press(a, "保存")
                check(note.activeInputOperationId == operation && note.hasActiveStroke)
                check((canvasField.get(b) as InkCanvasView).let { it.width == canvasWidth && it.height == canvasHeight })
                check(text(b, titleField) == "shared title" && text(b, statusField).contains("保存"))
                check(!(undoField.get(b) as Button).isEnabled)
            }
            gate.countDown()
            eventually("Real sibling Activity did not receive current-root file ACK") { text(b, statusField).contains("已保存") && owner.saveStates().isEmpty() }
            val layout = CountDownLatch(1)
            main {
                val root = b.window.decorView
                root.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        root.viewTreeObserver.removeOnPreDrawListener(this); layout.countDown(); return true
                    }
                })
                root.invalidate()
            }
            check(layout.await(5, TimeUnit.SECONDS)) { "No actual layout frame after save feedback" }
            main {
                check(note.activeInputOperationId == operation && note.hasActiveStroke)
                check((canvasField.get(b) as InkCanvasView).let { it.width == canvasWidth && it.height == canvasHeight })
                check(text(b, statusField).contains("当前笔未提交"))
                send(canvasField.get(b) as InkCanvasView, MotionEvent.ACTION_UP, 1001)
                check(note.totalStrokeCount == 2 && !note.hasActiveStroke)
            }
            eventually("Other Activity did not observe committed stroke and save") { text(a, statusField).contains("已保存") && (undoField.get(a) as Button).isEnabled && owner.saveStates().isEmpty() }
            main { press(a, "加页"); check(text(b, pageField) == "2/2") }
            eventually("Page edit did not finish writing") { owner.saveStates().isEmpty() }
            main {
                expected = note.capture(); val version = note.changeVersion
                press(a, "上一页"); check(text(b, pageField) == "1/2" && note.changeVersion == version)
            }
            check(File(directory, "${expected.documentId}.ncp").readText(Charsets.UTF_8) == expected.encode())
        } finally {
            gate.countDown()
            main { activities.forEach { if (!it.isDestroyed) it.finish() } }
            test.waitForIdleSync()
            try {
                eventually("Presentation Activities did not finish") { activities.all { it.isDestroyed } && owner.editorCount == 0 }
                main { check(owner.beginOwnerRetirement() == StoreRetirementRequest.STARTED) }
                eventually("Presentation owner did not drain original resources") { owner.ownerRetirementStatus().phase == StoreOwnerPhase.RETIRED }
            } finally { main { singleton.set(null, previous) } }
        }
    }
    private fun send(view: InkCanvasView, action: Int, time: Long) {
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 7; toolType = MotionEvent.TOOL_TYPE_STYLUS })
        val coordinates = arrayOf(MotionEvent.PointerCoords().apply { x = view.width / 2f; y = view.height / 2f; pressure = .5f; size = 1f })
        val event = MotionEvent.obtain(1000, time, action, 1, properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
        try { check(view.onTouchEvent(event)) } finally { event.recycle() }
    }
}
