package dev.notecore.document

import dev.notecore.canvas.prototype.*
import kotlin.test.*

/** Fixed v1 bytes exercise the shipping read-only migration; no retired editor/writer is linked. */
class LegacyPreviewReaderTest {
    private val input = "NCPREVIEW\t1\nD\tlegacy\t旧\\t标题😀\t3\t2\t7\nS\tink-2\t-12345\t9.0\t2\nP\t900.0\t1300.0\t0.3\t123\nP\t999.0\t1399.0\t0.8\t456\nS\tink-1\t-1\t2.5\t1\nP\t1.0\t2.0\t0.5\t789\nEND\n"
    private fun bad(text: String) { assertFailsWith<IllegalArgumentException> { PagedPreviewCodec.decode(text) } }
    @Test fun preservesEscapedUnicodeMetadataAndCanonicalDrawOrder() {
        val root = PagedPreviewCodec.decode(input)
        assertEquals("旧\t标题😀", root.title); assertEquals(7L, root.revision)
        assertEquals(listOf("ink-2", "ink-1"), root.scene.objects().map { it.id.value })
        assertEquals(7L, root.page(0).contentRevision); assertEquals(1000.0, root.page(0).width); assertEquals(1400.0, root.page(0).height)
    }
    @Test fun realTimePressureColorAndWidthSurviveReadOnlyMigration() {
        val stroke = PagedPreviewCodec.decode(input).scene.objects().first()
        assertEquals(123L, stroke.samples.timeMicros(0)); assertEquals(456L, stroke.samples.timeMicros(1))
        assertEquals(0.8f, stroke.samples.pressure(1)); assertEquals(1399.0, stroke.samples.y(1))
        assertEquals(-12345, stroke.brush.argb); assertEquals(9.0, stroke.brush.width)
    }
    @Test fun decoderReturnsOnlyImmutableRootAndAllFutureWritesAreV2() {
        val root = PagedPreviewCodec.decode(input); val before = root.scene.objects()
        val note = PagedPreviewNotebook(root, "migrated-session")
        assertTrue(note.rename("edited")); assertTrue(note.capture().encode().startsWith("NCPREVIEW\t2\n"))
        assertEquals("旧\t标题😀", root.title); assertEquals(before, root.scene.objects()); assertEquals(7L, root.revision)
    }
    @Test fun legacyMetadataIdentityCounterIsValidatedButNeverCreatesAnotherOwner() {
        bad(input.replace("\t3\t2\t7", "\t2\t2\t7"))
        bad(input.replace("\t3\t2\t7", "\t0\t2\t7"))
        bad(input.replace("S\tink-2", "S\tink-02")); bad(input.replace("S\tink-2", "S\tink--2"))
    }
    @Test fun repeatedAndOrphanIdentitiesCannotEnterMigratedScene() {
        bad(input.replace("S\tink-1", "S\tink-2")); bad(input.replace("D\tlegacy", "D\t"))
    }
    @Test fun truncationUnknownVersionFooterAndTrailingDataAreRejected() {
        bad(input.replace("NCPREVIEW\t1", "NCPREVIEW\t9")); bad(input.substringBefore("END"))
        bad(input + "unexpected\n"); bad(input + "\n")
    }
    @Test fun invalidRealSamplesAndTimeRegressionAreRejected() {
        bad(input.replace("900.0", "NaN")); bad(input.replace("\t0.3\t123", "\t1.3\t123"))
        bad(input.replace("\t0.8\t456", "\t0.8\t122")); bad(input.replace("\t123", "\t-1"))
        bad(input.replace("\t9.0\t2", "\tInfinity\t2"))
    }
    @Test fun strokeAndDocumentClaimsRejectBeforeReadingMissingPayload() {
        bad("NCPREVIEW\t1\nD\tlegacy\ttitle\t2\t501\t0\n")
        bad("NCPREVIEW\t1\nD\tlegacy\ttitle\t2\t1\t0\nS\tink-1\t-1\t2.5\t20001\n")
        bad("NCPREVIEW\t1\nD\tlegacy\ttitle\t2\t1\t0\nS\tink-1\t-1\t2.5\t2147483647\n")
    }
    @Test fun malformedFieldsAndOversizedLinesRejectBeforeSplitAllocation() {
        bad(input.replace("S\tink-2", "S\t\tink-2"))
        bad("NCPREVIEW\t1\nD\t" + "\t".repeat(100_000))
        bad(input.replace("标题", "x".repeat(1025)))
    }
    @Test fun individuallyValidLegacyStrokesCannotBypassAggregatePointCapacity() {
        val text = buildString {
            append("NCPREVIEW\t1\nD\tcapacity\ttitle\t12\t11\t0\n")
            repeat(11) { index ->
                append("S\tink-${index + 1}\t-1\t2.5\t20000\n")
                if (index < 10) repeat(20_000) { append("P\t1\t2\t0.5\t1\n") }
            }
            append("END\n")
        }
        bad(text) // Stroke eleven has no payload; reject its total claim before reading/allocating it.
        val limit = (text.substringBefore("S\tink-11") + "END\n").replace("\t12\t11\t0\n", "\t12\t10\t0\n")
        assertEquals(200_000, PagedPreviewCodec.decode(limit).scene.objects().sumOf { it.samples.size })
    }
    @Test fun malformedEscapesAndSurrogateTextAreRejected() {
        bad(input.replace("旧", "\\q")); bad(input.replace("旧", "\uD800"))
        bad(input.replace("旧", "\uDC00")); bad(input.replace("旧", "raw\rtext"))
    }
    @Test fun crlfAndNoFinalTerminatorReadIdenticallyWithoutWritingInput() {
        val root = PagedPreviewCodec.decode(input)
        assertEquals(PagedPreviewCodec.encode(root), PagedPreviewCodec.encode(PagedPreviewCodec.decode(input.replace("\n", "\r\n"))))
        assertEquals(root.scene.objects(), PagedPreviewCodec.decode(input.removeSuffix("\n")).scene.objects())
    }
    @Test fun boundedHeaderDoesNotPretendThePayloadWasValidated() {
        val header = PagedPreviewCodec.header(input.lineSequence().take(2).joinToString("\n"))
        assertEquals(2, header.objectCount); assertEquals(1, header.formatVersion); assertEquals(7L, header.revision)
        assertFailsWith<IllegalArgumentException> { PagedPreviewCodec.header("x".repeat(4097)) }
        bad(input.lineSequence().take(2).joinToString("\n"))
    }
}
