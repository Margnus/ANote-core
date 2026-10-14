package dev.notecore.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import android.net.Uri
import dev.notecore.canvas.preview.PreviewNotebook
import dev.notecore.canvas.preview.PreviewNotebookSnapshot
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.UUID
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

data class NoteSummary(val id: String, val title: String, val modified: Long)

/** App-owned serial IO survives Activity recreation. Only immutable snapshots cross the boundary. */
class NoteStore private constructor(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val directory = File(context.filesDir, "preview-notes").apply { mkdirs() }
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private data class Pending(val snapshot: PreviewNotebookSnapshot, val callback: (Long, String?) -> Unit)
    private val pending = linkedMapOf<String, Pending>()
    private val failed = mutableMapOf<String, PreviewNotebookSnapshot>() // preserve failed full content until a successful retry
    private var scheduled = false
    // Owner-thread capture survives Activity recreation while the system file picker is visible.
    var exportSnapshot: PreviewNotebookSnapshot? = null
    private fun file(id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,128}")))
        return File(directory, "$id.ncp")
    }
    private fun writeSnapshot(snapshot: PreviewNotebookSnapshot) {
        val atomic = AtomicFile(file(snapshot.documentId))
        val bytes = snapshot.encode().toByteArray(Charsets.UTF_8)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (error: Throwable) { atomic.failWrite(stream); throw error }
        val metadata = JSONObject().put("title", snapshot.title).toString()
        runCatching { File(directory, "${snapshot.documentId}.meta").writeText(metadata) }
    }
    fun export(uri: Uri, snapshot: PreviewNotebookSnapshot, callback: (String?) -> Unit) = io.execute {
        val failure = runCatching {
            val bytes = snapshot.encode().toByteArray(Charsets.UTF_8)
            checkNotNull(resolver.openOutputStream(uri, "wt")).use { it.write(bytes); it.flush() }
        }.exceptionOrNull()
        main.post { callback(failure?.let { "导出失败，目标文件可能不完整，请重新导出" }) }
    }
    fun importCopy(uri: Uri, callback: (PreviewNotebook?, String?) -> Unit) = io.execute {
        val result = runCatching {
            val bytes = checkNotNull(resolver.openInputStream(uri)).use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size().toLong() + count <= 32L * 1024 * 1024)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val encoded = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            val id = UUID.randomUUID().toString()
            check(!file(id).exists())
            PreviewNotebook.importAsCopy(encoded, id).also { writeSnapshot(it.capture()) }
        }
        main.post { callback(result.getOrNull(), result.exceptionOrNull()?.let { "导入失败：文件无效、超过32MB或本机无法保存；已有笔记未覆盖" }) }
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
                    writeSnapshot(job.snapshot)
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
