package dev.notecore.document

import dev.notecore.canvas.prototype.*
import kotlin.test.*

/** Specific opening-admission allocation regression; not a physical heap/performance threshold. */
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
}
