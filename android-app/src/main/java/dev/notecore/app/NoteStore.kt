package dev.notecore.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import dev.notecore.canvas.preview.PreviewNotebook
import dev.notecore.canvas.preview.PreviewNotebookSnapshot
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

data class NoteSummary(val id: String, val title: String, val modified: Long)

/** App-owned serial IO survives Activity recreation. Only immutable snapshots cross the boundary. */
class NoteStore private constructor(context: Context) {
    private val directory = File(context.filesDir, "preview-notes").apply { mkdirs() }
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private data class Pending(val snapshot: PreviewNotebookSnapshot, val callback: (Long, String?) -> Unit)
    private val pending = linkedMapOf<String, Pending>()
    private val failed = mutableMapOf<String, PreviewNotebookSnapshot>() // preserve failed full content until a successful retry
    private var scheduled = false
    private fun file(id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,128}")))
        return File(directory, "$id.ncp")
    }
    fun save(snapshot: PreviewNotebookSnapshot, callback: (Long, String?) -> Unit) {
        synchronized(pending) {
            pending[snapshot.documentId] = Pending(snapshot, callback) // full snapshot supersedes an earlier unsaved version
            if (scheduled) return
            scheduled = true
        }
        io.execute {
            while (true) {
                val job = synchronized(pending) {
                    val key = pending.keys.firstOrNull()
                    if (key == null) { scheduled = false; null } else pending.remove(key)
                } ?: break
                val failure = runCatching {
                    val atomic = AtomicFile(file(job.snapshot.documentId))
                    val bytes = job.snapshot.encode().toByteArray(Charsets.UTF_8)
                    val stream = atomic.startWrite()
                    try { stream.write(bytes); atomic.finishWrite(stream) }
                    catch (error: Throwable) { atomic.failWrite(stream); throw error }
                    val metadata = JSONObject().put("title", job.snapshot.title).toString()
                    runCatching { File(directory, "${job.snapshot.documentId}.meta").writeText(metadata) } // derived index is recoverable
                }.exceptionOrNull()
                synchronized(pending) {
                    if (failure == null) failed.remove(job.snapshot.documentId) else failed[job.snapshot.documentId] = job.snapshot
                }
                main.post { job.callback(job.snapshot.version, failure?.let { "保存失败，请保留当前笔记并重试" }) }
            }
        }
    }
    fun list(callback: (List<NoteSummary>) -> Unit) = io.execute {
        val ids = directory.listFiles().orEmpty().filter { it.extension == "ncp" || it.name.endsWith(".ncp.bak") }
            .map { it.name.removeSuffix(".bak").removeSuffix(".ncp") } + synchronized(pending) { failed.keys.toList() }
        val notes = ids.distinct().map { id ->
                val title = synchronized(pending) { failed[id]?.title } ?: runCatching { JSONObject(File(directory, "$id.meta").readText()).getString("title") }
                    .getOrElse { runCatching { read(id).title }.getOrDefault("待恢复的笔记") }
                NoteSummary(id, title, file(id).lastModified())
            }.sortedByDescending { it.modified }
        main.post { callback(notes) }
    }
    private fun read(id: String): PreviewNotebook {
        val atomic = AtomicFile(file(id))
        val text = atomic.openRead().use { stream ->
            require(stream.channel.size() <= 32L * 1024 * 1024) { "Document too large" }
            stream.bufferedReader(Charsets.UTF_8).readText()
        }
        return PreviewNotebook.restore(text).also { require(it.documentId == id) }
    }
    fun open(id: String, callback: (PreviewNotebook?, String?) -> Unit) = io.execute {
        val retained = synchronized(pending) { failed[id] }
        val result = runCatching { if (retained == null) read(id) else PreviewNotebook.restore(retained.encode()) }
        main.post { callback(result.getOrNull(), result.exceptionOrNull()?.let { "笔记读取失败，原文件已保留" }
            ?: retained?.let { "有更改尚未保存，请点击保存重试" }) }
    }
    companion object {
        @Volatile private var instance: NoteStore? = null
        fun get(context: Context): NoteStore = instance ?: synchronized(this) {
            instance ?: NoteStore(context.applicationContext).also { instance = it }
        }
    }
}
