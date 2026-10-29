package dev.notecore.app

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import dev.notecore.document.PagedPreviewNotebook
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import kotlin.test.*

/** Real Activity/Store/worker with a host resolver double; no actual picker/URI grant or ART claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class ImportPickerOwnershipTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private fun await(predicate: () -> Boolean) {
        val end = System.nanoTime() + 10_000_000_000L
        while (!predicate() && System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5)
        }
        assertTrue(predicate(), "Original worker/main delivery timed out")
    }
    private fun singleton(store: NoteStore?) {
        NoteStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, store)
    }
    private fun launch(activity: MainActivity) {
        MainActivity::class.java.getDeclaredMethod("importBackup").apply { isAccessible = true }.invoke(activity)
    }
    private fun result(activity: MainActivity, code: Int, status: Int, data: Intent?) {
        MainActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Intent::class.java).apply { isAccessible = true }.invoke(activity, code, status, data)
    }
    private fun stop(store: NoteStore) {
        store.stopTestBackend(); await { store.ownerRetirementStatus().workerStopped }
    }
    @Test fun originalStoreKeepsOneImportAndSharesNonreusingExportIdentity() {
        val directory = Files.createTempDirectory("import-picker-owner-").toFile()
        val store = NoteStore.isolated(context, directory)
        try {
            val first = assertNotNull(store.beginImportPicker().requestCode)
            assertEquals(ImportPickerDisposition.BUSY, store.beginImportPicker().disposition)
            assertEquals(StoreRetirementRequest.TRANSFER_PENDING, store.beginOwnerRetirement())
            assertFalse(store.takeImportPicker(first + 1))
            assertTrue(store.takeImportPicker(first)); assertFalse(store.takeImportPicker(first))
            val snapshot = PagedPreviewNotebook.create("export", "title", "session").capture()
            val export = assertNotNull(store.beginExportPicker(snapshot).requestCode)
            assertEquals(first + 1, export)
            assertEquals(ImportPickerDisposition.BUSY, store.beginImportPicker().disposition)
            assertFalse(store.takeImportPicker(export)); assertNotNull(store.takeExportPicker(export))
            val second = assertNotNull(store.beginImportPicker().requestCode)
            assertEquals(export + 1, second); assertFalse(store.takeImportPicker(first))
            assertEquals(ExportPickerDisposition.BUSY, store.beginExportPicker(snapshot).disposition)
            assertTrue(store.takeImportPicker(second))
            NoteStore::class.java.getDeclaredField("nextExportRequestCode").apply { isAccessible = true }.setInt(store, 65536)
            assertEquals(ImportPickerDisposition.IDENTITY_EXHAUSTED, store.beginImportPicker().disposition)
            assertEquals(StoreRetirementRequest.STARTED, store.beginOwnerRetirement())
            await { store.ownerRetirementStatus().workerStopped }
            assertEquals(ImportPickerDisposition.OWNER_CLOSED, store.beginImportPicker().disposition)
        } finally { stop(store); directory.deleteRecursively() }
    }

    @Test fun activityStateRestoresSameStoreAndCancellationConsumesOnlyMatchingRequest() {
        val directory = Files.createTempDirectory("import-picker-recreate-").toFile()
        val store = NoteStore.isolated(context, directory); singleton(store)
        var controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            launch(controller.get())
            val started = assertNotNull(shadowOf(controller.get()).nextStartedActivityForResult)
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, started.intent.action)
            val saved = Bundle()
            controller.pause().saveInstanceState(saved).stop().destroy()
            assertEquals(started.requestCode, saved.getInt("importRequest"))
            assertEquals(store.importPickerSession, saved.getString("importSession"))
            assertEquals(ImportPickerDisposition.BUSY, store.beginImportPicker().disposition)
            controller = Robolectric.buildActivity(MainActivity::class.java).create(saved).start().resume()
            result(controller.get(), started.requestCode + 1, Activity.RESULT_CANCELED, null)
            assertEquals(ImportPickerDisposition.BUSY, store.beginImportPicker().disposition)
            result(controller.get(), started.requestCode, Activity.RESULT_CANCELED, null)
            val next = assertNotNull(store.beginImportPicker().requestCode)
            result(controller.get(), started.requestCode, Activity.RESULT_CANCELED, null)
            assertEquals(ImportPickerDisposition.BUSY, store.beginImportPicker().disposition)
            assertTrue(store.takeImportPicker(next))
        } finally {
            controller.pause().stop().destroy(); singleton(null); stop(store); directory.deleteRecursively()
        }
    }

    @Test fun oldStoreResultCannotConsumeNewStoreRequestWithSameNumericCode() {
        val directory = Files.createTempDirectory("import-picker-session-").toFile()
        val old = NoteStore.isolated(context, File(directory, "old")); singleton(old)
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        val current = NoteStore.isolated(context, File(directory, "new"))
        try {
            launch(controller.get())
            val code = assertNotNull(shadowOf(controller.get()).nextStartedActivityForResult).requestCode
            val newCode = assertNotNull(current.beginImportPicker().requestCode)
            assertEquals(code, newCode); assertNotEquals(old.importPickerSession, current.importPickerSession)
            MainActivity::class.java.getDeclaredField("store").apply { isAccessible = true }.set(controller.get(), current)
            result(controller.get(), code, Activity.RESULT_OK, Intent().setData(Uri.parse("content://unused/source")))
            assertEquals(ImportPickerDisposition.BUSY, current.beginImportPicker().disposition)
            assertTrue(current.takeImportPicker(newCode)); assertTrue(old.takeImportPicker(code))
        } finally {
            controller.pause().stop().destroy(); singleton(null); stop(old); stop(current); directory.deleteRecursively()
        }
    }

    @Test fun duplicateAndLegacyCallbacksCannotImportTwiceOnOriginalWorker() {
        val directory = Files.createTempDirectory("import-picker-result-").toFile()
        val store = NoteStore.isolated(context, directory); singleton(store)
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            val bytes = PagedPreviewNotebook.create("source", "source title", "source-session").capture().encode().toByteArray()
            val source = File(directory, "source.ncp").apply { writeBytes(bytes) }
            val opens = AtomicInteger(); val uri = Uri.parse("content://picker-fixture/source")
            shadowOf(context.contentResolver).registerInputStreamSupplier(uri) { opens.incrementAndGet(); source.inputStream() }
            result(controller.get(), 102, Activity.RESULT_OK, Intent().setData(uri))
            launch(controller.get())
            val code = assertNotNull(shadowOf(controller.get()).nextStartedActivityForResult).requestCode
            result(controller.get(), code, Activity.RESULT_OK, Intent().setData(uri))
            result(controller.get(), code, Activity.RESULT_OK, Intent().setData(uri))
            // Invalidate the old view before delivery; committed work must survive without opening an editor.
            MainActivity::class.java.getDeclaredMethod("library").apply { isAccessible = true }.invoke(controller.get())
            // Original long-input decoding and catalog reservations can legitimately overlap to capacity.
            // Observe all accepted original requests ACKed before asking for the final catalog; never
            // weaken admission or treat a visible file as an owner receipt for the import.
            val requests = NoteStore::class.java.getDeclaredField("requestsInFlight").apply { isAccessible = true }
            await { requests.getInt(store) == 0 }
            var delivered = false
            var rows: Int? = null; var listFailure: String? = null
            store.list { notes, failure -> rows = notes.size; listFailure = failure; delivered = true }
            await { delivered }
            assertNull(listFailure); assertEquals(2, rows)
            assertEquals(1, opens.get()); assertContentEquals(bytes, source.readBytes())
            assertEquals(2, directory.listFiles()!!.count { it.extension == "ncp" })
            val next = assertNotNull(store.beginImportPicker().requestCode)
            assertTrue(store.takeImportPicker(next))
        } finally {
            controller.pause().stop().destroy(); singleton(null); stop(store); directory.deleteRecursively()
        }
    }

    @Test fun completedImportCannotReplaceSiblingEditorAcceptedInput() {
        val directory = Files.createTempDirectory("import-picker-sibling-").toFile()
        val store = NoteStore.isolated(context, directory); singleton(store)
        val importer = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        var sibling: org.robolectric.android.controller.ActivityController<MainActivity>? = null
        val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        val requests = NoteStore::class.java.getDeclaredField("requestsInFlight").apply { isAccessible = true }
        val opens = AtomicInteger()
        try {
            val bytes = PagedPreviewNotebook.create("source", "source title", "source-session").capture().encode().toByteArray()
            val source = File(directory, "source.ncp").apply { writeBytes(bytes) }
            val uri = Uri.parse("content://picker-fixture/sibling-source")
            shadowOf(context.contentResolver).registerInputStreamSupplier(uri) {
                opens.incrementAndGet(); entered.countDown(); release.await(); source.inputStream()
            }
            await { requests.getInt(store) == 0 }
            launch(importer.get())
            val code = assertNotNull(shadowOf(importer.get()).nextStartedActivityForResult).requestCode
            result(importer.get(), code, Activity.RESULT_OK, Intent().setData(uri))
            await { entered.count == 0L }
            sibling = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
            val note = assertNotNull(store.createForEditor())
            MainActivity::class.java.getDeclaredMethod("editor", PagedPreviewNotebook::class.java).apply { isAccessible = true }.invoke(sibling.get(), note)
            val root = note.snapshot()
            assertTrue(note.beginStroke(1.0, 2.0, .5f, 1)); assertTrue(note.appendStroke(3.0, 4.0, .5f, 2))
            val operation = note.activeInputOperationId; val reservation = note.inputReservedByteCharge
            release.countDown(); await { requests.getInt(store) == 0 }
            assertEquals(note.documentId, store.activeDocumentId); assertSame(root, note.snapshot())
            assertTrue(note.hasActiveStroke); assertEquals(operation, note.activeInputOperationId)
            assertEquals(reservation, note.inputReservedByteCharge); assertEquals(1, store.editorCount)
            assertContentEquals(bytes, source.readBytes()); assertEquals(1, opens.get())
            assertEquals(2, directory.listFiles()!!.count { it.extension == "ncp" })
            val failures = org.robolectric.shadows.ShadowLog.getLogsForTag("NoteCoreStorage").filter { it.type >= android.util.Log.ERROR }
            assertTrue(failures.isEmpty(), failures.joinToString { it.msg + ": " + it.throwable?.javaClass?.simpleName })
            assertEquals("备份已导入为新笔记，可返回列表查看", org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
            assertTrue(note.endStroke(5.0, 6.0, .5f, 3)); assertEquals(3, note.pointCount(0))
            var saved = false
            assertTrue(store.save(note.capture()) { _, failure -> assertNull(failure); saved = true })
            await { saved && store.saveStates().isEmpty() }
            assertEquals(note.capture().encode(), File(directory, note.documentId + ".ncp").readText())
        } finally {
            release.countDown(); sibling?.pause()?.stop()?.destroy()
            importer.pause().stop().destroy(); singleton(null); stop(store); directory.deleteRecursively()
        }
    }

    @Test fun unavailablePickerReleasesAdmissionAndAllowsANewRequest() {
        val directory = Files.createTempDirectory("import-picker-launch-").toFile()
        val store = NoteStore.isolated(context, directory); singleton(store)
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            shadowOf(context).checkActivities(true)
            launch(controller.get()) // No OPEN_DOCUMENT activity registered: real ActivityNotFoundException.
            assertNull(MainActivity::class.java.getDeclaredField("importRequestCode").apply { isAccessible = true }.get(controller.get()))
            val code = assertNotNull(store.beginImportPicker().requestCode)
            assertEquals(1001, code); assertTrue(store.takeImportPicker(code))
        } finally {
            shadowOf(context).checkActivities(false)
            controller.pause().stop().destroy(); singleton(null); stop(store); directory.deleteRecursively()
        }
    }
}
