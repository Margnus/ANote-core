package dev.notecore.app

import android.app.Application
import android.net.Uri
import android.os.Looper
import dev.notecore.document.*
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import kotlin.test.*

/** Actual IO/Store/serial worker; resolver streams are controlled host doubles, not ART providers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class PreviewImportOutcomeTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val uri = Uri.parse("content://import-fixture/document")
    private fun valid(): ByteArray {
        val note = PagedPreviewNotebook.create("source", "source title", "source-session")
        assertTrue(note.beginStroke(1.0, 2.0, .3f, 1))
        assertTrue(note.endStroke(2.0, 3.0, .7f, 2))
        return note.capture().encode().toByteArray(Charsets.UTF_8)
    }
    private class Tracked(bytes: ByteArray, val failure: Throwable? = null) : ByteArrayInputStream(bytes) {
        var closed = false
        var reader: Thread? = null
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            reader = Thread.currentThread()
            failure?.let { throw it }
            return super.read(b, off, len)
        }
        override fun close() { closed = true; super.close() }
    }
    private fun install(stream: InputStream) = shadowOf(context.contentResolver).registerInputStream(uri, stream)
    private fun await(predicate: () -> Boolean) {
        val end = System.nanoTime() + 10_000_000_000L
        while (!predicate() && System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue(predicate(), "Original worker/main delivery timed out")
    }
    private fun preserve(directory: File) = directory.listFiles().orEmpty().associate { it.name to it.readBytes().toList() }

    @Test fun knownReadRefusalsCloseSourcesAndPreserveExistingFiles() {
        val directory = Files.createTempDirectory("preview-import-refusal-").toFile()
        try {
            File(directory, "existing.ncp").writeBytes(valid())
            val before = preserve(directory)
            val io = PreviewFileIO(directory, context.contentResolver)
            val cases = listOf(
                byteArrayOf(0xc3.toByte(), 0x28) to LocalReadFailure.INVALID_UTF8,
                "NCPREVIEW\t99\n".toByteArray() to LocalReadFailure.INVALID_FORMAT,
                valid().toString(Charsets.UTF_8).replace("MONOLINE", "PRESSURE").toByteArray() to LocalReadFailure.UNSUPPORTED_CONTENT
            )
            for ((bytes, reason) in cases) {
                val stream = Tracked(bytes); install(stream)
                assertEquals(reason, assertIs<LocalImportResult.Refused>(io.importCopy(uri)).reason)
                assertTrue(stream.closed)
                assertEquals(before, preserve(directory))
            }
            for (failure in listOf(IOException("source read failed"), SecurityException("permission revoked during read"))) {
                val stream = Tracked(valid(), failure); install(stream)
                assertEquals(LocalReadFailure.IO_FAILURE, assertIs<LocalImportResult.Refused>(io.importCopy(uri)).reason)
                assertTrue(stream.closed)
                assertEquals(before, preserve(directory))
            }
        } finally { directory.deleteRecursively() } // Only this test's unique directory.
    }

    @Test fun openPermissionFailureIsKnownButUnexpectedFaultsStillEscape() {
        val directory = Files.createTempDirectory("preview-import-open-").toFile()
        try {
            val io = PreviewFileIO(directory, context.contentResolver)
            val resolver = shadowOf(context.contentResolver)
            resolver.registerInputStreamSupplier(uri) { throw SecurityException("source permission") }
            assertEquals(LocalReadFailure.IO_FAILURE, assertIs<LocalImportResult.Refused>(io.importCopy(uri)).reason)
            val unexpected = IllegalStateException("unexpected provider fault")
            resolver.registerInputStreamSupplier(uri) { throw unexpected }
            assertSame(unexpected, assertFailsWith<IllegalStateException> { io.importCopy(uri) })
            assertEquals(emptyMap(), preserve(directory))
        } finally { directory.deleteRecursively() }
    }

    @Test fun sourceCharacterAndByteCapacityRefusalsCloseWithoutCreatingCopies() {
        val directory = Files.createTempDirectory("preview-import-capacity-").toFile()
        try {
            for ((size, reason) in listOf(
                (PagedPreviewCodec.MAX_CHARACTERS + 1) to LocalReadFailure.CHARACTER_CAPACITY,
                (PagedPreviewCodec.MAX_UTF8_BYTES + 1) to LocalReadFailure.BYTE_CAPACITY
            )) {
                var remaining = size
                var closed = false
                val input = object : InputStream() {
                    override fun read(): Int = error("Bulk expected")
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (remaining == 0) return -1
                        val count = minOf(remaining, len)
                        b.fill(0x78, off, off + count); remaining -= count; return count
                    }
                    override fun close() { closed = true }
                }
                install(input)
                assertEquals(reason, assertIs<LocalImportResult.Refused>(
                    PreviewFileIO(directory, context.contentResolver).importCopy(uri)).reason)
                assertTrue(closed); assertEquals(emptyMap(), preserve(directory))
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun originalStoreDeliversKnownFailureThenImportsAndReopensOnSameWorker() {
        val directory = Files.createTempDirectory("preview-import-store-").toFile()
        val original = valid()
        File(directory, "existing.ncp").writeBytes(original)
        val store = NoteStore.isolated(context, directory)
        try {
            val source = File(directory.parentFile, directory.name + "-source.ncp")
            try {
                source.writeBytes(original)
                val bad = Tracked(byteArrayOf(0xff.toByte())); install(bad)
                var delivered = false
                store.importCopy(uri) { value, error ->
                    assertNull(value); assertEquals(LocalReadFailure.INVALID_UTF8.message, error)
                    assertSame(Looper.getMainLooper().thread, Thread.currentThread()); delivered = true
                }
                await { delivered }
                assertTrue(bad.closed); assertNotSame(Thread.currentThread(), bad.reader)
                assertContentEquals(original, File(directory, "existing.ncp").readBytes())
                assertEquals(listOf("existing.ncp"), directory.listFiles()!!.map { it.name })
                var sourceClosed = false
                val input = object : java.io.FilterInputStream(source.inputStream()) {
                    override fun close() { sourceClosed = true; super.close() }
                }
                install(input)
                var imported: OpenedDocument? = null
                store.importCopy(uri) { value, error -> assertNull(error); imported = assertNotNull(value) }
                await { imported != null }
                assertTrue(sourceClosed)
                val copied = imported!!
                assertTrue(copied.persisted); assertNotEquals("source", copied.root.id.value)
                assertEquals(1, copied.root.scene.objectCount)
                var reopened: OpenedDocument? = null
                store.open(copied.root.id.value) { value, error -> assertNull(error); reopened = assertNotNull(value) }
                await { reopened != null }
                assertEquals(PagedPreviewCodec.encode(copied.root), PagedPreviewCodec.encode(reopened!!.root))
                assertContentEquals(original, source.readBytes())
                assertContentEquals(original, File(directory, "existing.ncp").readBytes())
            } finally { source.delete() }
        } finally {
            store.stopTestBackend()
            await { store.ownerRetirementStatus().workerStopped }
            directory.deleteRecursively()
        }
    }

    @Test fun writeFailureIsNotReportedAsSourceRefusalOrConfirmedRollback() {
        val parent = Files.createTempDirectory("preview-import-write-").toFile()
        val directory = File(parent, "blocked").apply { writeText("existing obstruction") }
        val store = NoteStore.isolated(context, directory)
        try {
            val input = Tracked(valid()); install(input)
            var delivered = false
            store.importCopy(uri) { value, error ->
                assertNull(value); assertEquals("导入结果暂无法确认，请先查看列表；已有笔记未覆盖", error)
                delivered = true
            }
            await { delivered }
            assertTrue(input.closed); assertEquals("existing obstruction", directory.readText())
        } finally {
            store.stopTestBackend(); await { store.ownerRetirementStatus().workerStopped }
            parent.deleteRecursively()
        }
    }
}
