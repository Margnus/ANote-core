package dev.notecore.canvas.prototype

import android.graphics.*
import android.hardware.HardwareBuffer
import android.hardware.SyncFence
import android.media.Image
import android.media.ImageReader
import android.media.ImageWriter
import android.view.AttachedSurfaceControl
import android.view.SurfaceControl
import java.nio.ByteBuffer
import java.time.Duration

@RequiresOptIn("Unfrozen NoteCore preview adapter; not a production SDK", level = RequiresOptIn.Level.ERROR)
annotation class ExperimentalAndroidInkFrame

/** Restricted platform seam: no raw job, pool, reader, Bitmap or pixel array is exported. */
@ExperimentalAndroidInkFrame
@android.annotation.TargetApi(33)
class AndroidInkFrameExperiment(frame: FrameToken, layer: LayerId, sharedExecutor: BoundedOwnerExecutor,
    private val wakeOwner: () -> Unit, beforeCompute: () -> Unit = {},
    private val afterNativeClose: () -> Unit = {},
) {
    private val renderer = BrushRenderExecutorPoc(frame, layer, 48L*1024*1024, 64L*1024*1024, 2,
        beforeCompute = { beforeCompute() }, sharedExecutor = sharedExecutor)
    class Request internal constructor(internal val job: BrushRenderJobPoc)
    private val requests = mutableMapOf<BrushRenderIdPoc, Request>() // at most two bounded handles
    val chargedBytes: Long get() = renderer.chargedBytes
    val isDrained: Boolean get() = renderer.isDrained && requests.isEmpty()
    fun expect(frame: FrameToken, layer: LayerId) { renderer.expect(frame, layer) }
    fun submit(scene: SceneSnapshot, frame: FrameToken, layer: LayerId, x: Double, y: Double,
        width: Int, height: Int, pixelSize: Double): Request? {
        val bytes = width.toLong()*height*4
        val rowUpper = ((width*4L+4095)/4096)*4096
        val allowance = bytes*3 + rowUpper*height // CPU ink + composite + staging + one uploaded plane
        return when (val admitted = renderer.submitPage(scene, frame, layer,
            BrushFrameGridPoc(PointD(x,y),width,height,pixelSize), 2L*1024*1024, allowance, 100_000_000)) {
            is BrushRenderAdmissionPoc.Accepted -> Request(admitted.job).also { requests[admitted.job.id] = it }
            is BrushRenderAdmissionPoc.Rejected -> null
        }
    }
    fun settleAfterDrain(consume: (Request, String) -> Unit) {
        renderer.settleAfterDrain().forEach { (id, ack) ->
            val request = requests[id] ?: return@forEach
            if (ack != BrushRenderAckPoc.QUARANTINED) requests.remove(id)
            consume(request, ack.name)
        }
    }
    fun prepare(request: Request, paper: (Canvas) -> Unit, transform: Matrix, inkRect: RectF, inkClip: RectF): SurfaceImage? {
        val calls = object : AndroidBrushImageCallsPoc {
            override fun release(bitmap: Bitmap) { bitmap.recycle(); afterNativeClose() }
        }
        val source = when (val built = AndroidBrushImagePoc.create(renderer, request.job, calls)) {
            is AndroidBrushImageBuildPoc.Ready -> built.image
            is AndroidBrushImageBuildPoc.CleanupRequired -> return SurfaceImage(built.image, request.job).apply { close() }
            is AndroidBrushImageBuildPoc.Rejected -> return null
        }
        return SurfaceImage(source, request.job).apply { upload(paper, transform, inkRect, inkClip) }
    }
    fun close() { renderer.close() } // Shared storage executor remains accepting.

    inner class SurfaceImage internal constructor(private val source: AndroidBrushImagePoc, private val job: BrushRenderJobPoc) {
        private var reader: ImageReader? = null
        private var writer: ImageWriter? = null
        private var acquired: Image? = null
        private var buffer: HardwareBuffer? = null
        private var uploadComposite: Bitmap? = null
        private var uploadInput: Image? = null
        private var child: SurfaceControl? = null
        private var root: AttachedSurfaceControl? = null
        private var submitted = false
        private var closing = false
        private var removalRequested = false
        private var unknown = false
        private var nativeHandlesClosed = false
        private val receiptLock = Any()
        private var callbackSeen = false
        private var receipt: SyncFence? = null
        private var gpuReleased = false
        var released = false; private set
        var uploadRejected = false; private set
        var rowStride = 0; private set
        var callbackCount = 0; private set
        var validFenceObserved = false; private set
        val releaseUnknown: Boolean get() = unknown || source.releaseUnknown
        val ready: Boolean get() = !closing && !unknown && source.canPublish && buffer != null
        val needsPoll: Boolean get() = !released && !releaseUnknown &&
            ((!closing && buffer == null && !uploadRejected) || synchronized(receiptLock) { callbackSeen } )
        internal fun upload(paper: (Canvas) -> Unit, transform: Matrix, rect: RectF, clip: RectF) {
            renderer.executorStatus()
            try {
                val w = job.grid.width; val h = job.grid.height; val bytes = w*h*4
                reader = ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,1,
                    HardwareBuffer.USAGE_CPU_READ_OFTEN or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_COMPOSER_OVERLAY)
                writer = ImageWriter.newInstance(checkNotNull(reader).surface,1)
                val input = checkNotNull(writer).dequeueInputImage() // Single fresh writer, never a second dequeue.
                uploadInput = input
                val plane = input.planes.single()
                rowStride = plane.rowStride
                check(plane.pixelStride == 4 && rowStride >= w*4)
                check(rowStride.toLong()*h + bytes*3L <= job.nativeCopyAllowanceBytes)
                val composite = Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
                uploadComposite = composite
                check(composite.rowBytes == w*4)
                check(source.allocationBytes + composite.allocationByteCount.toLong() + bytes + rowStride.toLong()*h <= job.nativeCopyAllowanceBytes)
                val canvas = Canvas(composite)
                paper(canvas)
                canvas.save(); canvas.concat(transform); canvas.clipRect(clip)
                check(source.draw(canvas,rect,false)) // Existing holder still only admits CPU Canvas.
                canvas.restore()
                val staging = ByteBuffer.allocate(bytes)
                composite.copyPixelsToBuffer(staging)
                val target = plane.buffer
                for (y in 0 until h) {
                    target.position(y*rowStride)
                    staging.position(y*w*4); staging.limit((y+1)*w*4)
                    target.put(staging)
                    staging.limit(bytes)
                }
                checkNotNull(writer).queueInputImage(input); uploadInput = null
                poll()
            } catch (failure: Throwable) {
                android.util.Log.w("NoteCoreRender","upload-rejected error=${failure.javaClass.simpleName}")
                uploadRejected = true
            }
            finally {
                try {
                    uploadInput?.close(); uploadInput = null
                    uploadComposite?.recycle(); uploadComposite = null
                } catch (_: Throwable) {
                    // Retain ambiguous temporary handles and the original source charge, without retry.
                    unknown = true; uploadRejected = true
                }
                if (uploadRejected) close()
            } // These CPU objects never enter a hardware draw.
        }
        fun poll() {
            renderer.executorStatus()
            if (released || releaseUnknown) return
            if (!closing && buffer == null && !uploadRejected) {
                try {
                    acquired = checkNotNull(reader).acquireNextImage() ?: return
                    buffer = checkNotNull(acquired).hardwareBuffer
                    check(buffer != null)
                } catch (_: Throwable) { uploadRejected = true; close(); return }
            }
            if (!closing) return
            if (!submitted) { gpuReleased = true; finish(); return }
            val fence = synchronized(receiptLock) { if (!callbackSeen) return else receipt }
            try {
                if (fence != null && fence.isValid) {
                    validFenceObserved = true
                    if (!fence.await(Duration.ZERO)) return // Never block UI or the storage worker.
                }
                fence?.close()
                synchronized(receiptLock) { receipt = null }
                gpuReleased = true
                finish()
            } catch (_: Throwable) { unknown = true }
        }
        /** Consumed by ViewRoot atomically with removal of the complete Path fallback. */
        fun schedule(target: SurfaceControl, attachedRoot: AttachedSurfaceControl): Boolean {
            renderer.executorStatus()
            if (!ready || submitted) return false
            val transaction = SurfaceControl.Transaction()
            var inboundFence: SyncFence? = null
            try {
                inboundFence = acquired?.fence
                transaction.setBuffer(target,checkNotNull(buffer),inboundFence) { fence ->
                    synchronized(receiptLock) {
                        if (callbackSeen) { if (receipt !== fence) fence.close() }
                        else { callbackSeen = true; receipt = fence; callbackCount++ }
                    }
                    wakeOwner() // Payload stays in this single retained receipt; no payload callback queue.
                }.setLayer(target,1).setVisibility(target,true)
                // From this point a thrown/ambiguous native call must retain the original responsibility.
                submitted = true; child = target; root = attachedRoot
                if (!attachedRoot.applyTransactionOnDraw(transaction)) {
                    unknown = true; return false // Do not guess that failure means no native handoff.
                }
                return true
            } catch (_: Throwable) { unknown = true; return false }
            finally { transaction.close(); inboundFence?.close() }
        }
        fun close() {
            renderer.executorStatus()
            if (released) return
            closing = true
            if (submitted && !removalRequested) {
                removalRequested = true
                val target = child
                if (target != null && target.isValid) {
                    try {
                        SurfaceControl.Transaction().use { transaction ->
                            transaction.setBuffer(target,null).setVisibility(target,false)
                            if (root?.applyTransactionOnDraw(transaction) != true) transaction.apply()
                        }
                    } catch (_: Throwable) { unknown = true }
                }
            }
            root = null; child = null // Pending native transactions own their handles; do not retain old ViewRoot.
            poll()
        }
        private fun finish() {
            if (!gpuReleased || unknown) return
            try {
                buffer?.close(); buffer = null
                acquired?.close(); acquired = null
                writer?.close(); writer = null
                reader?.close(); reader = null
                nativeHandlesClosed = true
                released = source.close()
            } catch (_: Throwable) { unknown = true }
        }
        fun reconcileReleased(): Boolean {
            renderer.executorStatus()
            if (released) return true
            if (!gpuReleased || !nativeHandlesClosed || unknown) return false
            if (!source.reconcileReleased()) return false
            released = true; return true
        }
    }
}
