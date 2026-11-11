package dev.notecore.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ListView
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal object CatalogRetryHostChecks {
    fun verify(test: Instrumentation): String {
        val context = test.targetContext
        val directory = File(context.cacheDir, "catalog-retry-${UUID.randomUUID()}")
        val fixture = "NCPREVIEW\t2\nD\tretry-note\tRecovered note\t1\t0\t0\nG\tpage\tlayer\t595.0\t842.0\tBLANK\t0\nEND\n"
        val storeField = NoteStore::class.java.getDeclaredField("instance").apply { isAccessible = true }
        lateinit var store: NoteStore
        var priorStore: Any? = null
        var activity: Activity? = null
        directory.writeText("blocked catalog")
        test.runOnMainSync {
            priorStore = storeField.get(null)
            store = NoteStore.isolated(context, directory)
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
            return JSONObject().put("checks", JSONArray(listOf("activity_retry_displays_repaired_catalog"))).toString()
        } finally {
            test.runOnMainSync {
                activity?.finish()
                storeField.set(null, priorStore)
                store.stopTestBackend()
            }
            directory.deleteRecursively()
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
