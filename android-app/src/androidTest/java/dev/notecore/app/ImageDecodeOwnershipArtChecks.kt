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

/** Actual ART execution of the original host's three ownership scenarios. Test-only payloads. */
class ImageDecodeOwnershipArtChecks private constructor(private val context: android.content.Context, private val output: File) {
    private val allAllocated = ConcurrentLinkedQueue<Bitmap>()
    private var joinedWorkers = 0
    private fun assertTrue(value: Boolean, message: String = "Expected true") { check(value) { message } }
    private fun assertFalse(value: Boolean) { check(!value) }
    private fun assertNull(value: Any?) { check(value == null) }
    private fun <T : Any> assertNotNull(value: T?): T = checkNotNull(value)
    private fun assertEquals(expected: Long, actual: Long) { check(expected == actual) { "$expected != $actual" } }
    private fun <T> assertEquals(expected: T, actual: T) { check(expected == actual) { "$expected != $actual" } }
    private fun assertSame(expected: Any?, actual: Any?) { check(expected === actual) }
    private inline fun <reified T> assertIs(value: Any?): T { check(value is T); return value }
    private data class Input(val bytes: ByteArray, val sample: Int, val argbBytes: Long, val cancelled: AtomicBoolean = AtomicBoolean()) {
        val charge: Long get() = bytes.size.toLong() + argbBytes
    }
    private data class Output(val bitmap: Bitmap?, val cancelled: Boolean)
    private fun input(kind: String): Input {
        val bytes = context.assets.open("image-ownership/$kind-1.$kind").use { it.readBytes() }
        val rules = Class.forName("dev.notecore.document.ImageDecodeRules")
        val result = rules.getMethod("plan", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            .invoke(rules.getField("INSTANCE").get(null), 80, 120, 1, 20, 30, 9600L, 2400L)
        val plan = assertNotNull(result.javaClass.getMethod("getPlan").invoke(result))
        return Input(bytes, (plan.javaClass.getMethod("getSampleSize").invoke(plan) as Number).toInt(),
            (plan.javaClass.getMethod("getArgbByteUpperBound").invoke(plan) as Number).toLong())
    }
    private fun decode(input: Input): Bitmap = checkNotNull(BitmapFactory.decodeByteArray(input.bytes, 0, input.bytes.size,
        BitmapFactory.Options().apply { inSampleSize = input.sample; inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 })).also { allAllocated.add(it) }
    private fun observation(name: String, value: JSONObject) {
        File(output, "ownership-$name.json").writeText(value.toString(2) + "\n", Charsets.UTF_8)
    }
    private fun finish(executor: BoundedOwnerExecutor, allocated: Collection<Bitmap> = emptyList()) {
        executor.stopAccepting(); assertTrue(executor.awaitWorkerStop(5000), "Native test worker did not finish")
        (allocated + allAllocated).forEach { if (!it.isRecycled) it.recycle() }
        joinedWorkers++
    }

    fun queuedCancellationAndStoppedAdmissionsDoNotDropAcceptedResponsibility() {
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

    fun cancellationAfterDecodeKeepsWorkerOwnershipUntilItRecyclesAndOwnerAcks() {
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

    fun receiverFailureAfterReleaseRetainsSameResultAndRetryDoesNotRecycleTwice() {
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
    companion object {
        fun verify(target: android.content.Context, tests: android.content.Context): String {
            val dir = File(target.cacheDir, "image-owner-art-${java.util.UUID.randomUUID()}").apply { check(mkdirs()) }
            val sources = org.json.JSONObject()
            for (name in listOf("jpeg-1.jpeg", "png-1.png")) {
                val bytes = tests.assets.open("image-ownership/$name").use { it.readBytes() }
                File(dir, name).writeBytes(bytes)
                sources.put(name, java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            }
            val checks = ImageDecodeOwnershipArtChecks(tests, dir)
            checks.queuedCancellationAndStoppedAdmissionsDoNotDropAcceptedResponsibility()
            checks.cancellationAfterDecodeKeepsWorkerOwnershipUntilItRecyclesAndOwnerAcks()
            checks.receiverFailureAfterReleaseRetainsSameResultAndRetryDoesNotRecycleTwice()
            check(checks.joinedWorkers == 3 && checks.allAllocated.size == 2 && checks.allAllocated.all { it.isRecycled })
            val result = org.json.JSONObject().put("status", "passed").put("output", dir.absolutePath)
                .put("sources_sha256", sources).put("joined_workers", checks.joinedWorkers).put("tracked_bitmaps", checks.allAllocated.size)
                .put("all_bitmaps_recycled", true).put("scope", "Actual ART original executor ownership scenarios; API recycle state and declared payload charges only, no JNI interruption/native heap/production task or owner loss acceptance")
            File(dir, "result.json").writeText(result.toString(2) + "\n", Charsets.UTF_8)
            return result.toString()
        }
    }

}
