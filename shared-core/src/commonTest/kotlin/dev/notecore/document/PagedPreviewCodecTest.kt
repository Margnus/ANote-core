package dev.notecore.document

import dev.notecore.canvas.prototype.*
import kotlin.test.*

class PagedPreviewCodecTest {
    @Test fun utf8MetadataAndFiniteNumberWidthsFitTheDeclaredEnvelope() {
        val numbers = listOf(-Double.MAX_VALUE, Double.MIN_VALUE, -1.2345678901234567e-300,
            -0.0009999999999999999, 999999999.9999999, 1.2345678901234567e100)
        assertTrue(numbers.all { it.toString().length <= 25 })
        assertTrue(listOf(Float.MIN_VALUE, 1.2345679e-20f, .99999994f).all { it.toString().length <= 16 })
        val layer = LayerId("层".repeat(128)); val page = PageDescriptor(PageId("页".repeat(128)), layer,
            1234.5678901234567, 1234.5678901234567, contentRevision = Long.MAX_VALUE)
        val samples = StrokeSamples.copyOf(doubleArrayOf(-1.2345678901234567e-100),
            doubleArrayOf(-1.2345678901234567e-100), floatArrayOf(1.2345679e-20f), longArrayOf(Long.MAX_VALUE))
        val stroke = StrokeObject(ObjectId("笔".repeat(128)), Long.MAX_VALUE, layer,
            BrushSpec(BrushKind.MONOLINE, 1, 1.2345678901234567, Int.MIN_VALUE), samples,
            Affine2D(a=1.2345678901234567, b=.2345678901234567, c=.3456789012345678,
                d=1.3456789012345678, tx=1.2345678901234567e100, ty=1.2345678901234567e100))
        val root = DocumentRoot.compose("题".repeat(120), SceneSnapshot.restored(SceneId("文".repeat(128)), Long.MAX_VALUE, listOf(stroke)), listOf(page))
        val text = PagedPreviewCodec.encode(root)
        assertTrue(text.lineSequence().filter { it.isNotEmpty() }.all { it.encodeToByteArray().size + 1 <= 1024 })
        assertEquals(text, PagedPreviewCodec.encode(PagedPreviewCodec.decode(text)))
        assertEquals(26_381_312, PagedPreviewCodec.MAX_CHARACTERS)
        assertEquals(32 * 1024 * 1024, PagedPreviewCodec.MAX_UTF8_BYTES)
    }

    @Test fun declaredPointCapacityKeepsLongNumericSamplesSerializableAfterAdoption() {
        val count = PagedPreviewCodec.MAX_STROKE_POINTS
        val samples = StrokeSamples.copyOf(DoubleArray(count) { -1.2345678901234567e-100 },
            DoubleArray(count) { -1.2345678901234567e-100 }, FloatArray(count) { 1.2345679e-20f }, LongArray(count) { Long.MAX_VALUE })
        val id = SceneId("long-numeric-capacity"); val layer = LayerId("ink")
        val objects = (0 until 10).map { StrokeObject(ObjectId("stroke-$it"), 1, layer,
            BrushSpec(BrushKind.MONOLINE, 1, 2.0, -1), samples) }
        val root = DocumentRoot.compose("title", SceneSnapshot.restored(id, 1, objects),
            listOf(PageDescriptor(PageId("page"), layer, 595.0, 842.0, contentRevision = 1)))
        assertSame(root, PreviewDocumentReader.accept(root).root)
        val note = assertNotNull(PagedPreviewNotebook.open(root, "adopt").notebook)
        val text = note.capture().encode()
        assertTrue(text.length > 16_000_000)
        assertTrue(text.length <= PagedPreviewCodec.MAX_CHARACTERS)
        assertTrue(text.encodeToByteArray().size <= PagedPreviewCodec.MAX_UTF8_BYTES)
        assertTrue(note.capture().encodingWorkByteCharge <= 256L * 1024 * 1024)
        assertTrue(PagedPreviewCodec.maximumDecodingWorkByteCharge() <= 256L * 1024 * 1024)
        val decoded = PagedPreviewCodec.decode(text)
        assertTrue(root.scene.sameContent(decoded.scene)); assertEquals(text, PagedPreviewCodec.encode(decoded))
        assertSame(root, note.snapshot()); assertEquals(200_000, decoded.scene.objects().sumOf { it.samples.size })
    }

    @Test fun legacyBottomRightInkKeepsOriginalPaperExtentAndDecodedCopySharesSamples() {
        val input = "NCPREVIEW\t1\nD\tcorner\tcorner\t2\t1\t1\nS\tink-1\t-1\t2.5\t2\nP\t900\t1300\t0.5\t1\nP\t999\t1399\t0.5\t2\nEND\n"
        val root = PagedPreviewCodec.decode(input)
        assertEquals(1000.0, root.page(0).width); assertEquals(1400.0, root.page(0).height)
        assertEquals(1399.0, root.scene.objects().single().samples.y(1))
        val copy = PagedPreviewCodec.copyAsNew(root, SceneId("new"), mapOf(root.page(0).id to PageId("fresh-page")),
            mapOf(root.page(0).layerId to LayerId("fresh-layer")), mapOf(root.scene.objects().single().id to ObjectId("fresh-object")))
        assertSame(root.scene.objects().single().samples, copy.scene.objects().single().samples)
        assertEquals(root.page(0).width, copy.page(0).width)
    }
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
    private fun legacy(): String = "NCPREVIEW\t1\nD\tlegacy\t\u65e7\u6807\u9898\t2\t1\t2\nS\tink-1\t-1\t2.5\t2\nP\t1\t2\t0.3\t123\nP\t3\t4\t0.8\t456\nEND\n"
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
