package dev.notecore.app

import android.app.Activity
import android.app.Instrumentation
import android.os.SystemClock
import java.io.File
import dev.notecore.document.*

/** Original Store and real IO; all fixtures stay inside a uniquely named cache directory. */
internal class CatalogRetentionHostChecks(private val test: Instrumentation, private val host: Activity) {
    private fun main(action: () -> Unit) = test.runOnMainSync(action)
    private fun await(predicate: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 15000
        var done = false
        while (!done && SystemClock.uptimeMillis() < end) { main { done = predicate() }; if (!done) SystemClock.sleep(20) }
        check(done) { "Catalog owner callback did not finish" }
    }
    fun verify() {
        val dir = File(host.cacheDir, "catalog-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val moved = File(dir.path + "-held")
        lateinit var store: NoteStore
        fun fixture(id: String) = "NCPREVIEW\t2\nD\t$id\t中文😀\t1\t0\t0\nG\tpage\tlayer\t595.0\t842.0\tBLANK\t0\nEND\n"
        File(dir, "catalog-a.ncp").writeText(fixture("catalog-a"), Charsets.UTF_8)
        File(dir, "catalog-b.ncp").writeText(fixture("catalog-b"), Charsets.UTF_8)
        main { store = NoteStore.isolated(host, dir) }
        var rows: List<NoteSummary>? = null; var failure: String? = null; var calls = 0
        fun refresh() { main { store.list { value, error -> rows = value; failure = error; calls++ } } }
        try {
            refresh(); await { calls == 1 }; check(failure == null && rows?.size == 2)
            check(dir.renameTo(moved)); dir.writeText("blocked catalog", Charsets.UTF_8)
            refresh(); await { calls == 2 }
            check(failure != null && rows?.map { it.id }?.toSet() == setOf("catalog-a", "catalog-b")) {
                "Catalog IO failure discarded original disk rows: $rows"
            }
            main { check(store.catalogRetainedByteCharge == 1588L) }
            check(dir.delete() && moved.renameTo(dir))
            refresh(); await { calls == 3 }; check(failure == null && rows?.size == 2)
            val bad = File(dir, "bad-header.ncp").apply { writeText("broken header", Charsets.UTF_8) }
            val badCalls = calls; refresh(); await { calls == badCalls + 1 }
            check(failure == null && rows!!.single { it.id == "bad-header" }.title == "待恢复的笔记")
            check(bad.readText(Charsets.UTF_8) == "broken header" && bad.delete())
            val cleanCalls = calls; refresh(); await { calls == cleanCalls + 1 }; check(rows!!.size == 2)
            main { store.stopTestBackend() }
            val stoppedCalls = calls; refresh(); await { calls == stoppedCalls + 1 }
            check(failure != null && rows!!.size == 2)
            for (limit in listOf(0L, 1587L, 1588L)) {
                main { store = NoteStore.isolated(host, dir, catalogResultByteLimit = limit) }
                val prior = calls; refresh(); await { calls == prior + 1 }
                if (limit < 1588) {
                    check(rows!!.isEmpty() && failure == "列表超过预览元数据容量，原列表与文件仍保留")
                    main { check(store.catalogRetainedByteCharge == 0L) }
                } else {
                    check(rows!!.size == 2 && failure == null)
                    main { check(store.catalogRetainedByteCharge == 1588L) }
                    val extra = File(dir, "extra.ncp").apply { writeText(fixture("extra"), Charsets.UTF_8) }
                    val more = calls; refresh(); await { calls == more + 1 }
                    check(failure == "列表超过预览元数据容量，原列表与文件仍保留" && rows!!.size == 2)
                    main { check(store.catalogRetainedByteCharge == 1588L) }
                    check(extra.delete()); val repair = calls; refresh(); await { calls == repair + 1 }
                    check(failure == null && rows!!.size == 2)
                }
                main { store.stopTestBackend() }
            }
            val maximumTitle = "中".repeat(118) + "😀"
            for (i in 2 until 2000) {
                val id = "n" + i.toString().padStart(127, '0')
                File(dir, "$id.ncp").writeText(fixture(id).replace("中文😀", maximumTitle), Charsets.UTF_8)
            }
            main { store = NoteStore.isolated(host, dir) }
            var prior = calls; refresh(); await { calls == prior + 1 }
            check(failure == null && rows!!.size == 2000 && rows!!.map { it.id }.toSet().size == 2000)
            check(rows!!.count { it.id.length == 128 && it.title == maximumTitle } == 1998)
            val allIds = rows!!.map { it.id }.toSet()
            val overflow = File(dir, "overflow.ncp").apply { writeText(fixture("overflow"), Charsets.UTF_8) }
            prior = calls; refresh(); await { calls == prior + 1 }
            check(failure == "列表超过预览记录容量，原列表与文件仍保留" && rows!!.map { it.id }.toSet() == allIds)
            check(overflow.readText(Charsets.UTF_8) == fixture("overflow")); check(overflow.delete())
            prior = calls; refresh(); await { calls == prior + 1 }; check(failure == null && rows!!.size == 2000)
            check(dir.renameTo(moved)); dir.writeText("blocked catalog", Charsets.UTF_8)
            main {
                repeat(8) { i ->
                    val root = PagedPreviewNotebook.create("retained-$i", "中".repeat(120), "retained-$i").capture()
                    check(store.save(root) { _, error -> check(error != null) })
                }
            }
            await { store.saveStates().size == 8 && store.saveStates().all { it.retryRequired } }
            prior = calls; refresh(); await { calls == prior + 1 }
            check(failure != null && rows!!.size == 2008 && rows!!.count { it.title.endsWith("（未保存）") } == 8)
            main { check(store.catalogRetainedByteCharge <= 2L * 1024 * 1024) }
            check(dir.delete() && moved.renameTo(dir))
            main { check(store.retryRetainedWrites()) }; await { store.saveStates().isEmpty() }
            main { check(store.beginOwnerRetirement() == StoreRetirementRequest.STARTED) }
            await { store.ownerRetirementStatus().phase == StoreOwnerPhase.RETIRED }
            main { check(store.catalogRetainedByteCharge == 0L) }
        } finally {
            main { store.stopTestBackend() }
            dir.deleteRecursively(); moved.deleteRecursively()
        }
    }
}
