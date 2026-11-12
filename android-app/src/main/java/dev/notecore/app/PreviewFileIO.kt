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
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
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
internal sealed interface LocalImportResult {
    data class Imported(val root: DocumentRoot) : LocalImportResult
    data class Refused(val reason: LocalReadFailure) : LocalImportResult
}
private class ReadRefused(val reason: LocalReadFailure) : IllegalArgumentException(reason.name)
private fun PreviewReadFailure.localReason(): LocalReadFailure = when (this) {
    PreviewReadFailure.CHARACTER_CAPACITY -> LocalReadFailure.CHARACTER_CAPACITY
    PreviewReadFailure.INVALID_OR_UNSUPPORTED_FORMAT -> LocalReadFailure.INVALID_FORMAT
    PreviewReadFailure.UNSUPPORTED_PREVIEW_CONTENT -> LocalReadFailure.UNSUPPORTED_CONTENT
}

/** Serial worker capability. Contains immutable filesystem/resolver handles, no UI or owner ledger. */
internal class PreviewFileIO(private val directory: File, private val resolver: ContentResolver, private val beforeRead: () -> Unit = {}) {
    private fun ensureDirectory() { check(directory.isDirectory || directory.mkdirs()) { "Storage directory unavailable" } }
    private fun file(id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,128}")))
        return File(directory, "$id.ncp")
    }
    private fun atomicFile(id: String): AtomicFile {
        val base = file(id)
        // AtomicFile may rename or delete these entries during both reads and writes.
        for (suffix in listOf("", ".bak", ".new")) {
            val path = File(directory, base.name + suffix).toPath()
            val attributes = entryAttributes(path) ?: continue
            if (!attributes.isRegularFile) throw IOException("Unexpected preview storage entry")
        }
        return AtomicFile(base)
    }
    private fun entryAttributes(path: Path): BasicFileAttributes? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) {
        null // An entry may disappear between enumeration and inspection.
    }
    fun write(snapshot: PreviewSaveSnapshot) {
        ensureDirectory()
        val atomic = atomicFile(snapshot.documentId)
        val encoded = snapshot.encode(); require(encoded.length <= PagedPreviewCodec.MAX_CHARACTERS)
        val bytes = encoded.toByteArray(Charsets.UTF_8); require(bytes.size <= PagedPreviewCodec.MAX_UTF8_BYTES)
        val stream = atomic.startWrite()
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
    fun importCopy(uri: Uri): LocalImportResult {
        // Only source-read failures are known refusals. A failed write may already be visible;
        // leave it to the original executor's unknown outcome instead of claiming rollback.
        val source = try {
            val bytes = (resolver.openInputStream(uri) ?: throw IOException("Source unavailable"))
                .use(::readBoundedPreviewBytes)
            val reading = PreviewDocumentReader.read(decode(bytes))
            reading.root ?: return LocalImportResult.Refused(checkNotNull(reading.failure).localReason())
        } catch (refusal: ReadRefused) { return LocalImportResult.Refused(refusal.reason) }
          catch (_: CharacterCodingException) { return LocalImportResult.Refused(LocalReadFailure.INVALID_UTF8) }
          catch (_: IOException) { return LocalImportResult.Refused(LocalReadFailure.IO_FAILURE) }
          catch (_: SecurityException) { return LocalImportResult.Refused(LocalReadFailure.IO_FAILURE) }
        val id = UUID.randomUUID().toString(); check(!file(id).exists())
        val copied = PagedPreviewCodec.copyAsNew(source, SceneId(id),
            source.pages().associate { it.id to PageId(UUID.randomUUID().toString()) },
            source.pages().associate { it.layerId to LayerId(UUID.randomUUID().toString()) },
            source.scene.objects().associate { it.id to ObjectId(UUID.randomUUID().toString()) }
        )
        write(PagedPreviewSnapshot.fromRoot(copied))
        return LocalImportResult.Imported(copied)
    }
    fun catalog(): List<NoteSummary> {
        ensureDirectory()
        val ids = linkedSetOf<String>()
        Files.newDirectoryStream(directory.toPath()).use { stream ->
            for (path in stream) {
                val name = path.fileName.toString()
                if (!name.endsWith(".ncp") && !name.endsWith(".ncp.bak")) continue
                val id = name.removeSuffix(".bak").removeSuffix(".ncp")
                if (!id.matches(Regex("[a-zA-Z0-9-]{1,128}")) ||
                    entryAttributes(path)?.isRegularFile != true) continue
                ids.add(id)
                require(ids.size <= 2000) { "Preview catalog capacity; paged production catalog required" }
            }
        }
        return ids.map { id -> NoteSummary(id, runCatching { readHeader(id).title }.getOrDefault("待恢复的笔记"), file(id).lastModified()) }
    }
    private fun readHeader(id: String): PagedPreviewHeader = atomicFile(id).openRead().use { stream ->
        val input = stream.buffered(256); val bytes = java.io.ByteArrayOutputStream(1024); var newlines = 0
        while (newlines < 2) {
            require(bytes.size() < 4096) { "Invalid catalog header" }
            val value = input.read(); require(value >= 0) { "Invalid catalog header" }
            bytes.write(value); if (value == 10) newlines++
        }
        PreviewRecoveryCodec.header(decode(bytes.toByteArray())).also { require(it.documentId == id) }
    }
    private fun decode(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    fun read(id: String): DocumentRoot = readRecovery(id).let {
        require(it.pending == null) { "Pending recovery requires the original owner" }; it.root
    }
    fun readRecovery(id: String): PagedPreviewSnapshot = atomicFile(id).openRead().use { stream ->
        val size = stream.channel.size()
        if (size > PagedPreviewCodec.MAX_UTF8_BYTES.toLong()) throw ReadRefused(LocalReadFailure.BYTE_CAPACITY)
        val text = try { decode(readSizedPreviewBytes(stream, size.toInt())) }
            catch (_: CharacterCodingException) { throw ReadRefused(LocalReadFailure.INVALID_UTF8) }
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

}

/** Local regular files usually retain their measured length. Growth still uses the bounded reader. */
internal fun readSizedPreviewBytes(input: java.io.InputStream, size: Int): ByteArray {
    require(size in 0..PagedPreviewCodec.MAX_UTF8_BYTES)
    val bytes = ByteArray(size)
    var offset = 0
    while (offset < bytes.size) {
        val count = input.read(bytes, offset, bytes.size - offset)
        if (count < 0) return bytes.copyOf(offset)
        if (count == 0) throw IOException("Preview input stream made no progress")
        offset += count
    }
    val extra = input.read()
    if (extra < 0) return bytes
    if (size == PagedPreviewCodec.MAX_UTF8_BYTES) throw ReadRefused(LocalReadFailure.BYTE_CAPACITY)
    return collectPreviewBytes(input, bytes, extra)
}

/** Bounded synchronous read on the original worker. A broken provider must not spin that worker forever. */
internal fun readBoundedPreviewBytes(input: java.io.InputStream): ByteArray = collectPreviewBytes(input)

private fun collectPreviewBytes(input: java.io.InputStream, prefix: ByteArray? = null, extra: Int = -1): ByteArray {
    val capacity = if (prefix == null) 8192 else maxOf(8192, Integer.highestOneBit(prefix.size) * 2)
    val output = java.io.ByteArrayOutputStream(capacity)
    val buffer = ByteArray(8192)
    if (prefix != null) {
        output.write(prefix)
        output.write(extra)
    }
    while (true) {
        val remaining = PagedPreviewCodec.MAX_UTF8_BYTES - output.size()
        // At the limit, probe one byte for EOF without reading another full chunk.
        val count = input.read(buffer, 0, minOf(buffer.size, remaining + 1))
        if (count < 0) break
        if (count == 0) throw IOException("Preview input stream made no progress")
        if (count > remaining) throw ReadRefused(LocalReadFailure.BYTE_CAPACITY)
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
