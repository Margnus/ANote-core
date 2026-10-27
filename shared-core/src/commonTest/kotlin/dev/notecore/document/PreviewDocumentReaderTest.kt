package dev.notecore.document

import kotlin.test.*

class PreviewDocumentReaderTest {
    private fun text(): String {
        val note = PagedPreviewNotebook.create("reader", "title", "reader-session")
        assertTrue(note.beginStroke(1.0, 2.0, .3f, 1)); assertTrue(note.endStroke(2.0, 3.0, .7f, 2))
        return note.capture().encode()
    }
    private fun refused(text: String, failure: PreviewReadFailure) {
        val result = PreviewDocumentReader.read(text)
        assertNull(result.root); assertEquals(failure, result.failure)
    }
    @Test fun supportedCanonicalContentIsUnchangedAndExistingRootIsNotCloned() {
        val text = text(); val read = PreviewDocumentReader.read(text)
        assertNull(read.failure); val root = assertNotNull(read.root)
        assertEquals(text, PagedPreviewCodec.encode(root))
        assertNotNull(PreviewDocumentReader.read(text.removeSuffix("\n")).root) // final newline is optional
        assertNotNull(PreviewDocumentReader.read(text.replace("\n", "\r\n")).root)
        assertSame(root, PreviewDocumentReader.accept(root).root)
    }
    @Test fun oldReadOnlyFormatStillMigratesThroughTheSameReader() {
        val source = "NCPREVIEW\t1\nD\tlegacy\ttitle\t2\t1\t1\nS\tink-1\t-1\t2.5\t1\nP\t1.0\t2.0\t0.5\t1\nEND\n"
        val result = PreviewDocumentReader.read(source)
        assertNull(result.failure); assertEquals(1, assertNotNull(result.root).scene.objectCount)
        assertTrue(PagedPreviewCodec.encode(result.root!!).startsWith("NCPREVIEW\t2\n"))
    }
    @Test fun malformedVersionsTruncationAndScalarsReturnRefusalWithoutPartialRoot() {
        val good = text()
        for (bad in listOf("", "NCPREVIEW\t9\n", good.substringBefore("END"), good + "trailing",
            good.replace("MONOLINE", "unknown"), good.replace("0.3", "NaN"), good.replace("P\t1.0", "P\tInfinity"),
            good.replace("title", "bad\uD800")))
            refused(bad, PreviewReadFailure.INVALID_OR_UNSUPPORTED_FORMAT)
        for (cut in 0 until good.indexOf("END") + 3) refused(good.substring(0, cut), PreviewReadFailure.INVALID_OR_UNSUPPORTED_FORMAT)
    }
    @Test fun validCanonicalBrushesUnsupportedByPreviewAreRefusedWithoutLossyConversion() {
        val original = text()
        for (brush in listOf("PRESSURE", "HIGHLIGHTER")) {
            val encoded = original.replace("MONOLINE", brush)
            val canonical = PagedPreviewCodec.decode(encoded)
            assertEquals(brush, canonical.scene.objects().single().brush.kind.name)
            refused(encoded, PreviewReadFailure.UNSUPPORTED_PREVIEW_CONTENT)
            val refused = PreviewDocumentReader.accept(canonical)
            assertNull(refused.root); assertEquals(PreviewReadFailure.UNSUPPORTED_PREVIEW_CONTENT, refused.failure)
            assertEquals(encoded, PagedPreviewCodec.encode(canonical))
        }
    }
    @Test fun characterCapacityRefusesBeforeParsingAndDoesNotAffectLaterReads() {
        refused("x".repeat(PagedPreviewCodec.MAX_CHARACTERS + 1), PreviewReadFailure.CHARACTER_CAPACITY)
        assertNotNull(PreviewDocumentReader.read(text()).root)
    }
    @Test fun canonicalWidthsAndTransformsThatDegenerateInFloatAreRefusedBeforeAdoption() {
        val original = text()
        val root = assertNotNull(PreviewDocumentReader.read(original).root)
        val obj = root.scene.objects().single()
        val transforms = listOf(
            dev.notecore.canvas.prototype.Affine2D(a=1e-50, d=1e-50),
            dev.notecore.canvas.prototype.Affine2D(a=1.0, b=1.0, c=1.0, d=1.00000001))
        val unsupported = listOf(1e-50, Float.MIN_VALUE.toDouble()).map { obj.copy(brush=obj.brush.copy(width=it)) } +
            transforms.map { obj.copy(transform=it) }
        for (stroke in unsupported) {
            val canonical = DocumentRoot.compose(root.title, dev.notecore.canvas.prototype.SceneSnapshot.restored(
                root.id, root.revision, listOf(stroke)), root.pages())
            val encoded = PagedPreviewCodec.encode(canonical)
            assertEquals(encoded, PagedPreviewCodec.encode(PagedPreviewCodec.decode(encoded)))
            refused(encoded, PreviewReadFailure.UNSUPPORTED_PREVIEW_CONTENT)
            assertEquals(PreviewReadFailure.UNSUPPORTED_PREVIEW_CONTENT, PreviewDocumentReader.accept(canonical).failure)
            val opened = PagedPreviewNotebook.open(canonical, "refused")
            assertNull(opened.notebook); assertEquals(PreviewOpenFailure.UNSUPPORTED_CONTENT, opened.failure)
            assertEquals(encoded, PagedPreviewCodec.encode(canonical))
        }
        assertEquals(original, PagedPreviewCodec.encode(root))
    }
    @Test fun supportedFloatWidthsAndReflectedShearRemainCanonicalAndUnchanged() {
        val root = assertNotNull(PreviewDocumentReader.read(text()).root)
        val obj = root.scene.objects().single()
        for (width in listOf(.5, 2.5, 128.0)) {
            val stroke = obj.copy(brush=obj.brush.copy(width=width), transform=
                dev.notecore.canvas.prototype.Affine2D(a=-1.0, b=.25, c=.5, d=1.0, tx=100.0, ty=-50.0))
            val canonical = DocumentRoot.compose(root.title, dev.notecore.canvas.prototype.SceneSnapshot.restored(
                root.id, root.revision, listOf(stroke)), root.pages())
            assertSame(canonical, PreviewDocumentReader.accept(canonical).root)
            assertEquals(PagedPreviewCodec.encode(canonical), PagedPreviewCodec.encode(PreviewDocumentReader.read(PagedPreviewCodec.encode(canonical)).root!!))
        }
    }

}
