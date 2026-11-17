package dev.notecore.document

import dev.notecore.canvas.prototype.*
import kotlin.test.*

/** JVM allocation regressions for preview validation and framed recovery. */
class PreviewReadAllocationTest {
    @Test fun acceptingDenseImmutableRootDoesNotAllocateCoefficientListsAndDerivedInversesPerStroke() {
        val meter = assertIs<com.sun.management.ThreadMXBean>(java.lang.management.ManagementFactory.getThreadMXBean())
        assertTrue(meter.isThreadAllocatedMemorySupported); meter.isThreadAllocatedMemoryEnabled = true
        val id = SceneId("read-allocation")
        val page = PageDescriptor(PageId("page"), LayerId("ink"), 595.0, 842.0, contentRevision = 1)
        val samples = StrokeSamples.copyOf(doubleArrayOf(1.0), doubleArrayOf(2.0), floatArrayOf(.5f), longArrayOf(1))
        val brush = BrushSpec(BrushKind.MONOLINE, 1, 2.0, -1)
        val matrix = Affine2D(1.25, .125, .25, .75, 3.0, 5.0)
        val objects = (1..5000).map { StrokeObject(ObjectId("object-$it"), 1, page.layerId, brush, samples, matrix) }
        val dense = DocumentRoot.compose("title", SceneSnapshot.restored(id, 1, objects), listOf(page))
        val empty = DocumentRoot.compose("title", SceneSnapshot.restored(id, 1, emptyList()), listOf(page))
        fun measured(root: DocumentRoot): Long {
            repeat(8) { assertSame(root, PreviewDocumentReader.accept(root).root) }
            return (1..3).minOf {
                val before = meter.getThreadAllocatedBytes(Thread.currentThread().id)
                val result = PreviewDocumentReader.accept(root)
                val count = meter.getThreadAllocatedBytes(Thread.currentThread().id) - before
                assertNull(result.failure); assertSame(root, result.root)
                count
            }
        }
        val small = measured(empty); val large = measured(dense)
        println("preview-read-admission emptyBytes=$small denseBytes=$large")
        assertTrue(large - small < 1024 * 1024, "Dense reader allocated transient validation containers: $small -> $large")
        assertEquals(5000, dense.scene.objectCount)
        assertSame(objects.last(), dense.scene.find(objects.last().id))
        assertSame(samples, dense.scene.find(objects.first().id)!!.samples)
    }
    @Test fun oversizedRecoveryHeaderIsRejectedWithoutCopyingBody() {
        val meter = assertIs<com.sun.management.ThreadMXBean>(java.lang.management.ManagementFactory.getThreadMXBean())
        assertTrue(meter.isThreadAllocatedMemorySupported); meter.isThreadAllocatedMemoryEnabled = true
        val input = "NCRECOVERY\t1\n" + "x".repeat(2 * 1024 * 1024)
        fun reject() { assertFailsWith<IllegalArgumentException> { PreviewRecoveryCodec.header(input) } }
        repeat(8) { reject() }
        val allocated = (1..3).minOf {
            val before = meter.getThreadAllocatedBytes(Thread.currentThread().id)
            reject()
            meter.getThreadAllocatedBytes(Thread.currentThread().id) - before
        }
        println("preview-recovery-header rejectedBytes=$allocated")
        assertTrue(allocated < 64 * 1024, "Rejected header copied body: $allocated bytes")
    }

    @Test fun longRecoveryDecodeDoesNotAllocateCopiesOfTheFramedBodies() {
        val meter = assertIs<com.sun.management.ThreadMXBean>(java.lang.management.ManagementFactory.getThreadMXBean())
        assertTrue(meter.isThreadAllocatedMemorySupported)
        meter.isThreadAllocatedMemoryEnabled = true
        val text = longRecoveryText()
        fun decode(): PagedPreviewSnapshot = PreviewRecoveryCodec.decode(text)
        repeat(3) { assertEquals(9, decode().root.scene.objectCount) }
        val allocated = (1..3).minOf {
            val before = meter.getThreadAllocatedBytes(Thread.currentThread().id)
            val snapshot = decode()
            val bytes = meter.getThreadAllocatedBytes(Thread.currentThread().id) - before
            assertEquals(180_000, snapshot.root.scene.objects().sumOf { it.samples.size })
            val pending = assertIs<CanvasCommand.AddStroke>(snapshot.pending?.command)
            assertEquals(20_000, pending.samples.size)
            bytes
        }
        println("preview-recovery-frame inputCharacters=${text.length} decodeAllocatedBytes=$allocated")
        assertTrue(allocated < 50L * 1024 * 1024, "Recovery decode allocated $allocated bytes for ${text.length} characters")
    }

    @Test fun longRecoveryEncodeDoesNotAllocateCopiesOfTheFramedBodies() {
        val meter = assertIs<com.sun.management.ThreadMXBean>(java.lang.management.ManagementFactory.getThreadMXBean())
        assertTrue(meter.isThreadAllocatedMemorySupported)
        meter.isThreadAllocatedMemoryEnabled = true
        val text = longRecoveryText()
        val snapshot = PreviewRecoveryCodec.decode(text)
        repeat(3) { assertEquals(text.length, snapshot.encode().length) }
        val allocated = (1..3).minOf {
            val before = meter.getThreadAllocatedBytes(Thread.currentThread().id)
            val encoded = snapshot.encode()
            val bytes = meter.getThreadAllocatedBytes(Thread.currentThread().id) - before
            assertTrue(encoded == text, "Recovery encoding differs from the fixed cross-platform fixture")
            bytes
        }
        println("preview-recovery-encoding outputCharacters=${text.length} encodeAllocatedBytes=$allocated")
        assertTrue(allocated < 100L * 1024 * 1024, "Recovery encode allocated $allocated bytes for ${text.length} characters")
    }

    private fun longRecoveryText(): String {
        val point = "P\t-1.2345678901234567E-100\t-1.2345678901234567E-100\t1.2345679E-20\t9223372036854775807\n"
        val metadata = "D\tlong-capacity\tlong digits\t1\t9\t1"
        val base = buildString {
            append("NCPREVIEW\t2\n$metadata\nG\tpage\tink\t595.0\t842.0\tBLANK\t1\n")
            repeat(9) { stroke ->
                append("S\told-$stroke\tink\t1\tMONOLINE\t1\t-1\t2.0\t20000\t1.0\t0.0\t0.0\t1.0\t0.0\t0.0\n")
                repeat(20_000) { append(point) }
            }
            append("END\n")
        }
        val ink = buildString {
            append("NCPREVIEW\t2\nD\tlong-capacity\t\t1\t1\t0\nG\tpage\tink\t595.0\t842.0\tBLANK\t0\n")
            append("S\tproducer-ink-2\tink\t1\tMONOLINE\t1\t-14339513\t2.5\t20000\t1.0\t0.0\t0.0\t1.0\t0.0\t0.0\n")
            repeat(20_000) { append(point) }
            append("END\n")
        }
        val operation = "producer-op-1".map { it.code.toString(16).padStart(4, '0') }.joinToString("")
        return "NCRECOVERY\t1\n$metadata\n${base.length}\t${ink.length}\t$operation\n$base${ink}RECOVERY-END\n"
    }
}
