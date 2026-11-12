package dev.notecore.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

internal object CatalogRetryHostChecks {
    fun verify(test: Instrumentation): String {
        val context = test.targetContext
        val directory = File(context.cacheDir, "catalog-retry-${UUID.randomUUID()}")
        val movedDirectory = File(directory.path + "-moved")
        val workerEntered = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val exportEntered = CountDownLatch(1)
        val releaseExport = CountDownLatch(1)
        val nextGate = AtomicReference<Pair<CountDownLatch, CountDownLatch>?>(workerEntered to releaseWorker)
        val fixture = "NCPREVIEW\t2\nD\tretry-note\tRecovered note\t1\t0\t0\nG\tpage\tlayer\t595.0\t842.0\tBLANK\t0\nEND\n"
        val storeField = NoteStore::class.java.getDeclaredField("instance").apply { isAccessible = true }
        lateinit var store: NoteStore
        var priorStore: Any? = null
        var activity: Activity? = null
        directory.writeText("blocked catalog")
        test.runOnMainSync {
            priorStore = storeField.get(null)
            store = NoteStore.isolated(context, directory, beforeOpenRead = {
                nextGate.getAndSet(null)?.let { (entered, release) ->
                    entered.countDown()
                    check(release.await(8, TimeUnit.SECONDS))
                }
            })
            storeField.set(null, store)
        }
        try {
            val host = test.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            activity = host
            val root = host.findViewById<ViewGroup>(android.R.id.content)
            val retryTitle = host.getString(R.string.retry_note_list)
            await(test, "Retry button did not appear") {
                descendants(root).filterIsInstance<Button>().any { it.text == retryTitle && it.isShown }
            }
            check(directory.readText() == "blocked catalog")
            check(directory.delete() && directory.mkdir())
            val file = File(directory, "retry-note.ncp").apply { writeText(fixture) }
            var clicked = false
            test.runOnMainSync {
                val retry = descendants(root).filterIsInstance<Button>().single { it.text == retryTitle && it.isShown }
                clicked = retry.performClick()
            }
            check(clicked)
            await(test, "Retry did not show the restored catalog") {
                val list = descendants(root).filterIsInstance<ListView>().single()
                list.count == 1 && (list.adapter.getItem(0) as NoteSummary).title == "Recovered note" &&
                    descendants(root).filterIsInstance<Button>().none { it.text == retryTitle && it.isShown }
            }
            check(file.readText() == fixture)
            check(directory.renameTo(movedDirectory))
            directory.writeText("blocked save")
            test.runOnMainSync { store.open("hold-read-worker") { _, _ -> } }
            check(workerEntered.await(8, TimeUnit.SECONDS))
            var activeId = ""
            val savingText = host.getString(R.string.saving, 0)
            test.runOnMainSync {
                check(descendants(root).filterIsInstance<Button>().single { it.text == "新建笔记" }.performClick())
                activeId = checkNotNull(store.activeDocumentId)
                check(descendants(root).filterIsInstance<TextView>().any { it.text == savingText })
                store.open(activeId) { opened, error ->
                    check(error == null)
                    check(store.adopt(checkNotNull(opened)).rename("Newer in-memory content"))
                }
            }
            releaseWorker.countDown()
            await(test, "Failed save did not reach the ledger") {
                store.saveStates().singleOrNull()?.retryRequired == true
            }
            test.runOnMainSync {
                check(descendants(root).filterIsInstance<TextView>().any { it.text == savingText }) {
                    "An old failure replaced the newer editor status"
                }
            }
            check(directory.readText() == "blocked save")
            check(directory.delete() && movedDirectory.renameTo(directory))
            test.runOnMainSync {
                check(descendants(root).filterIsInstance<Button>().single { it.text == "保存" }.performClick())
            }
            await(test, "Current editor could not save after repair") {
                store.saveStates().isEmpty() &&
                    descendants(root).filterIsInstance<TextView>().any { it.text == host.getString(R.string.saved, 0) }
            }
            check(File(directory, "$activeId.ncp").readText().contains("Newer in-memory content"))
            check(file.readText() == fixture)
            val blockedExport = File(directory, "unwritable-export").apply { check(mkdir()) }
            val marker = File(blockedExport, "keep").apply { writeText("unchanged export target") }
            val statusField = host.javaClass.getDeclaredField("status").apply { isAccessible = true }
            val codeField = host.javaClass.getDeclaredField("exportRequestCode").apply { isAccessible = true }
            val sessionField = host.javaClass.getDeclaredField("exportPickerSession").apply { isAccessible = true }
            val pickerResult = host.javaClass.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Intent::class.java).apply { isAccessible = true }
            nextGate.set(exportEntered to releaseExport)
            test.runOnMainSync { store.open("hold-export-worker") { _, _ -> } }
            check(exportEntered.await(8, TimeUnit.SECONDS))
            var afterExport = false
            lateinit var libraryStatus: TextView
            test.runOnMainSync {
                store.open(activeId) { opened, error ->
                    check(error == null)
                    val request = store.beginExportPicker(checkNotNull(opened).recovery)
                    val code = checkNotNull(request.requestCode)
                    codeField.set(host, code)
                    sessionField.set(host, store.exportPickerSession)
                    pickerResult.invoke(host, code, Activity.RESULT_OK, Intent().setData(Uri.fromFile(blockedExport)))
                }
                check(descendants(root).filterIsInstance<Button>().single { it.text == "返回" }.performClick())
                check(store.activeDocumentId == null)
                libraryStatus = statusField.get(host) as TextView
                libraryStatus.text = "Current library status"
                store.list { _, error -> check(error == null); afterExport = true }
            }
            releaseExport.countDown()
            await(test, "Export and following catalog read did not finish") { afterExport }
            test.runOnMainSync {
                check(libraryStatus.text == "Current library status") { "An old export failure changed the new page" }
            }
            check(marker.readText() == "unchanged export target" && file.readText() == fixture)
            return JSONObject().put("checks", JSONArray(listOf(
                "activity_retry_displays_repaired_catalog", "activity_ignores_stale_save_failure",
                "activity_export_failure_preserves_new_page_status"
            ))).toString()
        } finally {
            releaseWorker.countDown()
            releaseExport.countDown()
            test.runOnMainSync {
                activity?.finish()
                storeField.set(null, priorStore)
                store.stopTestBackend()
            }
            directory.deleteRecursively()
            movedDirectory.deleteRecursively()
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
    }

    private fun await(test: Instrumentation, message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8000
        var done = false
        while (!done && SystemClock.uptimeMillis() < deadline) {
            test.runOnMainSync { done = predicate() }
            if (!done) SystemClock.sleep(20)
        }
        check(done) { message }
    }
}
