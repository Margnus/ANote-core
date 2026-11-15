package dev.notecore.app

import android.app.Activity
import android.app.Instrumentation
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import dev.notecore.document.*
import java.io.File
import java.util.concurrent.CountDownLatch
import org.json.JSONObject

/** Only unique synthetic cache files. No original documents or production service singleton. */
internal class AtomicCutHostChecks(private val test: Instrumentation, private val host: Activity,
                                  identity: String, boundaryName: String, private val firstWrite: Boolean) {
    private val boundary = PreviewWriteBoundary.valueOf(boundaryName)
    private val directory: File
    init {
        check(identity.matches(Regex("[0-9a-f-]{36}")))
        directory = File(host.cacheDir, "atomic-cut-$identity")
    }
    private fun main(action: () -> Unit) = test.runOnMainSync(action)
    private fun eventually(message: String, predicate: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 10000
        var done = false
        while (!done && SystemClock.uptimeMillis() < end) { main { done = predicate() }; if (!done) SystemClock.sleep(20) }
        check(done) { message }
    }
    private fun checkpoint(): PagedPreviewSnapshot {
        val base = "NCPREVIEW\t2\nD\tatomic-cut\tpending\t1\t0\t0\nG\tpage\tlayer\t595.0\t842.0\tBLANK\t0\nEND\n"
        val ink = "NCPREVIEW\t2\nD\tatomic-cut\t\t1\t1\t0\nG\tpage\tlayer\t595.0\t842.0\tBLANK\t0\nS\treused-ink-2\tlayer\t1\tMONOLINE\t1\t-14339513\t2.5\t3\t1.0\t0.0\t0.0\t1.0\t0.0\t0.0\nP\t1.25\t2.5\t0.125\t7\nP\t3.0\t4.0\t0.625\t9\nP\t8.0\t6.0\t1.0\t10\nEND\n"
        val operation = "reused-op-1".map { it.code.toString(16).padStart(4, '0') }.joinToString("")
        return PreviewRecoveryCodec.decode("NCRECOVERY\t1\nD\tatomic-cut\tpending\t1\t0\t0\n${base.length}\t${ink.length}\t$operation\n" + base + ink + "RECOVERY-END\n")
    }
    fun prepare(): Nothing {
        check(!directory.exists() && directory.mkdirs())
        val checkpoint = checkpoint(); val initial = PagedPreviewSnapshot.fromRoot(checkpoint.root)
        if (!firstWrite) {
            PreviewFileIO(directory, host.contentResolver).write(initial)
            File(directory, "expected-old.txt").writeText(initial.encode(), Charsets.UTF_8)
        }
        // Independent expected bytes for the external verifier; never used to repair files after death.
        File(directory, "expected-pending.txt").writeText(checkpoint.encode(), Charsets.UTF_8)
        val hold = CountDownLatch(1)
        lateinit var owner: NoteStore; lateinit var note: PagedPreviewNotebook
        var callbacks = 0
        main {
            owner = NoteStore.isolated(host, directory, observeWriteBoundary = { reached ->
                if (reached == boundary) {
                    Handler(Looper.getMainLooper()).post {
                        check(callbacks == 0 && owner.saveStates().size == 1 && note.hasPendingStroke)
                        check(owner.editorPresentation(note).phase == EditorSavePhase.SAVING)
                        val marker = JSONObject().put("pid", Process.myPid()).put("boundary", reached.name)
                            .put("callbacks", callbacks).put("phase", "SAVING").put("has_pending", true)
                            .put("first_write", firstWrite)
                        File(directory, "ready.json").writeText(marker.toString(), Charsets.UTF_8)
                    }
                    hold.await() // No timeout release or fabricated receipt. External process termination ends it.
                }
            })
            note = owner.adopt(OpenedDocument(checkpoint.root, false, checkpoint))
            check(owner.save(note.captureRecovery()) { _, _ -> callbacks++ })
        }
        hold.await()
        error("Atomic cut gate must end only with process death")
    }
    fun reopen(): String {
        check(directory.isDirectory && File(directory, "ready.json").isFile)
        val previousPid = JSONObject(File(directory, "ready.json").readText(Charsets.UTF_8)).getInt("pid")
        check(previousPid != Process.myPid())
        val checkpoint = checkpoint(); var opened: OpenedDocument? = null; var error: String? = null; var read = false
        lateinit var owner: NoteStore; lateinit var note: PagedPreviewNotebook
        var confirmedContent: PagedPreviewSnapshot? = null
        val expectedPending = boundary == PreviewWriteBoundary.VERIFIED_BEFORE_RETURN
        val expectedAbsent = firstWrite && !expectedPending
        main {
            owner = NoteStore.isolated(host, directory)
        }
        try {
            if (firstWrite) {
                var catalogReady = false
                main {
                    owner.list { entries, failure ->
                        check(failure == null)
                        check(entries.map { it.id } == if (expectedAbsent) emptyList() else listOf("atomic-cut"))
                        catalogReady = true
                    }
                }
                eventually("Original catalog did not settle") { catalogReady }
                check(!File(directory, "atomic-cut.ncp.new").exists())
            }
            main { owner.open("atomic-cut") { value, failure -> opened = value; error = failure; read = true } }
            eventually("Original reader did not finish after process death") { read }
            if (expectedAbsent) {
                check(opened == null && error == LocalReadFailure.IO_FAILURE.message)
                check(!File(directory, "atomic-cut.ncp").exists() && !File(directory, "atomic-cut.ncp.bak").exists())
                retire(owner)
                return result(previousPid, false, 0, null, true)
            }
            main {
                check(error == null); val value = checkNotNull(opened)
                check(value.persisted && (value.recovery.pending != null) == expectedPending)
                check(value.recovery.sameContent(if (expectedPending) checkpoint else PagedPreviewSnapshot.fromRoot(checkpoint.root)))
                note = owner.adopt(value)
                check(note.totalStrokeCount == 0 && note.hasPendingStroke == expectedPending)
                if (expectedPending) {
                    check(note.retryPendingStroke()); val version = note.changeVersion
                    check(version == checkpoint.version + 1 && note.totalStrokeCount == 1)
                    check(note.retryPendingStroke() && note.changeVersion == version && note.totalStrokeCount == 1)
                    check(note.pointCount(0) == 3 && note.pointX(0, 0) == 1.25 && note.pointY(0, 2) == 6.0)
                    check(owner.save(note.captureRecovery()) { _, failure -> check(failure == null) })
                }
                confirmedContent = note.captureRecovery()
            }
            eventually("Reopened original owner did not confirm completed content") { owner.saveStates().isEmpty() }
            val actual = PreviewFileIO(directory, host.contentResolver).readRecovery("atomic-cut")
            check(actual.sameContent(checkNotNull(confirmedContent)))
            check(actual.pending == null && actual.root.scene.objectCount == if (expectedPending) 1 else 0)
            check(!File(directory, "atomic-cut.ncp.new").exists())
            retire(owner)
            return result(previousPid, expectedPending, actual.root.scene.objectCount, actual.version, false)
        } finally {
            // Keep failures for diagnosis. Only successful real retirement authorizes fixture cleanup.
            var retired = false; main { retired = owner.ownerRetirementStatus().phase == StoreOwnerPhase.RETIRED }
            if (retired) check(directory.deleteRecursively())
        }
    }

    private fun retire(owner: NoteStore) {
        main { check(owner.beginOwnerRetirement() == StoreRetirementRequest.STARTED) }
        eventually("Reopened original worker did not retire") { owner.ownerRetirementStatus().phase == StoreOwnerPhase.RETIRED }
    }

    private fun result(previousPid: Int, pending: Boolean, strokes: Int, revision: Long?, absent: Boolean): String =
        JSONObject().put("previous_pid", previousPid).put("pid", Process.myPid()).put("boundary", boundary.name)
            .put("recovered_pending", pending).put("committed_strokes", strokes)
            .put("revision", revision ?: JSONObject.NULL).put("document_absent", absent)
            .put("temporary_file_absent", true).put("original_owner_retired", true).toString()
}
