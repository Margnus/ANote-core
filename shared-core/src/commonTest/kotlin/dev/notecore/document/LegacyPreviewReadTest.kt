package dev.notecore.document

import dev.notecore.canvas.preview.PreviewNotebook
import kotlin.test.*

class LegacyPreviewReadTest {
    // Fixed historical bytes exercise the reader independently of the test-only v1 writer.
    private val bytes = "NCPREVIEW\t1\nD\tlegacy\t旧标题\\t😀\t3\t1\t7\n" +
        "S\tink-2\t-1\t2.5\t2\nP\t900.0\t1300.0\t0.3\t1\nP\t999.0\t1399.0\t0.8\t2\nEND\n"

    @Test fun fixedHistoricalBytesMigrateToCanonicalRootAndOnlyEncodeV2() {
        val root = PagedPreviewCodec.decode(bytes)
        assertEquals("legacy", root.id.value); assertEquals("旧标题\t😀", root.title)
        assertEquals(7L, root.revision); assertEquals(7L, root.page(0).contentRevision)
        assertEquals(1000.0, root.page(0).width); assertEquals(1400.0, root.page(0).height)
        val stroke = root.scene.objects().single()
        assertEquals("ink-2", stroke.id.value); assertEquals(0.8f, stroke.samples.pressure(1))
        assertEquals(2L, stroke.samples.timeMicros(1)); assertEquals(1399.0, stroke.samples.y(1))
        val encoded = PagedPreviewCodec.encode(root)
        assertTrue(encoded.startsWith("NCPREVIEW\t2\n"))
        assertEquals(root.scene.objects(), PagedPreviewCodec.decode(encoded).scene.objects())
        assertEquals(root.scene.objects(), PagedPreviewCodec.decode(bytes.replace("\n", "\r\n")).scene.objects())
    }

    @Test fun readerMatchesHistoricalProducerAcrossHolesBrushesTitlesAndEmptyPages() {
        val old = PreviewNotebook("compat", "标题\\\t\n😀")
        repeat(5) { index ->
            old.selectPen(-index - 1, index + 0.5)
            assertTrue(old.beginStroke(index.toDouble(), -1.0, 0.2f, 1))
            assertTrue(old.endStroke(index + 0.25, 3.0, 0.9f, 2))
        }
        assertTrue(old.undo()); assertTrue(old.undo())
        val captured = old.capture(); val root = PagedPreviewCodec.decode(captured.encode())
        assertEquals(captured.canonicalScene().objects(), root.scene.objects())
        assertEquals(captured.version, root.revision); assertEquals(captured.title, root.title)
        val empty = PagedPreviewCodec.decode(PreviewNotebook("empty", "empty").capture().encode())
        assertEquals(1, empty.pageCount); assertEquals(0, empty.scene.objectCount)
    }

    @Test fun malformedLegacyIdentityPayloadAndTrailingRowsAreRejected() {
        listOf(bytes.replace("\t3\t1\t7", "\t2\t1\t7"), bytes.replace("ink-2", "ink-02"),
            bytes.replace("\t0.3\t1", "\tNaN\t1"), bytes.replace("\t0.8\t2", "\t0.8\t0"),
            bytes.replace("END\n", ""), bytes + "extra", bytes + "\n",
            bytes.replace("旧标题\\t😀", "x".repeat(121)), bytes.replace("legacy", " "),
            bytes.replace("\t2.5\t2", "\t2.5\t20001"), bytes.replace("\t3\t1\t7", "\t3\t501\t7"),
            bytes.replace("\t900.0\t", "\t" + "1".repeat(1025) + "\t")
        ).forEach { text -> assertFailsWith<IllegalArgumentException> { PagedPreviewCodec.decode(text) } }
    }

    @Test fun legacyHeaderIsBoundedAndNeverTreatsPayloadAsValidated() {
        val text = bytes.lineSequence().take(2).joinToString("\n") + "\nBROKEN"
        val header = PagedPreviewCodec.header(text)
        assertEquals(1, header.formatVersion); assertEquals(1, header.objectCount)
        assertEquals(7L, header.revision); assertEquals("旧标题\t😀", header.title)
        assertFailsWith<IllegalArgumentException> { PagedPreviewCodec.decode(text) }
    }
}
