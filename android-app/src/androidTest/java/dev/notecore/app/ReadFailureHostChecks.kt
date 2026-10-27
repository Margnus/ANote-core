package dev.notecore.app

import android.app.Activity
import android.app.Instrumentation
import android.os.Looper
import android.os.SystemClock
import dev.notecore.document.*
import dev.notecore.canvas.prototype.*
import java.io.File
import java.io.RandomAccessFile

/** Real original Store/worker; unique cache fixtures only. Unknown failure is explicitly injected. */
internal class ReadFailureHostChecks(private val test: Instrumentation, private val host: Activity) {
    private fun main(action: () -> Unit) = test.runOnMainSync(action)
    private fun await(timeoutMillis: Long = 12000, predicate: () -> Boolean) {
        val end = SystemClock.uptimeMillis()+timeoutMillis
        var done = false
        while (!done && SystemClock.uptimeMillis()<end) { main { done=predicate() }; if (!done) SystemClock.sleep(20) }
        check(done) { "Read callback did not arrive" }
    }
    private fun longNumericCheckpoint(directory: File) {
        val id = "long-capacity"; val layer = LayerId("ink")
        val samples = StrokeSamples.copyOf(DoubleArray(20000) { -1.2345678901234567e-100 },
            DoubleArray(20000) { -1.2345678901234567e-100 }, FloatArray(20000) { 1.2345679e-20f }, LongArray(20000) { Long.MAX_VALUE })
        val brush = BrushSpec(BrushKind.MONOLINE, 1, 2.0, -1)
        val objects = (0 until 9).map { StrokeObject(ObjectId("old-$it"), 1, layer, brush, samples) }
        val page = PageDescriptor(PageId("page"), layer, 595.0, 842.0, contentRevision = 1)
        val root = DocumentRoot.compose("long digits", SceneSnapshot.restored(SceneId(id), 1, objects), listOf(page))
        lateinit var checkpoint: PagedPreviewSnapshot
        lateinit var pending: DocumentCommand.Canvas
        lateinit var producer: PagedPreviewNotebook
        lateinit var store: NoteStore
        var saved = false; var armed = false; var probing = false
        main {
            store = NoteStore.isolated(host, directory)
            producer = PagedPreviewNotebook(root, "producer", ownerCheck = {
                check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
                if (armed && !probing) {
                    probing = true; val completed = try { producer.hasPendingStroke } finally { probing = false }
                    if (completed) { armed = false; error("Synthetic commit owner unavailable") }
                }
            })
            store.attachActive(producer)
            val xs=DoubleArray(1000) { -1.2345678901234567e-100 }; val ys=xs.copyOf()
            val pressures=FloatArray(1000) { 1.2345679e-20f }; val times=LongArray(1000) { Long.MAX_VALUE }
            repeat(19) { index ->
                val result=producer.offerRealBatch(if(index==0) PointerPhase.DOWN else PointerPhase.MOVE, Long.MAX_VALUE, xs, ys, pressures, times)
                check(result.acceptedSamples==1000 && result.disposition==PreviewInputDisposition.ACCEPTED)
            }
            armed=true
            try { producer.offerRealBatch(PointerPhase.UP,Long.MAX_VALUE,xs,ys,pressures,times); error("Expected owner fault") }
            catch (failure: IllegalStateException) { check(!armed) }
            checkpoint=producer.captureRecovery(); pending=checkNotNull(checkpoint.pending)
        }
        try {
            main { check(store.save(checkpoint) { _, error -> check(error == null); saved = true }) }
            await(60000) { saved && store.saveStates().isEmpty() }
            val file = File(directory, "$id.ncp")
            check(file.length() > 16_000_000 && file.length() <= PagedPreviewCodec.MAX_UTF8_BYTES)
            main { check(store.releaseActive(producer)); store.stopTestBackend(); store = NoteStore.isolated(host, directory) }
            saved = false
            lateinit var owner: PagedPreviewNotebook
            main { store.open(id) { value, error ->
                check(error == null); owner = store.adopt(checkNotNull(value))
                check(owner.hasPendingStroke && owner.totalStrokeCount == 9)
                check(owner.retryPendingStroke()); val completed = owner.snapshot()
                check(owner.retryPendingStroke() && owner.snapshot() === completed && owner.totalStrokeCount == 10)
                check(owner.rename("saved long digits"))
                check(store.save(owner.capture()) { _, failure -> check(failure == null); saved = true })
            } }
            await(60000) { saved && store.saveStates().isEmpty() }
            main { check(store.releaseActive(owner)); store.stopTestBackend(); store = NoteStore.isolated(host, directory) }
            var opened = false
            main { store.open(id) { value, error ->
                check(error == null); val note = store.adopt(checkNotNull(value))
                check(!note.hasPendingStroke && note.totalStrokeCount == 10 && note.title == "saved long digits")
                check(note.snapshot().scene.objects().sumOf { it.samples.size } == 200000)
                check(note.snapshot().scene.objects().all { it.samples == samples })
                check(store.releaseActive(note)); opened = true
            } }
            await(60000) { opened }
            check(file.length() > 16_000_000 && file.length() <= PagedPreviewCodec.MAX_UTF8_BYTES)
            check(root.scene.objectCount == 9 && checkpoint.pending === pending)
        } finally { main { store.stopTestBackend() } }
    }
    fun verify() {
        val directory=File(host.cacheDir,"read-failure-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        longNumericCheckpoint(File(directory, "long-numeric"))
        val id="read-failures"; val file=File(directory,"$id.ncp")
        val valid="NCPREVIEW\t2\nD\t$id\ttitle\t1\t0\t0\nG\tpage\tlayer\t595.0\t842.0\tBLANK\t0\nEND\n"
        lateinit var store: NoteStore
        main { store=NoteStore.isolated(host,directory) }
        fun refuse(message: String) {
            var done=false
            main { store.open(id) { value,error ->
                check(Looper.myLooper()==Looper.getMainLooper());check(value==null && error==message) { "Wrong refusal: $error" }
                check(store.activeDocumentId==null);done=true
            } }
            await { done }
        }
        try {
            for ((bytes,reason) in listOf("bad format".toByteArray() to LocalReadFailure.INVALID_FORMAT,
                valid.replace(id,"other-note").toByteArray() to LocalReadFailure.WRONG_DOCUMENT,
                byteArrayOf(0xc3.toByte(),0x28) to LocalReadFailure.INVALID_UTF8,
                ByteArray(PagedPreviewCodec.MAX_CHARACTERS+1) { 'x'.code.toByte() } to LocalReadFailure.CHARACTER_CAPACITY)) {
                file.writeBytes(bytes);refuse(reason.message);check(file.readBytes().contentEquals(bytes))
            }
            // Valid canonical doubles can degenerate in the original Float Path/Matrix adapter.
            val ink="NCPREVIEW\t2\nD\t$id\ttitle\t1\t1\t0\nG\tpage\tlayer\t595.0\t842.0\tBLANK\t0\nS\tink\tlayer\t1\tMONOLINE\t1\t-1\t2.5\t1\t1.0\t0.0\t0.0\t1.0\t0.0\t0.0\nP\t1.0\t2.0\t0.5\t1\nEND\n"
            val degenerate = listOf(ink.replace("\t2.5\t1\t", "\t1.0E-50\t1\t"),
                ink.replace("\t1.0\t0.0\t0.0\t1.0\t0.0\t0.0\n", "\t1.0\t1.0\t1.0\t1.00000001\t0.0\t0.0\n"))
            for (encoded in degenerate) {
                val canonical = PagedPreviewCodec.decode(encoded)
                check(PagedPreviewCodec.encode(canonical) == encoded)
                main {
                    var ownerChecks = 0
                    var refused = false
                    try { PagedPreviewNotebook(canonical, "direct", ownerCheck={ ownerChecks++ }) }
                    catch (_: IllegalArgumentException) { refused = true }
                    check(refused && ownerChecks == 1)
                }
                file.writeText(encoded, Charsets.UTF_8)
                refuse(LocalReadFailure.UNSUPPORTED_CONTENT.message)
                check(file.readText(Charsets.UTF_8) == encoded)
            }
            RandomAccessFile(file,"rw").use { it.setLength(32L*1024*1024+1) }
            val largeBefore=file.readBytes()
            refuse(LocalReadFailure.BYTE_CAPACITY.message);check(file.readBytes().contentEquals(largeBefore))
            check(file.delete());refuse(LocalReadFailure.IO_FAILURE.message)
            main { store.stopTestBackend();store=NoteStore.isolated(host,directory,beforeOpenRead={ error("injected non-IO read failure") }) }
            file.writeText(valid,Charsets.UTF_8)
            refuse("读取结果暂无法确认，请稍后重试");check(file.readText(Charsets.UTF_8)==valid)
            main { store.stopTestBackend();store=NoteStore.isolated(host,directory) }
            // Retained input skips disk IO but still refuses unsupported content before adopt.
            val blocked=File(directory,"blocked").apply { writeText("not a directory",Charsets.UTF_8) }
            main { store.stopTestBackend();store=NoteStore.isolated(host,blocked) }
            val encoded="NCPREVIEW\t2\nD\t$id\ttitle\t1\t1\t0\nG\tpage\tlayer\t595.0\t842.0\tBLANK\t0\nS\tink\tlayer\t1\tPRESSURE\t1\t-1\t2.5\t1\t1.0\t0.0\t0.0\t1.0\t0.0\t0.0\nP\t1.0\t2.0\t0.5\t1\nEND\n"
            val retained=PagedPreviewSnapshot.fromRoot(PagedPreviewCodec.decode(encoded))
            var failed=false
            main { check(store.save(retained) { _,error -> check(error!=null);failed=true }) }
            await { failed && store.saveStates().single().retryRequired }
            refuse(LocalReadFailure.UNSUPPORTED_CONTENT.message)
            main { check(store.saveStates().single().snapshot === retained) }
            check(blocked.readText(Charsets.UTF_8)=="not a directory")
            check(blocked.delete() && blocked.mkdirs())
            main { check(store.retryRetainedWrites()) }
            await { store.saveStates().isEmpty() }
            check(File(blocked,"$id.ncp").readText(Charsets.UTF_8)==retained.encode())
            main { store.stopTestBackend();store=NoteStore.isolated(host,directory) }
            var opened=false
            main { store.open(id) { value,error -> check(error==null);val note=store.adopt(checkNotNull(value));check(note.totalStrokeCount==0);check(store.releaseActive(note));opened=true } }
            await { opened };check(file.readText(Charsets.UTF_8)==valid)
        } finally { main { store.stopTestBackend() };directory.deleteRecursively() }
    }
}
