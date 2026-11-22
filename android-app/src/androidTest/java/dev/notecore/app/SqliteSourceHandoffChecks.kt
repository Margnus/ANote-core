package dev.notecore.app

import android.app.Activity
import android.app.Instrumentation
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import dev.notecore.canvas.prototype.*
import dev.notecore.document.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Original View/Store source handoff into a synthetic DB, confined to this test's cache. */
internal class SqliteSourceHandoffChecks(private val test: Instrumentation, private val host: Activity) {
    private fun main(action: () -> Unit) = test.runOnMainSync(action)

    fun verify(): String {
        val directory = File(host.cacheDir, "sqlite-source-handoff-${UUID.randomUUID()}")
        check(directory.mkdir())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        lateinit var store: NoteStore
        var cleanup: NoteStore? = null
        lateinit var source: PagedPreviewNotebook
        lateinit var checkpoint: PagedPreviewSnapshot
        var saved = false
        try {
            main {
                store = NoteStore.isolated(host, directory, observeWriteBoundary = {
                    if (it == PreviewWriteBoundary.SYNCED_BEFORE_REPLACE) {
                        entered.countDown()
                        check(release.await(15, TimeUnit.SECONDS))
                    }
                })
                cleanup = store
                val root = DocumentRoot.empty(SceneId("source-handoff"), "Source handoff",
                    PageDescriptor(PageId("page"), LayerId("layer"), 595.0, 842.0))
                source = PagedPreviewNotebook(root, "original-source")
                store.attachActive(source)
                checkpoint = AndroidInputHostChecks(host).captureViewPendingForProcess(source)
                check(!store.releaseActive(source))
                check(store.save(checkpoint) { _, error -> check(error == null); saved = true })
            }
            check(entered.await(8, TimeUnit.SECONDS))
            main {
                check(!saved && !store.releaseActive(source))
                check(source.captureRecovery().sameContent(checkpoint))
                check(source.hasPendingStroke && source.inputReservedByteCharge > 0)
            }
            release.countDown()
            val deadline = SystemClock.uptimeMillis() + 8000
            while (!saved && SystemClock.uptimeMillis() < deadline) {
                main { /* Drain the original owner via its normal handler. */ }
                SystemClock.sleep(20)
            }
            main {
                check(saved && store.saveStates().isEmpty())
                check(source.captureRecovery().sameContent(checkpoint))
                check(store.releaseActive(source))
                check(!source.retryPendingStroke())
                check(source.lastFailure?.kind == PreviewFailureKind.CLOSED)
            }
            val originalFile = File(directory, "${checkpoint.documentId}.ncp")
            val archived = originalFile.readBytes()
            check(archived.contentEquals(checkpoint.encode().toByteArray(Charsets.UTF_8)))
            val canonical = run {
                val opened = PagedPreviewNotebook.openRecovery(
                    PreviewRecoveryCodec.decode(archived.toString(Charsets.UTF_8)), "isolated-sqlite-import",
                    DocumentBudget(), {})
                val recovered = checkNotNull(opened.notebook)
                check(recovered.retryPendingStroke() && recovered.retryPendingStroke())
                check(recovered.totalStrokeCount == 1 && recovered.changeVersion == checkpoint.version + 1)
                val captured = recovered.capture()
                check(recovered.retire())
                captured.encode().toByteArray(Charsets.UTF_8)
            }
            val database = File(directory, "isolated.sqlite")
            SQLiteDatabase.openDatabase(database.path, null,
                SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING,
                { error("Fixture corruption must remain visible") }).use { db ->
                db.execSQL("PRAGMA synchronous=FULL")
                db.execSQL("CREATE TABLE migration(op TEXT PRIMARY KEY NOT NULL, document TEXT UNIQUE NOT NULL, original BLOB NOT NULL, canonical BLOB NOT NULL)")
                fun transaction(accept: Boolean) {
                    db.beginTransactionNonExclusive()
                    try {
                        db.rawQuery("PRAGMA synchronous", null).use { check(it.moveToFirst() && it.getInt(0) == 2) }
                        db.rawQuery("PRAGMA journal_mode", null).use { check(it.moveToFirst() && it.getString(0) == "wal") }
                        db.execSQL("INSERT INTO migration VALUES(?,?,?,?)",
                            arrayOf("source-import", checkpoint.documentId, archived, canonical))
                        if (accept) db.setTransactionSuccessful()
                    } finally { db.endTransaction() }
                }
                transaction(false)
                db.rawQuery("SELECT count(*) FROM migration", null).use { check(it.moveToFirst() && it.getInt(0) == 0) }
                check(originalFile.readBytes().contentEquals(archived))
                // The immutable file remains the recovery owner after the original session has retired.
                val retry = PagedPreviewNotebook.openRecovery(
                    PreviewRecoveryCodec.decode(originalFile.readText(Charsets.UTF_8)), "retry-after-sqlite-rollback",
                    DocumentBudget(), {})
                val retryNote = checkNotNull(retry.notebook)
                check(retryNote.retryPendingStroke() && retryNote.retryPendingStroke())
                check(retryNote.capture().encode().toByteArray(Charsets.UTF_8).contentEquals(canonical))
                check(retryNote.retire())
                transaction(true)
                db.rawQuery("SELECT document,original,canonical FROM migration WHERE op=?", arrayOf("source-import")).use {
                    check(it.moveToFirst() && it.getString(0) == checkpoint.documentId)
                    check(it.getBlob(1).contentEquals(archived) && it.getBlob(2).contentEquals(canonical))
                    check(!it.moveToNext())
                }
            }
            SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT canonical FROM migration WHERE op=?", arrayOf("source-import")).use {
                    check(it.moveToFirst() && it.getBlob(0).contentEquals(canonical) && !it.moveToNext())
                }
                db.rawQuery("PRAGMA quick_check", null).use { check(it.moveToFirst() && it.getString(0) == "ok") }
            }
            check(originalFile.readBytes().contentEquals(archived))
            fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            return JSONObject().put("retirement_before_source_receipt_refused", true)
                .put("retirement_after_source_receipt", true).put("sqlite_rollback_preserves_source", true)
                .put("new_recovery_after_rollback_same_canonical", true).put("original_source_unchanged", true)
                .put("source_sha256", sha(archived)).put("canonical_sha256", sha(canonical)).toString()
        } finally {
            release.countDown()
            main { cleanup?.stopTestBackend() }
            directory.deleteRecursively()
        }
    }
}
