package dev.notecore.app

import android.content.ContentResolver
import android.net.Uri
import dev.notecore.canvas.prototype.*
import dev.notecore.document.*
import java.io.BufferedOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.CharacterCodingException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.attribute.BasicFileAttributes
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID

/** Platform filename policy only; canonical document IDs retain their broader Unicode contract. */
internal fun isLocalPreviewDocumentId(id: String): Boolean = id.length in 1..128 &&
    id.all { it == '-' || it in '0'..'9' || it in 'A'..'Z' || it in 'a'..'z' }

internal class CatalogCapacityRefusal : RuntimeException()
internal enum class PreviewWriteBoundary { PREFIX_WRITTEN_BEFORE_SYNC, SYNCED_BEFORE_REPLACE, VERIFIED_BEFORE_RETURN }

/** Platform-local read result; no mutable document or secondary storage ownership. */
internal enum class LocalReadFailure(val message: String) {
    INVALID_FORMAT("笔记格式无效或版本不支持，原文件已保留"),
    UNSUPPORTED_CONTENT("当前预览不支持此笔记内容，原文件已保留"),
    CHARACTER_CAPACITY("笔记内容超过预览容量，原文件已保留"),
    BYTE_CAPACITY("笔记文件超过预览读取容量，原文件已保留"),
    INVALID_UTF8("笔记文本编码无效，原文件已保留"),
    WRONG_DOCUMENT("笔记身份与文件不一致，原文件已保留"),
    INVALID_LOCAL_IDENTITY("笔记身份不适用于本机文件，原文件已保留"),
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
private class ReadRefused(val reason: LocalReadFailure) : IOException(reason.name)
private fun PreviewReadFailure.localReason(): LocalReadFailure = when (this) {
    PreviewReadFailure.CHARACTER_CAPACITY -> LocalReadFailure.CHARACTER_CAPACITY
    PreviewReadFailure.INVALID_OR_UNSUPPORTED_FORMAT -> LocalReadFailure.INVALID_FORMAT
    PreviewReadFailure.UNSUPPORTED_PREVIEW_CONTENT -> LocalReadFailure.UNSUPPORTED_CONTENT
}

/** Serial worker capability. Contains immutable filesystem/resolver handles, no UI or owner ledger. */
internal class PreviewFileIO(private val directory: File, private val resolver: ContentResolver, private val beforeRead: () -> Unit = {},
    private val observeWriteBoundary: ((PreviewWriteBoundary) -> Unit)? = null) {
    private fun ensureDirectory() { check(directory.isDirectory || directory.mkdirs()) { "Storage directory unavailable" } }
    private fun file(id: String): File {
        require(isLocalPreviewDocumentId(id))
        return File(directory, "$id.ncp")
    }
    private fun checkStorageEntries(base: File) {
        for (suffix in listOf("", ".bak", ".new")) {
            val path = File(directory, base.name + suffix).toPath()
            val attributes = entryAttributes(path) ?: continue
            if (!attributes.isRegularFile) throw IOException("Unexpected preview storage entry")
        }
    }
    private fun backupFile(base: File): File = File(directory, base.name + ".bak")
    private fun stagingFile(base: File): File = File(directory, base.name + ".new")
    private fun entryAttributes(path: Path): BasicFileAttributes? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) {
        null // An entry may disappear between enumeration and inspection.
    }
    fun write(snapshot: PreviewSaveSnapshot) {
        ensureDirectory()
        val base = file(snapshot.documentId)
        checkStorageEntries(base)
        val staging = stagingFile(base)
        Files.deleteIfExists(staging.toPath())
        val digest = MessageDigest.getInstance("SHA-256")
        var size: Int
        try {
            FileOutputStream(staging).use { stream ->
                val buffered = BufferedOutputStream(DigestOutputStream(stream, digest))
                size = writeSnapshot(buffered, snapshot as PagedPreviewSnapshot, observeWriteBoundary)
                buffered.flush()
                stream.fd.sync()
            }
            observeWriteBoundary?.invoke(PreviewWriteBoundary.SYNCED_BEFORE_REPLACE)
            Files.move(staging.toPath(), base.toPath(), ATOMIC_MOVE)
        } catch (error: Throwable) {
            try { Files.deleteIfExists(staging.toPath()) }
            catch (cleanup: IOException) { error.addSuppressed(cleanup) }
            throw error
        }
        val expected = digest.digest()
        FileInputStream(base).use { input ->
            require(input.channel.size() == size.toLong()) { "Atomic replacement was not committed" }
            val buffer = ByteArray(8192)
            var remaining = size
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                require(count > 0) { "Committed file truncated" }
                digest.update(buffer, 0, count)
                remaining -= count
            }
            require(input.read() == -1 && digest.digest().contentEquals(expected)) {
                "Committed file differs from accepted snapshot"
            }
        }
        // A legacy backup remains the prior recovery source until the replacement has been verified.
        Files.deleteIfExists(backupFile(base).toPath())
        observeWriteBoundary?.invoke(PreviewWriteBoundary.VERIFIED_BEFORE_RETURN)
    }
    fun export(uri: Uri, snapshot: PagedPreviewSnapshot) {
        require(snapshot.pending == null) { "Recovery checkpoint is not an interchange export" }
        checkNotNull(resolver.openOutputStream(uri, "wt")).buffered().use { writeSnapshot(it, snapshot) }
    }
    fun importCopy(uri: Uri): LocalImportResult {
        // Only source-read failures are known refusals. A failed write may already be visible;
        // leave it to the original executor's unknown outcome instead of claiming rollback.
        val source = try {
            (resolver.openInputStream(uri) ?: throw IOException("Source unavailable")).use { input ->
                readSnapshot(input, allowRecovery = false).root
            }
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
                if (name.endsWith(".ncp.new")) {
                    // A process can leave a first write behind before the document has a visible ID.
                    val id = name.removeSuffix(".ncp.new")
                    if (isLocalPreviewDocumentId(id) && entryAttributes(path)?.isRegularFile == true &&
                        entryAttributes(file(id).toPath()) == null &&
                        entryAttributes(File(directory, "$id.ncp.bak").toPath()) == null) {
                        try { Files.deleteIfExists(path) } catch (_: IOException) { /* Retry on a later scan. */ }
                    }
                    continue
                }
                if (!name.endsWith(".ncp") && !name.endsWith(".ncp.bak")) continue
                val id = name.removeSuffix(".bak").removeSuffix(".ncp")
                if (!isLocalPreviewDocumentId(id) ||
                    entryAttributes(path)?.isRegularFile != true) continue
                ids.add(id)
                if (ids.size > PreviewCatalogBudget.maxDiskRows) throw CatalogCapacityRefusal()
            }
        }
        return ids.map { id ->
            val title = try { readHeader(id).title }
                catch (_: IOException) { "待恢复的笔记" }
                catch (_: IllegalArgumentException) { "待恢复的笔记" }
            NoteSummary(id, title, file(id).lastModified())
        }
    }
    private fun readHeader(id: String): PagedPreviewHeader {
        val base = file(id)
        checkStorageEntries(base)
        val source = backupFile(base).takeIf { entryAttributes(it.toPath()) != null } ?: base
        return FileInputStream(source).use(::readHeader).also { require(it.documentId == id) }
    }

    private fun readHeader(stream: FileInputStream): PagedPreviewHeader {
        val input = stream.buffered(256); val bytes = java.io.ByteArrayOutputStream(1024); var newlines = 0
        while (newlines < 2) {
            require(bytes.size() < 4096) { "Invalid catalog header" }
            val value = input.read(); require(value >= 0) { "Invalid catalog header" }
            bytes.write(value); if (value == 10) newlines++
        }
        return PreviewRecoveryCodec.header(decode(bytes.toByteArray()))
    }
    private fun decode(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    fun read(id: String): DocumentRoot = readRecovery(id).let {
        require(it.pending == null) { "Pending recovery requires the original owner" }; it.root
    }
    fun readRecovery(id: String): PagedPreviewSnapshot {
        val base = file(id)
        checkStorageEntries(base)
        val backup = backupFile(base).takeIf { entryAttributes(it.toPath()) != null }
        val snapshot = FileInputStream(backup ?: base).use { stream ->
            if (stream.channel.size() > PagedPreviewCodec.MAX_UTF8_BYTES.toLong()) throw ReadRefused(LocalReadFailure.BYTE_CAPACITY)
            readSnapshot(stream, allowRecovery = true)
        }
        if (snapshot.documentId != id) throw ReadRefused(LocalReadFailure.WRONG_DOCUMENT)
        if (backup != null) Files.move(backup.toPath(), base.toPath(), ATOMIC_MOVE)
        Files.deleteIfExists(stagingFile(base).toPath())
        return snapshot
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
    fun readOutcome(id: String): LocalRecoveryRead {
        if (!isLocalPreviewDocumentId(id)) return LocalRecoveryRead.Refused(LocalReadFailure.INVALID_LOCAL_IDENTITY)
        return try {
            beforeRead()
            LocalRecoveryRead.Opened(readRecovery(id))
        } catch (refusal: ReadRefused) { LocalRecoveryRead.Refused(refusal.reason) }
          catch (_: IOException) { LocalRecoveryRead.Refused(LocalReadFailure.IO_FAILURE) }
    }

}

/** Counts actual bytes, including on providers without a reliable length. */
internal class BoundedPreviewInput(private val source: InputStream) : FilterInputStream(source) {
    private var remaining = PagedPreviewCodec.MAX_UTF8_BYTES

    override fun read(): Int {
        val value = source.read()
        if (value >= 0 && --remaining < 0) throw ReadRefused(LocalReadFailure.BYTE_CAPACITY)
        return value
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val count = source.read(bytes, offset, minOf(length, remaining + 1))
        if (count == 0) throw IOException("Preview input stream made no progress")
        if (count > remaining) throw ReadRefused(LocalReadFailure.BYTE_CAPACITY)
        if (count > 0) remaining -= count
        return count
    }
}

private fun readSnapshot(input: InputStream, allowRecovery: Boolean): PagedPreviewSnapshot {
    val bytes = BoundedPreviewInput(input)
    val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val reader = InputStreamReader(bytes, decoder)
    val result = try {
        if (allowRecovery) AndroidPreviewTextCodec.readCheckpoint(reader) else AndroidPreviewTextCodec.readDocument(reader)
    } catch (_: CharacterCodingException) {
        // Byte capacity takes precedence even when a provider contains invalid UTF-8 before the limit.
        val buffer = ByteArray(8192)
        while (bytes.read(buffer) >= 0) Unit
        throw ReadRefused(LocalReadFailure.INVALID_UTF8)
    }
    return result.snapshot ?: throw ReadRefused(checkNotNull(result.failure).localReason())
}

private fun writeSnapshot(output: OutputStream, snapshot: PagedPreviewSnapshot,
                          observe: ((PreviewWriteBoundary) -> Unit)? = null): Int {
    var count = 0
    var prefix = 0
    val beforeWrite: ((Int) -> Unit)? = if (observe != null) { size -> prefix = size / 2 } else null
    AndroidPreviewTextCodec.write(snapshot, beforeWrite) { line ->
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (observe != null && prefix > count && prefix <= count + bytes.size) {
            val partial = prefix - count
            output.write(bytes, 0, partial)
            output.flush()
            observe(PreviewWriteBoundary.PREFIX_WRITTEN_BEFORE_SYNC)
            output.write(bytes, partial, bytes.size - partial)
        } else output.write(bytes)
        count += bytes.size
    }
    return count
}
