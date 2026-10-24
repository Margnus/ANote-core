package dev.notecore.app

import android.content.ContentResolver
import android.net.Uri
import android.util.AtomicFile
import dev.notecore.canvas.prototype.*
import dev.notecore.document.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.util.UUID

/** Serial worker capability. Contains immutable filesystem/resolver handles, no UI or owner ledger. */
internal class PreviewFileIO(private val directory: File, private val resolver: ContentResolver) {
    private fun ensureDirectory() { check(directory.isDirectory || directory.mkdirs()) { "Storage directory unavailable" } }
    private fun file(id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,128}")))
        return File(directory, "$id.ncp")
    }
    fun write(snapshot: PreviewSaveSnapshot) {
        ensureDirectory()
        val encoded = snapshot.encode(); require(encoded.length <= 16_000_000)
        val bytes = encoded.toByteArray(Charsets.UTF_8)
        val atomic = AtomicFile(file(snapshot.documentId)); val stream = atomic.startWrite()
        try { stream.write(bytes); stream.fd.sync(); atomic.finishWrite(stream) }
        catch (error: Throwable) { atomic.failWrite(stream); throw error }
        // AtomicFile.finishWrite can only log a failed rename. Verify through its serialized read path.
        atomic.openRead().use { input ->
            require(input.channel.size() == bytes.size.toLong()) { "Atomic replacement was not committed" }
            val buffer = ByteArray(8192); var offset = 0
            while (offset < bytes.size) {
                val count = input.read(buffer, 0, minOf(buffer.size, bytes.size - offset))
                require(count > 0) { "Committed file truncated" }
                for (i in 0 until count) require(buffer[i] == bytes[offset + i]) { "Committed file differs from accepted snapshot" }
                offset += count
            }
            require(input.read() == -1)
        }
    }
    fun export(uri: Uri, snapshot: PagedPreviewSnapshot) {
        require(snapshot.pending == null) { "Recovery checkpoint is not an interchange export" }
        val encoded = snapshot.encode(); require(encoded.length <= 16_000_000)
        checkNotNull(resolver.openOutputStream(uri, "wt")).use { it.write(encoded.toByteArray(Charsets.UTF_8)); it.flush() }
    }
    fun importCopy(uri: Uri): DocumentRoot {
        val bytes = checkNotNull(resolver.openInputStream(uri)).use(::readBounded)
        val id = UUID.randomUUID().toString(); check(!file(id).exists())
        val source = readPreview(decode(bytes))
        return PagedPreviewCodec.copyAsNew(source, SceneId(id),
            source.pages().associate { it.id to PageId(UUID.randomUUID().toString()) },
            source.pages().associate { it.layerId to LayerId(UUID.randomUUID().toString()) },
            source.scene.objects().associate { it.id to ObjectId(UUID.randomUUID().toString()) }
        ).also { write(PagedPreviewSnapshot.fromRoot(it)) }
    }
    fun catalog(): List<NoteSummary> {
        ensureDirectory()
        val ids = linkedSetOf<String>()
        Files.newDirectoryStream(directory.toPath()).use { stream ->
            for (path in stream) {
                val name = path.fileName.toString()
                if (name.endsWith(".ncp") || name.endsWith(".ncp.bak")) {
                    val id = name.removeSuffix(".bak").removeSuffix(".ncp")
                    if (id.matches(Regex("[a-zA-Z0-9-]{1,128}"))) ids.add(id)
                    require(ids.size <= 2000) { "Preview catalog capacity; paged production catalog required" }
                }
            }
        }
        return ids.map { id -> NoteSummary(id, runCatching { readHeader(id).title }.getOrDefault("待恢复的笔记"), file(id).lastModified()) }
    }
    private fun readHeader(id: String): PagedPreviewHeader = AtomicFile(file(id)).openRead().use { stream ->
        val input = stream.buffered(256); val bytes = java.io.ByteArrayOutputStream(1024); var newlines = 0
        while (newlines < 2) {
            val value = input.read(); require(value >= 0 && bytes.size() < 4096) { "Invalid catalog header" }
            bytes.write(value); if (value == 10) newlines++
        }
        PreviewRecoveryCodec.header(decode(bytes.toByteArray())).also { require(it.documentId == id) }
    }
    private fun readBounded(input: java.io.InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer); if (count < 0) break
            require(output.size().toLong() + count <= 32L * 1024 * 1024)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    private fun decode(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    fun read(id: String): DocumentRoot = readRecovery(id).let {
        require(it.pending == null) { "Pending recovery requires the original owner" }; it.root
    }
    fun readRecovery(id: String): PagedPreviewSnapshot = AtomicFile(file(id)).openRead().use { stream ->
        require(stream.channel.size() <= 32L * 1024 * 1024)
        val result = PreviewRecoveryCodec.read(decode(readBounded(stream)))
        requireNotNull(result.snapshot) { "Preview recovery refused: ${result.failure}" }.also { require(it.documentId == id) }
    }
    /** Typed shared reader owns format/content refusal; only local worker IO errors escape this adapter. */
    private fun readPreview(text: String): DocumentRoot {
        val result = PreviewDocumentReader.read(text)
        return requireNotNull(result.root) { "Preview document refused: ${result.failure}" }
    }
}
