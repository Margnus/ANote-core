package dev.notecore.app

import android.app.Activity
import android.app.Instrumentation
import android.os.Looper
import android.os.SystemClock
import dev.notecore.document.*
import java.io.File
import java.io.RandomAccessFile

/** Real original Store/worker; unique cache fixtures only. Unknown failure is explicitly injected. */
internal class ReadFailureHostChecks(private val test: Instrumentation, private val host: Activity) {
    private fun main(action: () -> Unit) = test.runOnMainSync(action)
    private fun await(predicate: () -> Boolean) {
        val end = SystemClock.uptimeMillis()+12000
        var done = false
        while (!done && SystemClock.uptimeMillis()<end) { main { done=predicate() }; if (!done) SystemClock.sleep(20) }
        check(done) { "Read callback did not arrive" }
    }
    fun verify() {
        val directory=File(host.cacheDir,"read-failure-${java.util.UUID.randomUUID()}").apply { mkdirs() }
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
