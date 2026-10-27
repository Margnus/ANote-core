package dev.notecore.app

import android.content.ContentResolver
import android.net.Uri
import android.util.AtomicFile
import dev.notecore.canvas.prototype.*
import dev.notecore.document.*
import java.io.File
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.util.UUID

/** Platform-local read result; no mutable document or secondary storage ownership. */
internal enum class LocalReadFailure(val message: String) {
    INVALID_FORMAT("笔记格式无效或版本不支持，原文件已保留"),
    UNSUPPORTED_CONTENT("当前预览不支持此笔记内容，原文件已保留"),
    CHARACTER_CAPACITY("笔记内容超过预览容量，原文件已保留"),
    BYTE_CAPACITY("笔记文件超过预览读取容量，原文件已保留"),
    INVALID_UTF8("笔记文本编码无效，原文件已保留"),
    WRONG_DOCUMENT("笔记身份与文件不一致，原文件已保留"),
    IO_FAILURE("笔记文件读取失败，请检查文件后重试")
}
internal sealed interface LocalRecoveryRead {
    data class Opened(val snapshot: PagedPreviewSnapshot) : LocalRecoveryRead
    data class Refused(val reason: LocalReadFailure) : LocalRecoveryRead
}
private class ReadRefused(val reason: LocalReadFailure) : IllegalArgumentException(reason.name)

/** Serial worker capability. Contains immutable filesystem/resolver handles, no UI or owner ledger. */
internal class PreviewFileIO(private val directory: File, private val resolver: ContentResolver, private val beforeRead: () -> Unit = {}) {
    private fun ensureDirectory() { check(directory.isDirectory || directory.mkdirs()) { "Storage directory unavailable" } }
    private fun file(id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,128}")))
        return File(directory, "$id.ncp")
    }
    fun write(snapshot: PreviewSaveSnapshot) {
        ensureDirectory()
        val encoded = snapshot.encode(); require(encoded.length <= PagedPreviewCodec.MAX_CHARACTERS)
        val bytes = encoded.toByteArray(Charsets.UTF_8); require(bytes.size <= PagedPreviewCodec.MAX_UTF8_BYTES)
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
        val encoded = snapshot.encode(); require(encoded.length <= PagedPreviewCodec.MAX_CHARACTERS)
        val bytes = encoded.toByteArray(Charsets.UTF_8); require(bytes.size <= PagedPreviewCodec.MAX_UTF8_BYTES)
        checkNotNull(resolver.openOutputStream(uri, "wt")).use { it.write(bytes); it.flush() }
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
            if (output.size().toLong() + count > PagedPreviewCodec.MAX_UTF8_BYTES.toLong()) throw ReadRefused(LocalReadFailure.BYTE_CAPACITY)
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
        if (stream.channel.size() > PagedPreviewCodec.MAX_UTF8_BYTES.toLong()) throw ReadRefused(LocalReadFailure.BYTE_CAPACITY)
        val text = try { decode(readBounded(stream)) } catch (_: CharacterCodingException) { throw ReadRefused(LocalReadFailure.INVALID_UTF8) }
        val result = PreviewRecoveryCodec.read(text)
        val snapshot = result.snapshot ?: throw ReadRefused(when (checkNotNull(result.failure)) {
            PreviewReadFailure.CHARACTER_CAPACITY -> LocalReadFailure.CHARACTER_CAPACITY
            PreviewReadFailure.INVALID_OR_UNSUPPORTED_FORMAT -> LocalReadFailure.INVALID_FORMAT
            PreviewReadFailure.UNSUPPORTED_PREVIEW_CONTENT -> LocalReadFailure.UNSUPPORTED_CONTENT
        })
        if (snapshot.documentId != id) throw ReadRefused(LocalReadFailure.WRONG_DOCUMENT)
        snapshot
    }
    fun acceptRecovery(snapshot: PagedPreviewSnapshot, id: String): LocalRecoveryRead {
        val content = PreviewDocumentReader.accept(snapshot.root)
        val reason = content.failure
        if (reason != null) return LocalRecoveryRead.Refused(when (reason) {
            PreviewReadFailure.CHARACTER_CAPACITY -> LocalReadFailure.CHARACTER_CAPACITY
            PreviewReadFailure.INVALID_OR_UNSUPPORTED_FORMAT -> LocalReadFailure.INVALID_FORMAT
            PreviewReadFailure.UNSUPPORTED_PREVIEW_CONTENT -> LocalReadFailure.UNSUPPORTED_CONTENT
        })
        return if (snapshot.documentId == id) LocalRecoveryRead.Opened(snapshot) else LocalRecoveryRead.Refused(LocalReadFailure.WRONG_DOCUMENT)
    }
    fun readOutcome(id: String): LocalRecoveryRead = try {
        beforeRead()
        LocalRecoveryRead.Opened(readRecovery(id))
    } catch (refusal: ReadRefused) { LocalRecoveryRead.Refused(refusal.reason) }
      catch (_: IOException) { LocalRecoveryRead.Refused(LocalReadFailure.IO_FAILURE) }

    /** Typed shared reader owns format/content refusal; only local worker IO errors escape this adapter. */
    private fun readPreview(text: String): DocumentRoot {
        val result = PreviewDocumentReader.read(text)
        return requireNotNull(result.root) { "Preview document refused: ${result.failure}" }
    }
}
