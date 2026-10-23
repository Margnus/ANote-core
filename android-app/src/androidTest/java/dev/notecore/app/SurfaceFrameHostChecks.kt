package dev.notecore.app

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import dev.notecore.document.PagedPreviewNotebook
import dev.notecore.document.PaperKind
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Uses the exact app host/view/NoteStore and actual compositor, never a separate Surface-only demo. */
internal class SurfaceFrameHostChecks(private val test: Instrumentation, private val host: Activity) {
    private lateinit var store: NoteStore
    private lateinit var note: PagedPreviewNotebook
    private lateinit var view: InkCanvasView
    private lateinit var directory: File
    private fun main(action: () -> Unit) = test.runOnMainSync(action)
    private fun eventually(message: String, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis()+12000
        var done = false
        while (!done && SystemClock.uptimeMillis()<until) {
            main { done = condition() }
            if (!done) SystemClock.sleep(20)
        }
        check(done) { message }
    }
    private fun setup(before: () -> Unit = {}, afterRelease: () -> Unit = {},
        afterSubmit: () -> Unit = {}, maySettleRelease: () -> Boolean = { true }) {
        main {
            directory = File(host.cacheDir,"surface-host-${java.util.UUID.randomUUID()}")
            store = NoteStore.isolated(host,directory,before,afterRelease,afterSubmit,maySettleRelease)
            note = store.create(); check(note.setPaper(200.0,200.0,PaperKind.BLANK))
            for (color in listOf(0x80ff0000.toInt(),0x800000ff.toInt())) {
                note.selectPen(color,10.0)
                check(note.beginStroke(50.0,40.0,1f,0)); check(note.endStroke(50.0,40.0,1f,1))
            }
            view = InkCanvasView(host,note,{}, { error(it) })
            host.setContentView(InkSurfaceHost(view,store))
        }
        test.waitForIdleSync()
    }
    private fun point(): Pair<Int,Int> {
        var result = 0 to 0
        main {
            val location = IntArray(2); view.getLocationOnScreen(location)
            val p = view.viewport.toView(50.0,40.0)
            result = (location[0]+p.x.toInt()) to (location[1]+p.y.toInt())
        }
        return result
    }
    private fun screenPixel(): Int {
        val p = point()
        val shot = checkNotNull(test.uiAutomation.takeScreenshot())
        return try { shot.getPixel(p.first,p.second) } finally { shot.recycle() }
    }
    private fun close() {
        main { store.detachInk(view); store.releaseActive(note); host.setContentView(View(host)) }
        eventually("Detached compositor reader did not return") { !store.inkOwnerRetained && store.inkChargedBytes == 0L }
        main { store.stopTestBackend() }
    }
    fun verify(): String {
        check(android.os.Build.VERSION.SDK_INT >= 33)
        setup()
        eventually("Original app hierarchy never scheduled a complete SurfaceControl frame") { store.inkFramePublications > 0 }
        SystemClock.sleep(150) // screenshot observation, not a resource release rule
        val original = screenPixel()
        check(kotlin.math.abs(Color.red(original)-127)<=4 && kotlin.math.abs(Color.green(original)-63)<=4 &&
            kotlin.math.abs(Color.blue(original)-191)<=4) { "Compositor pixel/alpha/z-order mismatch: ${Integer.toHexString(original)}" }
        var fallback = 0
        main {
            val bitmap = Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap)) // Actual CPU destination keeps the complete canonical Path.
            val p = view.viewport.toView(50.0,40.0); fallback = bitmap.getPixel(p.x.toInt(),p.y.toInt()); bitmap.recycle()
        }
        check(kotlin.math.abs(Color.red(original)-Color.red(fallback))<=4 && kotlin.math.abs(Color.blue(original)-Color.blue(fallback))<=4)
        var publication = 0
        main {
            publication = store.inkFramePublications
            val p = view.viewport.toView(50.0,40.0)
            view.penColor = 0x8000ff00.toInt(); view.penWidth = 10.0
            val now = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,p.x.toFloat(),p.y.toFloat(),0)
            try { check(view.dispatchTouchEvent(down)) } finally { down.recycle() }
        }
        SystemClock.sleep(100)
        val active = screenPixel()
        check(Color.green(active)>Color.green(original)+50) { "Active ink was not above the static surface" }
        main {
            val p = view.viewport.toView(50.0,40.0); val now = SystemClock.uptimeMillis()
            val up = MotionEvent.obtain(now-100,now,MotionEvent.ACTION_UP,p.x.toFloat(),p.y.toFloat(),0)
            try { check(view.dispatchTouchEvent(up)) } finally { up.recycle() }
        }
        eventually("Commit did not replace fallback with a complete new frame") { store.inkFramePublications>publication }
        SystemClock.sleep(100)
        val committed = screenPixel()
        check(kotlin.math.abs(Color.green(active)-Color.green(committed))<=4 &&
            kotlin.math.abs(Color.blue(active)-Color.blue(committed))<=4) { "Active/static replacement changed alpha" }
        main { check(note.insertPage()); view.refreshPage() }
        eventually("Empty next page did not schedule") { store.inkFramePublications>publication+1 }
        SystemClock.sleep(100)
        check(Color.red(screenPixel())>245 && Color.green(screenPixel())>245) { "Old page leaked into new page" }
        main { view.zoomViewport(1.5) }
        eventually("Zoom did not preserve same-owner release progress") { store.inkFramePublications>publication+2 }
        val beforeResize = store.inkFramePublications
        main {
            view.surfaceHost!!.layoutParams = android.widget.FrameLayout.LayoutParams(view.width-40,view.height-60)
            view.surfaceHost!!.requestLayout()
        }
        eventually("Resize did not replace the old surface identity/size") { store.inkFramePublications>beforeResize }
        val callbacks = store.inkReleaseCallbacks
        val validFence = store.inkValidFenceObserved
        var savedFirst = false
        var encoded = ""
        var document = ""
        main { encoded = note.capture().encode(); document = note.documentId
            check(store.save(note.capture()) { _, error -> check(error==null); savedFirst = true }) }
        eventually("Full page snapshot did not save") { savedFirst }
        var savedAfterWeakClear = false
        main {
            val field = NoteStore::class.java.getDeclaredField("activeSavedRoot").apply { isAccessible = true }
            val savedIdentity = field.get(store) as java.lang.ref.WeakReference<*>
            check(savedIdentity.get() === note.snapshot())
            savedIdentity.clear() // deterministic GC-equivalent loss of an optimization, never content
            check(store.save(note.capture()) { _, error -> check(error == null); savedAfterWeakClear = true })
            check(!savedAfterWeakClear && store.saveStates().single().inFlight)
        }
        eventually("Cleared saved identity did not complete a real queued write") { savedAfterWeakClear }
        var exported = false
        lateinit var pickerStore: NoteStore
        val exportFile = java.io.File(directory, "picker-output.ncp")
        main {
            val snapshot = note.capture()
            val tooSmall = NoteStore.isolated(host, java.io.File(directory, "small-picker"),
                exportPickerByteLimit = snapshot.retainedByteCharge - 1)
            check(tooSmall.beginExportPicker(snapshot).disposition == ExportPickerDisposition.CAPACITY)
            check(tooSmall.exportReservedByteCharge == 0L); tooSmall.stopTestBackend()
            pickerStore = NoteStore.isolated(host, java.io.File(directory, "exact-picker"),
                exportPickerByteLimit = snapshot.retainedByteCharge)
            val first = pickerStore.beginExportPicker(snapshot)
            check(first.disposition == ExportPickerDisposition.READY && first.requestCode == 1000)
            val later = PagedPreviewNotebook(snapshot.root, "later-export").apply { check(rename("later")) }.capture()
            check(pickerStore.beginExportPicker(later).disposition == ExportPickerDisposition.BUSY)
            check(pickerStore.exportReservedByteCharge == snapshot.retainedByteCharge)
            check(pickerStore.takeExportPicker(1001) == null)
            check(pickerStore.takeExportPicker(1000) === snapshot)
            check(pickerStore.takeExportPicker(1000) == null && pickerStore.exportReservedByteCharge == 0L)
            check(pickerStore.beginExportPicker(snapshot).requestCode == 1001)
            check(pickerStore.takeExportPicker(1000) == null && pickerStore.exportReservedByteCharge > 0)
            // Exercise the original Activity's saved-state/cancellation callbacks with isolated Store content.
            val storeField = host.javaClass.getDeclaredField("store").apply { isAccessible = true }
            val codeField = host.javaClass.getDeclaredField("exportRequestCode").apply { isAccessible = true }
            val sessionField = host.javaClass.getDeclaredField("exportPickerSession").apply { isAccessible = true }
            val previousStore = storeField.get(host); val previousCode = codeField.get(host); val previousSession = sessionField.get(host)
            try {
                storeField.set(host, pickerStore); codeField.set(host, 1001); sessionField.set(host, pickerStore.exportPickerSession)
                val savedState = android.os.Bundle()
                host.javaClass.getDeclaredMethod("onSaveInstanceState", android.os.Bundle::class.java).apply { isAccessible = true }.invoke(host, savedState)
                check(savedState.getInt("exportRequest") == 1001 && savedState.getString("exportSession") == pickerStore.exportPickerSession)
                val callback = host.javaClass.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType, android.content.Intent::class.java).apply { isAccessible = true }
                callback.invoke(host, 1000, Activity.RESULT_CANCELED, null)
                check(codeField.get(host) == 1001 && pickerStore.exportReservedByteCharge == snapshot.retainedByteCharge)
                callback.invoke(host, 1001, Activity.RESULT_CANCELED, null)
                check(codeField.get(host) == null && pickerStore.exportReservedByteCharge == 0L)
                callback.invoke(host, 1001, Activity.RESULT_CANCELED, null) // duplicate is harmless
            } finally {
                storeField.set(host, previousStore); codeField.set(host, previousCode); sessionField.set(host, previousSession)
            }
            check(pickerStore.beginExportPicker(snapshot).requestCode == 1002)
            val captured = checkNotNull(pickerStore.takeExportPicker(1002))
            pickerStore.export(android.net.Uri.fromFile(exportFile), captured) { failure ->
                check(failure == null); exported = true
            }
            val sequence = NoteStore::class.java.getDeclaredField("nextExportRequestCode").apply { isAccessible = true }
            sequence.setInt(pickerStore, 65536)
            check(pickerStore.beginExportPicker(snapshot).disposition == ExportPickerDisposition.IDENTITY_EXHAUSTED)
            check(pickerStore.exportReservedByteCharge == 0L)
        }
        eventually("Export capture did not transfer to actual IO") { exported }
        check(exportFile.readText(Charsets.UTF_8) == encoded)
        check(dev.notecore.document.PagedPreviewCodec.decode(exportFile.readText(Charsets.UTF_8)).scene.objectCount == 3)
        main { pickerStore.stopTestBackend() }
        var readerRefusals = 0
        val rejectedSources = mutableListOf<Pair<java.io.File, String>>()
        val readerStores = mutableListOf<NoteStore>()
        for ((label, rejectedText) in listOf("unsupported" to encoded.replace("MONOLINE", "PRESSURE"),
            "malformed" to encoded.replace("NCPREVIEW\t2", "NCPREVIEW\t99"))) {
            val sourceDirectory = java.io.File(directory, "reader-$label").apply { check(mkdirs()) }
            val sourceFile = java.io.File(sourceDirectory, "$document.ncp").apply { writeText(rejectedText, Charsets.UTF_8) }
            rejectedSources.add(sourceFile to rejectedText)
            val importDirectory = java.io.File(directory, "reader-import-$label")
            main {
                val readerStore = NoteStore.isolated(host, sourceDirectory)
                val importer = NoteStore.isolated(host, importDirectory)
                readerStores.add(readerStore); readerStores.add(importer)
                readerStore.open(document) { opened, error -> check(opened == null && !error.isNullOrEmpty()); readerRefusals++ }
                importer.importCopy(android.net.Uri.fromFile(sourceFile)) { opened, error ->
                    check(opened == null && !error.isNullOrEmpty()); check(!importDirectory.exists()); readerRefusals++
                }
            }
        }
        eventually("Original reader/import worker did not refuse invalid/unsupported sources") { readerRefusals == 4 }
        for ((file, contents) in rejectedSources) check(file.readText(Charsets.UTF_8) == contents)
        main { readerStores.forEach { it.stopTestBackend() } }
        close()
        check(store.inkReleaseCallbacks>callbacks) { "Missing actual compositor release callback on close" }

        var reopened = false
        main {
            store = NoteStore.isolated(host,directory)
            store.open(document) { opened, error ->
                check(error==null); note = store.adopt(checkNotNull(opened))
                check(note.capture().encode()==encoded && note.pageCount==2 && note.totalStrokeCount==3)
                reopened = true
            }
        }
        eventually("Fresh NoteStore did not reopen saved complete document") { reopened }
        main { store.releaseActive(note); store.stopTestBackend() }

        var allowLostViewSettlement = false
        setup(maySettleRelease = { allowLostViewSettlement })
        eventually("Lost-view fixture did not publish an actual source") { store.inkFramePublications > 0 }
        val lostViewPublications = store.inkFramePublications
        var savedAfterViewLoss = false
        var lostViewGeneration = 0L
        val generationField = NoteStore::class.java.getDeclaredField("inkGeneration").apply { isAccessible = true }
        main {
            lostViewGeneration = generationField.getLong(store)
            view.renderStore = null // endpoint no longer consumes renderer draws; prevent synthetic rebind
            val weakView = NoteStore::class.java.getDeclaredField("inkView").apply { isAccessible = true }
                .get(store) as java.lang.ref.WeakReference<*>
            weakView.clear() // deterministic missing endpoint, no explicit detach/retire call
            check(store.save(note.capture()) { _, failure -> check(failure == null); savedAfterViewLoss = true })
        }
        eventually("Endpoint loss did not retire on original owner progress") {
            val retiring = NoteStore::class.java.getDeclaredField("inkRetiring").apply { isAccessible = true }.getBoolean(store)
            savedAfterViewLoss && retiring && store.inkReleaseDeferred
        }
        var lostViewHeld = 0L
        main { lostViewHeld = store.inkChargedBytes; check(lostViewHeld > 0 && store.inkOwnerRetained) }
        main { view.renderStore = store; view.invalidate() } // retry cannot bypass the old retiring pool
        test.waitForIdleSync()
        main {
            check(generationField.getLong(store) == lostViewGeneration)
            check(store.inkFramePublications == lostViewPublications && store.inkChargedBytes == lostViewHeld)
            allowLostViewSettlement = true
        }
        eventually("Missing-view old pool did not drain before replacement frame") {
            store.inkFramePublications > lostViewPublications && generationField.getLong(store) > lostViewGeneration
        }
        close()

        val lostStarted = CountDownLatch(1); val lostRelease = CountDownLatch(1)
        setup(before = { lostStarted.countDown(); check(lostRelease.await(8, TimeUnit.SECONDS)) })
        check(lostStarted.await(5, TimeUnit.SECONDS))
        var savedAfterLateLoss = false
        main {
            view.renderStore = null
            (NoteStore::class.java.getDeclaredField("inkView").apply { isAccessible = true }.get(store)
                as java.lang.ref.WeakReference<*>).clear()
            check(store.save(note.capture()) { _, failure -> check(failure == null); savedAfterLateLoss = true })
        }
        lostRelease.countDown()
        eventually("Late worker output after endpoint loss was not retired/ACKed") {
            savedAfterLateLoss && !store.inkOwnerRetained && store.inkChargedBytes == 0L
        }
        main { check(store.inkFramePublications == 0); view.renderStore = store; view.invalidate() }
        eventually("Owner did not recover after late lost-endpoint result drained") { store.inkFramePublications > 0 }
        close()

        val started = CountDownLatch(1); val release = CountDownLatch(1)
        setup(before = { started.countDown(); check(release.await(8,TimeUnit.SECONDS)) })
        check(started.await(5,TimeUnit.SECONDS))
        val waiting = screenPixel()
        check(kotlin.math.abs(Color.blue(waiting)-191)<=4) { "Pending shared frame lost complete canonical fallback" }
        main { store.detachInk(view); store.releaseActive(note); host.setContentView(View(host)); check(store.inkOwnerRetained); check(store.inkChargedBytes>0) }
        release.countDown()
        eventually("Late shared-executor result was not ACKed after View removal") { !store.inkOwnerRetained && store.inkChargedBytes==0L }
        var saved = false
        main {
            val next = store.create()
            check(store.save(next.capture()) { _, error -> check(error==null); saved = true })
        }
        eventually("Render close stopped shared storage executor") { saved }
        main { store.stopTestBackend() }

        var allowSettlement = false
        var handoffs = 0
        var nativeCloses = 0
        setup(afterRelease = { nativeCloses++ }, afterSubmit = {
            handoffs++
            if (handoffs == 1) error("injected confirmation loss after real ViewRoot handoff")
        }, maySettleRelease = { allowSettlement })
        eventually("Ambiguous handoff never received its real release receipt") {
            store.inkReleaseCallbacks>0 && store.inkReleaseDeferred
        }
        var handoffHeld = 0L
        main {
            handoffHeld = store.inkChargedBytes
            check(handoffHeld>0 && !store.inkHandoffRecovered && nativeCloses==0 && handoffs==1)
            check(!store.reconcileInkRelease()) // Receipt alone cannot bypass completion/cleanup.
            store.detachInk(view); store.detachInk(view)
            check(store.inkChargedBytes==handoffHeld)
        }
        val ambiguousFallback = screenPixel()
        check(kotlin.math.abs(Color.blue(ambiguousFallback)-191)<=4) { "Ambiguous handoff lost complete Path fallback" }
        var savedWhileDeferred = false
        main {
            store.releaseActive(note)
            note = store.create()
            view = InkCanvasView(host,note,{}, { error(it) })
            host.setContentView(InkSurfaceHost(view,store))
            check(store.save(note.capture()) { _, error -> check(error==null); savedWhileDeferred = true })
        }
        eventually("Deferred release blocked original shared storage worker") { savedWhileDeferred }
        SystemClock.sleep(150) // Delayed owner settlement, not an emulated unsignaled GPU fence.
        main {
            check(store.inkChargedBytes==handoffHeld && store.inkOwnerRetained)
            check(store.inkFramePublications==0 && handoffs==1 && nativeCloses==0)
        }
        val nextFallback = screenPixel()
        check(Color.red(nextFallback)>245 && Color.green(nextFallback)>245 && Color.blue(nextFallback)>245) {
            "New document did not retain complete fallback while old pool was held"
        }
        main { allowSettlement = true }
        eventually("Real release proof did not recover handoff and unblock the new document frame") {
            store.inkHandoffRecovered && store.inkFramePublications>0
        }
        main { check(nativeCloses==1 && handoffs==2) }
        close()

        var releaseCalls = 0
        setup(afterRelease = { releaseCalls++; error("injected post-recycle confirmation loss") })
        eventually("Unknown cleanup fixture did not present") { store.inkFramePublications>0 }
        main { store.detachInk(view); store.releaseActive(note); host.setContentView(View(host)) }
        eventually("No release callback for unknown cleanup fixture") { store.inkReleaseCallbacks>0 && releaseCalls>0 }
        var held = 0L
        main { held = store.inkChargedBytes; check(held>0 && store.inkOwnerRetained); store.detachInk(view); check(releaseCalls==1) }
        main { check(store.reconcileInkRelease()); check(store.inkChargedBytes==0L && !store.inkOwnerRetained); store.stopTestBackend() }

        val storageStarted = CountDownLatch(1); val storageRelease = CountDownLatch(1)
        setup(before = { storageStarted.countDown(); check(storageRelease.await(8,TimeUnit.SECONDS)) })
        check(storageStarted.await(5,TimeUnit.SECONDS))
        var queuedSuccess = 0; var queuedFailure = 0; var pendingFailure = 0; var pendingSuccess = 0
        var queuedId = ""; var queuedBytes = ""; var pendingId = ""
        try {
            main {
                queuedId = note.documentId; queuedBytes = note.capture().encode()
                check(store.save(note.capture()) { _, error -> if (error == null) queuedSuccess++ else queuedFailure++ })
                check(store.saveStates().single().inFlight)
                store.stopTestBackend()
                val pending = PagedPreviewNotebook.create("blocked-${java.util.UUID.randomUUID()}", "Retained after stop", "blocked-session")
                pendingId = pending.documentId
                check(store.save(pending.capture()) { _, error -> if (error == null) pendingSuccess++ else pendingFailure++ })
                check(queuedSuccess == 0 && queuedFailure == 0 && pendingFailure == 1 && pendingSuccess == 0)
                check(store.saveStates().single { it.snapshot.documentId == queuedId }.inFlight)
                check(!store.saveStates().single { it.snapshot.documentId == pendingId }.inFlight)
                val callbacks = NoteStore::class.java.getDeclaredField("saveCallbacks").apply { isAccessible = true }.get(store) as Map<*, *>
                check(pendingId !in callbacks && queuedId in callbacks)
                var throwingReceiverCalled = 0
                check(store.save(pending.capture()) { _, error ->
                    check(error != null && pendingId !in callbacks)
                    throwingReceiverCalled++
                    throw IllegalStateException("Synthetic destroyed UI receiver")
                })
                check(throwingReceiverCalled == 1 && pendingId !in callbacks)
                check(store.saveStates().single { it.snapshot.documentId == pendingId }.snapshot.sameContent(pending.capture()))
                store.detachInk(view); store.releaseActive(note); host.setContentView(View(host))
            }
        } finally { storageRelease.countDown() }
        eventually("Stopping admission cancelled an already accepted storage write") {
            queuedSuccess == 1 && !store.inkOwnerRetained && store.inkChargedBytes == 0L
        }
        main {
            check(queuedFailure == 0 && pendingSuccess == 0 && pendingFailure == 1)
            check(store.saveStates().single().snapshot.documentId == pendingId)
            check(!store.saveStates().single().inFlight)
        }
        check(File(directory,"$queuedId.ncp").readText(Charsets.UTF_8) == queuedBytes)
        check(!File(directory,"$pendingId.ncp").exists())
        return "{\"app_hierarchy_pixels\":true,\"active_static_alpha\":true,\"page_zoom\":true,\"resize\":true,\"pending_complete_fallback\":true,\"actual_release_callback\":true,\"valid_release_fence_observed\":$validFence,\"late_view_ack\":true,\"shared_storage_after_close\":true,\"saved_new_owner_reopen\":true,\"weak_saved_identity_rewrites_after_clear\":true,\"export_picker_single_capture_budget_identity_and_io\":true,\"export_activity_state_and_stale_cancel\":true,\"typed_reader_rejects_original_open_import_without_overwrite\":true,\"lost_view_retires_on_owner_progress_without_bypassing_receipts\":true,\"lost_view_late_worker_ack_without_publication\":true,\"unknown_retained_bytes\":$held,\"unknown_readonly_reconciled\":true,\"ambiguous_handoff_recovered\":true,\"deferred_completion_keeps_charge\":true,\"storage_during_deferred_release\":true,\"new_frame_after_handoff_recovery\":true,\"handoff_unknown_retained_bytes\":$handoffHeld,\"stopped_backend_keeps_accepted_write\":true,\"stopped_backend_rejects_only_pending\":true,\"terminal_save_notification_drops_receiver_retains_content\":true,\"long_unsignaled_gpu_fence_tested\":false}"
    }
}
