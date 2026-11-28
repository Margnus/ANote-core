package dev.notecore.app

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Debug
import android.os.SystemClock
import android.view.View
import dev.notecore.canvas.prototype.*
import dev.notecore.document.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Measures the original preview consumers without widening their admission or changing user notes.
 * The prefix is deliberately NOT S2 acceptance. CPU draw and sampled PSS are not frame/peak metrics.
 */
internal class DenseAdmissionHostChecks(private val test: Instrumentation, private val host: Activity) {
    private val fixture = File(host.cacheDir, "dense-admission-S2.bin")
    private val report = File(host.cacheDir, "dense-admission.jsonl")
    private val expectedSha = "22eea352e0ded47f34cabc0c8b543de0126a658377a6ea286cf60130a17c6725"
    private val timings = linkedMapOf<String, MutableList<Long>>()
    private fun main(action: () -> Unit) = test.runOnMainSync(action)
    private fun eventually(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 30000
        while (SystemClock.uptimeMillis() < deadline) {
            var done = false; main { done = condition() }
            if (done) return
            SystemClock.sleep(20)
        }
        error(message)
    }
    private fun record(kind: String, vararg values: Pair<String, Any>) {
        val row = JSONObject().put("kind", kind).put("uptimeMs", SystemClock.uptimeMillis())
        values.forEach { row.put(it.first, it.second) }
        report.appendText(row.toString() + "\n")
    }
    private fun <T> timed(name: String, action: () -> T): T {
        val start = System.nanoTime()
        val result = action()
        timings.getOrPut(name) { mutableListOf() }.add(System.nanoTime() - start)
        return result
    }
    private fun memory(label: String, note: PagedPreviewNotebook? = null, store: NoteStore? = null) {
        val info = Debug.MemoryInfo(); Debug.getMemoryInfo(info)
        val runtime = Runtime.getRuntime()
        val values = mutableListOf<Pair<String, Any>>(
            "stage" to label, "javaUsedBytes" to runtime.totalMemory() - runtime.freeMemory(),
            "javaHeapBytes" to runtime.totalMemory(), "nativeAllocatedBytes" to Debug.getNativeHeapAllocatedSize(),
            "totalPssKiB" to info.totalPss, "privateDirtyKiB" to info.totalPrivateDirty,
            "runtimeStats" to JSONObject(Debug.getRuntimeStats()))
        note?.let {
            val s = it.authorityStatus()
            values += listOf("liveCharge" to s.liveByteCharge, "journalCharge" to s.journalByteCharge,
                "historyCharge" to s.historyByteCharge, "identityCharge" to s.identityByteCharge,
                "inputCharge" to it.inputReservedByteCharge, "objects" to s.objectCount,
                "operations" to s.operationCount, "undo" to s.undoCount, "redo" to s.redoCount)
        }
        store?.let { values += listOf("nativeFrameCharge" to it.inkChargedBytes,
            "nativePublications" to it.inkFramePublications, "nativeOwnerRetained" to it.inkOwnerRetained) }
        record("memory-sample-not-peak", *values.toTypedArray())
    }
    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(65536)
            while (true) { val count = stream.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    private fun readRoot(count: Int): DocumentRoot {
        val layer = LayerId("ink")
        val objects = DataInputStream(fixture.inputStream().buffered()).use { input ->
            val header = ByteArray(16).also(input::readFully)
            val fields = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            check(header.copyOfRange(0, 8).decodeToString() == "NCSCALE1")
            check(fields.getInt(8) == 10000 && fields.getInt(12) == 100)
            val record = ByteArray(2800)
            val data = ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN)
            List(count) { id ->
                input.readFully(record)
                val xs = DoubleArray(100) { data.getDouble(it * 28) }
                val ys = DoubleArray(100) { data.getDouble(it * 28 + 8) }
                val pressures = FloatArray(100) { data.getFloat(it * 28 + 16) }
                val times = LongArray(100) { data.getLong(it * 28 + 20) }
                // Original renderer experiment alternates all brushes. This explicit preview profile
                // keeps identical sample bytes but only the already-supported MONOLINE algorithm.
                StrokeObject(ObjectId("s2-$id"), 1, layer, BrushSpec(BrushKind.MONOLINE, 1, 4.0, 0xff395284.toInt()),
                    StrokeSamples.copyOf(xs, ys, pressures, times))
            }.also { if (count == 10000) check(input.read() == -1) }
        }
        return DocumentRoot.compose("Dense admission probe", SceneSnapshot.restored(SceneId("dense-admission-$count"), 1, objects),
            listOf(PageDescriptor(PageId("page"), layer, 595.0, 2400.0, contentRevision = 1)))
    }
    private fun fullAdmission() {
        memory("before-full-root")
        val root = timed("full-canonical-read") { readRoot(10000) }
        val unique = java.util.IdentityHashMap<StrokeSamples, Boolean>()
        var pointBytes = 0L
        root.scene.forEachObject { unique[it.samples] = true; pointBytes += it.samples.size * 28L }
        check(unique.size == 10000 && pointBytes == 28000000L)
        memory("full-canonical-root")
        val open = timed("full-original-open-refusal") { PagedPreviewNotebook.open(root, "full-refusal") }
        check(open.notebook == null && open.failure == PreviewOpenFailure.INVALID_CONTENT)
        check(root.scene.objectCount == 10000 && root.revision == 1L)
        record("full-admission", "strokes" to 10000, "points" to 1000000, "samplePayloadBytes" to pointBytes,
            "uniqueSampleContainers" to unique.size, "primitiveArrays" to unique.size * 4, "failure" to open.failure.toString(),
            "maxObjects" to PagedPreviewCodec.MAX_OBJECTS, "maxPoints" to PagedPreviewCodec.MAX_POINTS,
            "maxStrokePoints" to PagedPreviewCodec.MAX_STROKE_POINTS, "defaultLiveBytes" to DocumentBudget().liveBytes,
            "ownerCreated" to false, "nativeFrames" to 0, "brushProfile" to "MONOLINE-v1")
        // Isolate the independent point gate while keeping object count below its limit.
        val pointLimitRoot = readRoot(2001)
        check(PagedPreviewNotebook.open(pointLimitRoot, "points-refusal").notebook == null)
        check(pointLimitRoot.scene.objectCount < PagedPreviewCodec.MAX_OBJECTS)
        record("point-gate", "strokes" to 2001, "points" to 200100, "ownerCreated" to false)
    }
    private fun sameContent(a: DocumentRoot, b: DocumentRoot) {
        check(a.title == b.title && a.pages() == b.pages() && a.revision == b.revision && a.id == b.id)
        check(a.scene.objects() == b.scene.objects()) // Includes every immutable real sample, style and transform.
    }
    fun verify(): String {
        report.writeText("")
        check(fixture.length() == 28000016L && digest(fixture) == expectedSha)
        record("input", "sha256" to expectedSha, "scope" to "synthetic ink; no mixed resources or physical stylus",
            "sdk" to android.os.Build.VERSION.SDK_INT, "fingerprint" to android.os.Build.FINGERPRINT)
        fullAdmission()
        // GC request separates measurement stages, but proves neither a live-set size nor a peak.
        System.gc(); SystemClock.sleep(100)
        memory("after-full-scope-gc-request")
        lateinit var store: NoteStore
        lateinit var note: PagedPreviewNotebook
        lateinit var view: InkCanvasView
        lateinit var original: DocumentRoot
        lateinit var saved: PagedPreviewSnapshot
        lateinit var bitmap: Bitmap
        val directory = File(host.cacheDir, "dense-host-${java.util.UUID.randomUUID()}")
        val writeHeld = CountDownLatch(1); val releaseWrite = CountDownLatch(1)
        val savedAck = CountDownLatch(1)
        val nativeComputations = java.util.concurrent.atomic.AtomicInteger()
        val holdRender = java.util.concurrent.atomic.AtomicBoolean(false)
        val renderHeld = CountDownLatch(1); val releaseRender = CountDownLatch(1)
        var saveFailure: String? = null
        var surfaceInstalledAt = 0L
        main {
            original = timed("prefix-canonical-read") { readRoot(1900) }
            store = NoteStore.isolated(host, directory, beforeInkCompute = {
                nativeComputations.incrementAndGet()
                if (holdRender.compareAndSet(true, false)) {
                    renderHeld.countDown(); check(releaseRender.await(30, TimeUnit.SECONDS))
                }
            }, observeWriteBoundary = {
                if (it == PreviewWriteBoundary.PREFIX_WRITTEN_BEFORE_SYNC) {
                    writeHeld.countDown(); check(releaseWrite.await(30, TimeUnit.SECONDS)) { "Write observation timed out" }
                }
            })
            note = timed("original-store-adopt") { store.adopt(OpenedDocument(original, false)) }
            check(note.totalStrokeCount == 1900)
            timed("cold-page-projection") { check(note.strokeCount == 1900) }
            original.scene.forEachObject { check(note.snapshot().scene.find(it.id) === it) }
            repeat(30) {
                timed("authority-status") { note.authorityStatus() }
                timed("toolbar-history-reads") { note.canUndo; note.canRedo }
                timed("warm-projection") { check(note.strokeCount == 1900) }
            }
            view = InkCanvasView(host, note, {}, { error(it) })
            surfaceInstalledAt = System.nanoTime()
            host.setContentView(InkSurfaceHost(view, store), android.widget.FrameLayout.LayoutParams(1024, 768))
        }
        test.waitForIdleSync()
        try { eventually("Dense staged frame did not publish") { store.inkFramePublications == 1 } }
        catch (failure: Throwable) {
            main { memory("native-publication-failure", note, store)
                record("native-compute-count-at-failure", "count" to nativeComputations.get()) }
            throw failure
        }
        record("first-complete-native-frame", "hostInstallToObservedPublicationNs" to System.nanoTime() - surfaceInstalledAt,
            "slices" to nativeComputations.get())
        main { memory("complete-dense-native-before-software-oracle", note, store) }
        var sampleX = 0; var sampleY = 0; var screenX = 0; var screenY = 0
        main {
            val point = view.viewport.toView(12.5, 103.25)
            sampleX = point.x.toInt(); sampleY = point.y.toInt()
            val location = IntArray(2); view.getLocationOnScreen(location)
            screenX = location[0] + sampleX; screenY = location[1] + sampleY
        }
        SystemClock.sleep(100)
        val shot = checkNotNull(test.uiAutomation.takeScreenshot())
        val nativePixel = try { shot.getPixel(screenX, screenY) } finally { shot.recycle() }
        main {
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            // Force the original cold path cache; draw uses a software destination and thus full fallback.
            view.refreshPage()
            timed("cold-original-path-draw") { view.draw(Canvas(bitmap)) }
            repeat(10) { timed("warm-original-path-draw") { view.draw(Canvas(bitmap)) } }
            memory("prefix-projection-path-native", note, store)
            val refused = NoteStore::class.java.getDeclaredField("inkAdmissionRejected").apply { isAccessible = true }.getBoolean(store)
            check(!refused && nativeComputations.get() > 1 && store.inkFramePublications == 1 && store.inkChargedBytes > 0L)
            val fallbackPixel = bitmap.getPixel(sampleX, sampleY)
            check(nativePixel != android.graphics.Color.WHITE)
            for (shift in listOf(0, 8, 16, 24)) check(kotlin.math.abs((nativePixel ushr shift and 255) -
                (fallbackPixel ushr shift and 255)) <= 8) { "Dense compositor/fallback pixel differs" }
            record("original-native-admission", "rejected" to refused, "workerComputations" to nativeComputations.get(),
                "selectedPageStrokes" to note.strokeCount, "nativeFrameCharge" to store.inkChargedBytes,
                "nativePixel" to Integer.toHexString(nativePixel), "fallbackPixel" to Integer.toHexString(fallbackPixel),
                "backend" to "staged original executor/pool; whole atlas publication")
            val originalPixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(originalPixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            repeat(12) { iteration ->
                val root = note.snapshot()
                timed("down-admission") { check(note.beginStroke(100.0, 100.0 + iteration, .5f, 0)) }
                if (iteration == 0) memory("active-input", note, store)
                timed("up-commit") { check(note.endStroke(110.0, 110.0 + iteration, .5f, 4167)) }
                timed("after-commit-projection") { check(note.strokeCount == 1901) }
                timed("undo") { check(note.undo()) }
                check(note.snapshot().scene.objects() == root.scene.objects())
                timed("redo") { check(note.redo()) }
                check(note.totalStrokeCount == 1901)
                timed("second-undo") { check(note.undo()) }
            }
            // No accepted stroke disappears: all undone samples stay owned by history/journal.
            check(note.authorityStatus().operationCount == 48)
            check(note.snapshot().scene.objects() == original.scene.objects())
            timed("after-history-cold-path-draw") { view.draw(Canvas(bitmap)) }
            val historyPixels = IntArray(originalPixels.size)
            bitmap.getPixels(historyPixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            check(originalPixels.contentEquals(historyPixels))
            // Keep the final accepted stroke in the saved content, in addition to the undo oracle.
            check(note.redo()); check(note.strokeCount == 1901)
            check(note.stroke(1900).samples.size == 2)
            check(note.stroke(1900).samples.x(0) == 100.0 && note.stroke(1900).samples.y(1) == 121.0)
            memory("history-and-projection", note, store)
            saved = timed("save-capture") { note.capture() }
            record("save-responsibility", "retainedCharge" to saved.retainedByteCharge,
                "workReservationCharge" to saved.encodingWorkByteCharge, "rootSharedWithAuthority" to (saved.root === note.snapshot()))
            holdRender.set(true); view.syncPresentation()
        }
        val saveStart = System.nanoTime()
        try {
            check(renderHeld.await(30, TimeUnit.SECONDS)) { "Replacement renderer did not begin" }
            main { check(store.save(saved) { revision, failure ->
                check(revision == saved.version); saveFailure = failure; savedAck.countDown()
            }) }
            releaseRender.countDown()
            check(writeHeld.await(30, TimeUnit.SECONDS)) { "Writer did not reach the actual file boundary" }
            main {
                check(store.inkFramePublications == 1 && store.inkChargedBytes > 0L)
                memory("write-held-with-staged-atlas-before-sync", note, store)
            }
        } finally { releaseRender.countDown(); releaseWrite.countDown() }
        check(savedAck.await(30, TimeUnit.SECONDS)) { "No durable save callback" }
        check(saveFailure == null) { "Save failed: $saveFailure" }
        record("save-ack", "elapsedNsIncludingObservationHold" to System.nanoTime() - saveStart,
            "fileBytes" to File(directory, "${saved.documentId}.ncp").length(),
            "sha256" to digest(File(directory, "${saved.documentId}.ncp")))
        eventually("Saved revision did not publish a complete replacement frame") { store.inkFramePublications == 2 }
        main { memory("saved-revision-native-frame", note, store) }
        main {
            store.detachInk(view); check(store.releaseActive(note)); host.setContentView(View(host))
            bitmap.recycle()
        }
        val deadline = SystemClock.uptimeMillis() + 15000
        var drained = false
        while (!drained && SystemClock.uptimeMillis() < deadline) {
            main { drained = !store.inkOwnerRetained && store.inkChargedBytes == 0L }
            if (!drained) SystemClock.sleep(20)
        }
        check(drained) { "Original renderer failed to drain" }
        main { check(store.inkReleaseCallbacks > 0); record("native-release", "callbacks" to store.inkReleaseCallbacks,
            "validFenceObserved" to store.inkValidFenceObserved, "chargeAfterDrain" to store.inkChargedBytes) }
        lateinit var reopenedStore: NoteStore
        lateinit var reopened: PagedPreviewNotebook
        val opened = CountDownLatch(1)
        val openStart = System.nanoTime()
        main {
            memory("retired-old-owner", note, store); store.stopTestBackend()
            reopenedStore = NoteStore.isolated(host, directory)
            reopenedStore.open(saved.documentId) { value, failure ->
                check(failure == null && value != null) { "Read failed: $failure" }
                reopened = reopenedStore.adopt(checkNotNull(value)); opened.countDown()
            }
        }
        check(opened.await(30, TimeUnit.SECONDS)) { "No reopen callback" }
        record("new-owner-reopen", "elapsedNs" to System.nanoTime() - openStart)
        main {
            sameContent(saved.root, reopened.snapshot())
            check(reopened.strokeCount == 1901 && reopened.stroke(1900).samples.size == 2)
            memory("new-owner-with-test-oracle-retained", reopened, reopenedStore)
            check(reopenedStore.releaseActive(reopened)); reopenedStore.stopTestBackend()
        }
        for ((name, values) in timings) {
            val sorted = values.sorted()
            fun percentile(p: Double) = sorted[(kotlin.math.ceil(sorted.size * p).toInt() - 1).coerceAtLeast(0)]
            record("cpu-wall-timing", "name" to name, "count" to values.size,
                "firstNs" to values.first(), "p50Ns" to percentile(.5), "p95Ns" to percentile(.95),
                "maxNs" to sorted.last(), "rawNs" to JSONArray(values))
        }
        record("passed", "prefixStrokes" to 1900, "prefixPoints" to 190000,
            "savedStrokes" to 1901, "savedPoints" to 190002, "acceptedStrokePreserved" to true,
            "fullS2Accepted" to false, "nativeFramePeakMeasured" to false, "stagedNativeFrames" to 2,
            "scope" to "original consumers; synthetic API input; debug API35; held-write latency is not a benchmark")
        return report.name
    }
}
