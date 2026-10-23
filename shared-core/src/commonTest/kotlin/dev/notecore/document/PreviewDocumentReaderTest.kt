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
}
