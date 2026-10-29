package dev.notecore.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.notecore.canvas.prototype.*
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.*

/** Real original worker + host native Bitmap; no production task owner, JNI interruption or heap claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageDecodeOwnershipNativeTest {
    private data class Input(val bytes: ByteArray, val sample: Int, val argbBytes: Long, val cancelled: AtomicBoolean = AtomicBoolean()) {
        val charge: Long get() = bytes.size.toLong() + argbBytes
    }
    private data class Output(val bitmap: Bitmap?, val cancelled: Boolean)
    private fun input(kind: String): Input {
        val file = File(checkNotNull(System.getProperty("notecore.image.fixtures")), "$kind-1.$kind")
        val rules = Class.forName("dev.notecore.document.ImageDecodeRules")
        val result = rules.getMethod("plan", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            .invoke(rules.getField("INSTANCE").get(null), 80, 120, 1, 20, 30, 9600L, 2400L)
        val plan = assertNotNull(result.javaClass.getMethod("getPlan").invoke(result))
        return Input(file.readBytes(), (plan.javaClass.getMethod("getSampleSize").invoke(plan) as Number).toInt(),
            (plan.javaClass.getMethod("getArgbByteUpperBound").invoke(plan) as Number).toLong())
    }
    private fun decode(input: Input): Bitmap = checkNotNull(BitmapFactory.decodeByteArray(input.bytes, 0, input.bytes.size,
        BitmapFactory.Options().apply { inSampleSize = input.sample; inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }))
    private fun observation(name: String, value: JSONObject) {
        val dir = File(checkNotNull(System.getProperty("notecore.image.budget.outputs"))).apply { mkdirs() }
        File(dir, "ownership-$name.json").writeText(value.toString(2) + "\n", Charsets.UTF_8)
    }
    private fun finish(executor: BoundedOwnerExecutor, allocated: Collection<Bitmap> = emptyList()) {
        executor.stopAccepting(); assertTrue(executor.awaitWorkerStop(5000), "Native test worker did not finish")
        allocated.forEach { if (!it.isRecycled) it.recycle() }
    }

    @Test fun queuedCancellationAndStoppedAdmissionsDoNotDropAcceptedResponsibility() {
        val input = input("jpeg"); val executor = BoundedOwnerExecutor(2, input.charge * 2, "image-queued-test")
        val started = CountDownLatch(1); val release = CountDownLatch(1); val decodes = AtomicInteger()
        try {
            assertIs<WorkAdmission.Accepted>(executor.submit(input.charge, {
                started.countDown(); check(release.await(5, TimeUnit.SECONDS)); 1
            }) { ResultDisposition.ACKNOWLEDGED })
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertIs<WorkAdmission.Accepted>(executor.submit(input, input.charge, { source ->
                executor.checkWorkerThread()
                if (source.cancelled.get()) Output(null, true) else { decodes.incrementAndGet(); Output(decode(source), false) }
            }) { _, result ->
                val output = assertIs<WorkResult.Succeeded<Output>>(result).value
                assertTrue(output.cancelled); assertNull(output.bitmap); ResultDisposition.ACKNOWLEDGED
            })
            assertEquals(OwnedWorkStage.QUEUED, executor.status().entries.last().stage)
            assertEquals(WorkAdmissionFailure.ENTRY_CAPACITY, assertIs<WorkAdmission.Rejected>(executor.submit(input.charge, { 1 }) { ResultDisposition.ACKNOWLEDGED }).reason)
            input.cancelled.set(true); executor.stopAccepting()
            assertEquals(WorkAdmissionFailure.STOPPED, assertIs<WorkAdmission.Rejected>(executor.submit(input.charge, { 1 }) { ResultDisposition.ACKNOWLEDGED }).reason)
            assertEquals(input.charge * 2, executor.status().chargedBytes)
            release.countDown(); assertTrue(executor.awaitWorkerStop(5000))
            assertEquals(input.charge * 2, executor.status().chargedBytes); assertEquals(0, decodes.get())
            assertEquals(2, executor.drainResults().acknowledged); assertEquals(0, executor.status().chargedBytes)
            observation("queued", JSONObject().put("source_bytes", input.bytes.size).put("argb_bytes", input.argbBytes).put("decode_calls", decodes.get()).put("charge_before_ack", input.charge * 2).put("charge_after_ack", 0))
        } finally { release.countDown(); finish(executor) }
    }

    @Test fun cancellationAfterDecodeKeepsWorkerOwnershipUntilItRecyclesAndOwnerAcks() {
        val input = input("jpeg"); val executor = BoundedOwnerExecutor(1, input.charge, "image-running-test")
        val decoded = CountDownLatch(1); val release = CountDownLatch(1); val allocated = ConcurrentLinkedQueue<Bitmap>(); val closes = AtomicInteger()
        try {
            assertIs<WorkAdmission.Accepted>(executor.submit(input, input.charge, { source ->
                executor.checkWorkerThread(); val bitmap = decode(source); allocated.add(bitmap); decoded.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                if (source.cancelled.get()) { bitmap.recycle(); closes.incrementAndGet(); Output(null, true) } else Output(bitmap, false)
            }) { _, result ->
                val output = assertIs<WorkResult.Succeeded<Output>>(result).value
                assertTrue(output.cancelled); assertNull(output.bitmap); ResultDisposition.ACKNOWLEDGED
            })
            assertTrue(decoded.await(5, TimeUnit.SECONDS)); assertEquals(OwnedWorkStage.RUNNING, executor.status().entries.single().stage)
            val bitmap = allocated.single(); assertFalse(bitmap.isRecycled)
            input.cancelled.set(true); executor.stopAccepting()
            assertFalse(bitmap.isRecycled); assertEquals(input.charge, executor.status().chargedBytes)
            release.countDown(); assertTrue(executor.awaitWorkerStop(5000))
            assertTrue(bitmap.isRecycled); assertEquals(1, closes.get()); assertEquals(input.charge, executor.status().chargedBytes)
            assertEquals(1, executor.drainResults().acknowledged); assertEquals(0, executor.status().chargedBytes)
            observation("running", JSONObject().put("source_bytes", input.bytes.size).put("argb_bytes", input.argbBytes).put("decode_calls", 1).put("recycle_calls", closes.get())
                .put("charge_until_owner_ack", input.charge).put("charge_after_ack", 0).put("inside_jni_cancellation_tested", false))
        } finally { release.countDown(); finish(executor, allocated) }
    }

    @Test fun receiverFailureAfterReleaseRetainsSameResultAndRetryDoesNotRecycleTwice() {
        val input = input("png"); val executor = BoundedOwnerExecutor(1, input.charge, "image-delivery-test")
        val allocated = ConcurrentLinkedQueue<Bitmap>(); var deliveries = 0; var closes = 0; var first: Output? = null
        try {
            val ticket = assertIs<WorkAdmission.Accepted>(executor.submit(input, input.charge, { source ->
                executor.checkWorkerThread(); val bitmap = decode(source); allocated.add(bitmap); Output(bitmap, false)
            }) { source, result ->
                executor.checkOwnerThread(); assertTrue(source.cancelled.get())
                val output = assertIs<WorkResult.Succeeded<Output>>(result).value
                if (first == null) first = output else assertSame(first, output)
                val bitmap = assertNotNull(output.bitmap)
                if (!bitmap.isRecycled) { bitmap.recycle(); closes++ }
                if (++deliveries == 1) error("Owner consumer failed after native API release")
                ResultDisposition.ACKNOWLEDGED
            }).ticket
            executor.stopAccepting(); assertTrue(executor.awaitWorkerStop(5000))
            assertFalse(allocated.single().isRecycled); input.cancelled.set(true)
            assertEquals(0, executor.drainResults().acknowledged)
            assertEquals(OwnedWorkStage.DELIVERY_BLOCKED, executor.status().entries.single().stage)
            assertTrue(allocated.single().isRecycled); assertEquals(1, closes); assertEquals(input.charge, executor.status().chargedBytes)
            assertTrue(executor.retryDelivery(ticket)); assertEquals(1, executor.drainResults().acknowledged)
            assertEquals(2, deliveries); assertEquals(1, closes); assertEquals(0, executor.status().chargedBytes)
            observation("delivery", JSONObject().put("source_bytes", input.bytes.size).put("argb_bytes", input.argbBytes).put("deliveries", deliveries).put("recycle_calls", closes)
                .put("blocked_charge", input.charge).put("final_charge", 0).put("same_result_retried", true))
        } finally { finish(executor, allocated) }
    }
}
