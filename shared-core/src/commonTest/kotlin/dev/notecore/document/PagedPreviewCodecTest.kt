package dev.notecore.document

import dev.notecore.canvas.preview.PreviewNotebook
import dev.notecore.canvas.prototype.*
import kotlin.test.*

class PagedPreviewCodecTest {
    @Test fun cumulativePointsCannotBypassAdmissionWithManyIndividuallyValidStrokes() {
        val text = buildString {
            append("NCPREVIEW\t2\nD\tcumulative\tcapacity\t1\t11\t0\nG\tp\tl\t595.0\t842.0\tBLANK\t0\n")
            repeat(11) { stroke ->
                append("S\ts-$stroke\tl\t1\tMONOLINE\t1\t-1\t2.0\t20000\t1.0\t0.0\t0.0\t1.0\t0.0\t0.0\n")
                if (stroke < 10) repeat(20_000) { append("P\t1.0\t2.0\t0.5\t1\n") }
            }
            append("END\n")
        }
        // The final stroke has no points: reject its aggregate claim before reading or allocating them.
        invalid(text)
        val max = text.substringBefore("S\ts-10") + "END\n"
        val restored = PagedPreviewCodec.decode(max.replace("\t1\t11\t0\n", "\t1\t10\t0\n"))
        assertEquals(200_000, restored.scene.objects().sumOf { it.samples.size })
        assertEquals(restored.scene.objects(), PagedPreviewCodec.decode(PagedPreviewCodec.encode(restored)).scene.objects())
    }
    private val doc = SceneId("doc\\\t😀")
    private val a = PageDescriptor(PageId("page-a"), LayerId("layer-a"), 595.0, 842.0, PaperKind.GRID, 7)
    private val b = PageDescriptor(PageId("page-b"), LayerId("layer-b"), 612.0, 792.0, PaperKind.DOTS, 5)
    private val samples = StrokeSamples.copyOf(doubleArrayOf(-0.0, 1e-10), doubleArrayOf(100.5, -42.25), floatArrayOf(0.2f, 0.8f), longArrayOf(123, Long.MAX_VALUE))
    private fun root(): DocumentRoot {
        val objects = listOf(
            StrokeObject(ObjectId("z-first"), 9, a.layerId, BrushSpec(BrushKind.HIGHLIGHTER, 1, 12.0, 0x7f123456), samples, Affine2D(0.0, 1.0, -1.0, 0.0, 10.0, -20.0)),
            StrokeObject(ObjectId("a-last"), 3, b.layerId, BrushSpec(BrushKind.PRESSURE, 1, 2.5, 0xff123456.toInt()), samples))
        return DocumentRoot.compose("title\\\t\n\r😀", SceneSnapshot.restored(doc, 12, objects), listOf(b, a))
    }
    private fun encoded() = PagedPreviewCodec.encode(root())
    private fun invalid(text: String) { assertFailsWith<IllegalArgumentException> { PagedPreviewCodec.decode(text) } }
    private fun legacy(): String {
        val note = PreviewNotebook("legacy", "旧笔记😀")
        assertTrue(note.beginStroke(1.0, 2.0, 0.3f, 123)); assertTrue(note.endStroke(3.0, 4.0, 0.8f, 456))
        note.rename("旧标题")
        return note.capture().encode()
    }
    @Test fun roundTripPreservesEveryCanonicalFieldPageAndDrawOrderAndTextBytes() {
        val before = root(); val text = encoded(); val after = PagedPreviewCodec.decode(text)
        assertEquals(before.id, after.id); assertEquals(before.title, after.title); assertEquals(before.revision, after.revision)
        assertEquals(before.pages(), after.pages()); assertEquals(before.scene.objects(), after.scene.objects())
        assertEquals(text, PagedPreviewCodec.encode(after)); assertEquals(-0.0, after.scene.objects()[0].samples.x(0))
        assertEquals(Long.MAX_VALUE, after.scene.objects()[0].samples.timeMicros(1))
    }
    @Test fun emptyPageAndCrLfInputRoundTripWithNoHistoryOrReceipts() {
        val before = DocumentRoot.empty(SceneId("empty"), "empty", a.copy(contentRevision = 0))
        val text = PagedPreviewCodec.encode(before)
        val after = PagedPreviewCodec.decode(text.replace("\n", "\r\n"))
        assertEquals(before.pages(), after.pages()); assertEquals(0, after.scene.objectCount)
        val owner = MemoryDocumentAuthority(after); assertEquals(0, owner.status().undoCount); assertEquals(0, owner.status().operationCount)
    }
    @Test fun boundedHeaderReportsV2MetadataWithoutValidatingBody() {
        val text = encoded().lineSequence().take(2).joinToString("\n")+"\nBROKEN"
        val header = PagedPreviewCodec.header(text)
        assertEquals(2, header.formatVersion); assertEquals(2, header.pageCount); assertEquals(2, header.objectCount)
        assertEquals(doc.value, header.documentId); assertEquals(root().title, header.title); assertEquals(12L, header.revision)
        invalid(text)
        assertFailsWith<IllegalArgumentException> { PagedPreviewCodec.header("x".repeat(4097)) }
    }
    @Test fun v1MigrationKeepsOriginalTimePressureAndVersionAndDoesNotModifyInput() {
        val text = legacy(); val root = PagedPreviewCodec.decode(text)
        assertEquals(SceneId("legacy"), root.id); assertEquals("旧标题", root.title); assertEquals(2L, root.revision)
        assertEquals(PageId("page-1"), root.page(0).id); assertEquals(LayerId("ink"), root.page(0).layerId)
        assertEquals(2L, root.page(0).contentRevision)
        val obj = root.scene.objects().single(); assertEquals(ObjectId("ink-1"), obj.id)
        assertEquals(123L, obj.samples.timeMicros(0)); assertEquals(456L, obj.samples.timeMicros(1)); assertEquals(0.3f, obj.samples.pressure(0))
        assertTrue(text.startsWith("NCPREVIEW\t1\n")); assertTrue(PagedPreviewCodec.encode(root).startsWith("NCPREVIEW\t2\n"))
        val header = PagedPreviewCodec.header(text.lineSequence().take(2).joinToString("\n"))
        assertEquals(1, header.formatVersion); assertEquals(1, header.pageCount); assertEquals(1, header.objectCount)
    }
    @Test fun duplicateMembershipAndOrphanObjectsAreRejected() {
        val text = encoded()
        invalid(text.replace("G\tpage-a\tlayer-a", "G\tpage-b\tlayer-a"))
        invalid(text.replace("G\tpage-a\tlayer-a", "G\tpage-a\tlayer-b"))
        invalid(text.replace("S\ta-last", "S\tz-first"))
        invalid(text.replace("S\ta-last\tlayer-b", "S\ta-last\torphan"))
    }
    @Test fun countsAndPointCapacityRejectBeforeReadingOrAllocatingClaimedPayload() {
        val text = encoded()
        invalid(text.replace("\t2\t2\t12\n", "\t0\t2\t12\n"))
        invalid(text.replace("\t2\t2\t12\n", "\t129\t2\t12\n"))
        invalid(text.replace("\t2\t2\t12\n", "\t2\t5001\t12\n"))
        invalid(text.replace("\t12.0\t2\t", "\t12.0\t2147483647\t"))
        invalid(text.replace("\t12.0\t2\t", "\t12.0\t0\t"))
    }
    @Test fun invalidGeometrySamplesAndMonotonicTimeCannotEnterCanonicalScene() {
        val text = encoded()
        invalid(text.replace("\t0.0\t1.0\t-1.0\t0.0\t10.0\t-20.0", "\t0.0\t0.0\t0.0\t0.0\t10.0\t-20.0"))
        invalid(text.replace("P\t-0.0", "P\tNaN")); invalid(text.replace("\t0.2\t123", "\t1.2\t123"))
        invalid(text.replace("\t9223372036854775807", "\t1")); invalid(text.replace("\t12.0\t2\t", "\tInfinity\t2\t"))
        invalid(text.replace("G\tpage-a\tlayer-a\t595.0", "G\tpage-a\tlayer-a\t0.0"))
    }
    @Test fun unknownFormatsPaperBrushAndAlgorithmVersionsFailExplicitly() {
        val text = encoded()
        invalid(text.replace("NCPREVIEW\t2", "NCPREVIEW\t3"))
        invalid(text.replace("\tGRID\t7", "\tUNKNOWN\t7"))
        invalid(text.replace("HIGHLIGHTER\t1", "UNKNOWN\t1"))
        invalid(text.replace("HIGHLIGHTER\t1", "HIGHLIGHTER\t2"))
        val source = root(); val obj = source.scene.objects().first()
        val future = DocumentRoot.compose(source.title, SceneSnapshot.restored(source.id, 12, listOf(obj.copy(brush = obj.brush.copy(algorithmVersion = 2)))), source.pages())
        assertFailsWith<IllegalArgumentException> { PagedPreviewCodec.encode(future) }
    }
    @Test fun negativeOrFuturePageRevisionsAndNonPositiveObjectRevisionsAreRejected() {
        val text = encoded()
        invalid(text.replace("\tGRID\t7", "\tGRID\t13")); invalid(text.replace("\tGRID\t7", "\tGRID\t-1"))
        invalid(text.replace("S\tz-first\tlayer-a\t9", "S\tz-first\tlayer-a\t0"))
        invalid(text.replace("\t2\t2\t12\n", "\t2\t2\t-1\n"))
    }
    @Test fun truncationExtraFieldsTrailingDataAndBadEscapesAreRejected() {
        val text = encoded()
        invalid(text.substringBefore("END")); invalid(text+"EXTRA\n"); invalid(text+"\n")
        invalid(text.replace("G\tpage-a", "G\textra\tpage-a"))
        invalid(text.replace("page-a", "page\\q")); invalid(text.replace("page-a", "page\\"))
        assertEquals(root().title, PagedPreviewCodec.decode(text.removeSuffix("\n")).title)
    }
    @Test fun oversizedRowsAndUnpairedSurrogatesCannotBecomeUtf8ReplacementData() {
        invalid("NCPREVIEW\t2\n"+"D\t"+"x".repeat(1025))
        invalid(legacy().replace("旧标题", "\uD800"))
        invalid(encoded().replace("page-a", "\uDC00"))
        val malformed = DocumentRoot.empty(SceneId("valid"), "\uD800", a.copy(contentRevision = 0))
        assertFailsWith<IllegalArgumentException> { PagedPreviewCodec.encode(malformed) }
        invalid("NCPREVIEW\t1\nD\t"+"\t".repeat(1025))
    }
    @Test fun importAsCopyRequiresFreshCompleteUniqueMappingsAndResetsAllContentVersions() {
        val copied = PagedPreviewCodec.importAsCopy(encoded(), SceneId("copy"), mapOf(a.id to PageId("a-new"), b.id to PageId("b-new")),
            mapOf(a.layerId to LayerId("a-new"), b.layerId to LayerId("b-new")), mapOf(ObjectId("z-first") to ObjectId("one"), ObjectId("a-last") to ObjectId("two")))
        assertEquals(SceneId("copy"), copied.id); assertEquals(0L, copied.revision)
        assertEquals(listOf(PageId("b-new"), PageId("a-new")), copied.pages().map { it.id }); assertTrue(copied.pages().all { it.contentRevision == 0L })
        assertEquals(listOf(ObjectId("one"), ObjectId("two")), copied.scene.objects().map { it.id }); assertTrue(copied.scene.objects().all { it.objectRevision == 1L })
        assertEquals(root().scene.objects().map { it.samples }, copied.scene.objects().map { it.samples })
        assertEquals(copied.scene.objects(), PagedPreviewCodec.decode(PagedPreviewCodec.encode(copied)).scene.objects())
    }
    @Test fun copyMappingErrorsNeverChangeTheSourceOrReturnPartialDocuments() {
        val text = encoded(); val p = mapOf(a.id to PageId("a-new"), b.id to PageId("b-new")); val l = mapOf(a.layerId to LayerId("a-new"), b.layerId to LayerId("b-new"))
        val o = mapOf(ObjectId("z-first") to ObjectId("one"), ObjectId("a-last") to ObjectId("two"))
        fun fail(doc: SceneId = SceneId("copy"), pages: Map<PageId, PageId> = p, layers: Map<LayerId, LayerId> = l, objects: Map<ObjectId, ObjectId> = o) {
            assertFailsWith<IllegalArgumentException> { PagedPreviewCodec.importAsCopy(text, doc, pages, layers, objects) }
            assertEquals(text, encoded())
        }
        fail(doc = this.doc); fail(pages = emptyMap()); fail(layers = emptyMap()); fail(objects = emptyMap())
        fail(pages = p.mapValues { a.id }); fail(layers = l.mapValues { LayerId("same") }); fail(objects = o.mapValues { ObjectId("same") })
        fail(objects = o + (ObjectId("orphan") to ObjectId("extra")))
    }
    @Test fun workChargeIncludesRetainedSnapshotAndBoundedEncodingReservation() {
        val root = root(); val retained = PagedPreviewCodec.retainedByteCharge(root); val work = PagedPreviewCodec.encodingWorkByteCharge(root)
        assertTrue(retained > 4 * 28); assertTrue(work > retained + encoded().length * 2)
        assertTrue(work <= retained + PagedPreviewCodec.MAX_CHARACTERS * 8L)
        assertTrue(PagedPreviewCodec.decodingWorkByteCharge(encoded().length) > encoded().length * 2L + retained)
        assertFailsWith<IllegalArgumentException> { PagedPreviewCodec.decodingWorkByteCharge(-1) }
        assertFailsWith<IllegalArgumentException> { PagedPreviewCodec.decodingWorkByteCharge(PagedPreviewCodec.MAX_CHARACTERS + 1) }
        val oversized = DocumentRoot.compose("many", SceneSnapshot.empty(SceneId("many")), (0..128).map {
            PageDescriptor(PageId("p-$it"), LayerId("l-$it"), 595.0, 842.0) })
        assertFailsWith<IllegalArgumentException> { PagedPreviewCodec.encode(oversized) }
    }
}
