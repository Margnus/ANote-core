package dev.notecore.document

import dev.notecore.canvas.prototype.*
import kotlin.test.*

class PdfOutlineRulesTest {
    private val source = PdfSourcePages("ab".repeat(32), 6)
    private val pages = listOf(
        PageDescriptor(PageId("p0"), LayerId("l0"), 595.0, 842.0),
        PageDescriptor(PageId("p1"), LayerId("l1"), 595.0, 842.0),
        PageDescriptor(PageId("p2"), LayerId("l2"), 595.0, 842.0),
        PageDescriptor(PageId("blank"), LayerId("lb"), 595.0, 842.0),
        PageDescriptor(PageId("p3"), LayerId("l3"), 595.0, 842.0),
    )
    private val root = DocumentRoot.compose("PDF", SceneSnapshot.empty(SceneId("pdf")), pages)
    private val catalog = PdfPageCatalog("local", root.id, 0, true, listOf(source), listOf(
        PdfPageRef(PageId("p0"), source.sha256, 0),
        PdfPageRef(PageId("p1"), source.sha256, 1),
        PdfPageRef(PageId("p2"), source.sha256, 2),
        PdfPageRef(PageId("p3"), source.sha256, 5),
    ))

    private fun map(outline: List<PdfOutlineDest>?, revision: Long = 0,
                    cat: PdfPageCatalog = catalog, r: DocumentRoot = root) =
        PdfOutlineRules.map(r, cat, "local", revision, source, outline)

    @Test fun nullOutlineDegradesWithoutErrorAndKeepsCatalogUntouched() {
        val result = map(null)
        assertFalse(result.outlineAvailable)
        assertTrue(result.nodes.isEmpty())
        assertEquals(0, result.skippedCount)
        assertNull(result.failure); assertNull(result.catalogFailure)
        assertEquals(5, root.pageCount); assertEquals(4, catalog.references.size)
    }

    @Test fun flatOutlineMapsToNotePagesWithDisplayAndSourceNumbers() {
        val outline = listOf(
            PdfOutlineDest(0, "Introduction"),
            PdfOutlineDest(1, "Setup"),
            PdfOutlineDest(5, "Appendix"))
        val result = map(outline)
        assertTrue(result.outlineAvailable)
        assertEquals(0, result.skippedCount)
        assertEquals(3, result.nodes.size)
        assertEquals("p0", result.nodes[0].pageId.value)
        assertEquals(1, result.nodes[0].displayNumber)
        assertEquals(1, result.nodes[0].sourceNumber)
        assertEquals("p1", result.nodes[1].pageId.value)
        assertEquals(2, result.nodes[1].displayNumber)
        assertEquals(2, result.nodes[1].sourceNumber)
        assertEquals("p3", result.nodes[2].pageId.value)
        assertEquals(5, result.nodes[2].displayNumber)
        assertEquals(6, result.nodes[2].sourceNumber)
    }

    @Test fun nestedOutlineMapsChildrenRecursivelyWithOwnPageBinding() {
        val outline = listOf(
            PdfOutlineDest(0, "Chapter 1", listOf(
                PdfOutlineDest(1, "Section 1.1"),
                PdfOutlineDest(2, "Section 1.2"))),
            PdfOutlineDest(5, "Chapter 2"))
        val result = map(outline)
        assertTrue(result.outlineAvailable); assertEquals(0, result.skippedCount)
        assertEquals(2, result.nodes.size)
        val ch1 = result.nodes[0]
        assertEquals("p0", ch1.pageId.value); assertEquals(2, ch1.children.size)
        assertEquals("p1", ch1.children[0].pageId.value)
        assertEquals(2, ch1.children[0].displayNumber)
        assertEquals("p2", ch1.children[1].pageId.value)
        assertEquals(3, ch1.children[1].displayNumber)
        val ch2 = result.nodes[1]
        assertEquals("p3", ch2.pageId.value); assertTrue(ch2.children.isEmpty())
    }

    @Test fun duplicateSourcePagesMapToFirstInDisplayOrder() {
        val extra = PageDescriptor(PageId("dup"), LayerId("ld"), 595.0, 842.0)
        val dupRoot = DocumentRoot.compose("dup", SceneSnapshot.empty(root.id), pages + extra)
        val dupCatalog = catalog.copy(references = catalog.references +
            PdfPageRef(PageId("dup"), source.sha256, 0))
        val outline = listOf(PdfOutlineDest(0, "Start"))
        val result = PdfOutlineRules.map(dupRoot, dupCatalog, "local", 0, source, outline)
        assertTrue(result.outlineAvailable); assertEquals(0, result.skippedCount)
        assertEquals(1, result.nodes.size)
        assertEquals("p0", result.nodes[0].pageId.value)
        assertEquals(1, result.nodes[0].displayNumber)
    }

    @Test fun blankPageAndUnmappedSourceEntriesAreSkippedWithCount() {
        val outline = listOf(
            PdfOutlineDest(0, "First"),
            PdfOutlineDest(3, "Blank"),  // blank page has no PdfPageRef
            PdfOutlineDest(4, "Removed"), // source page 4 not referenced
            PdfOutlineDest(5, "Last"))
        val result = map(outline)
        assertTrue(result.outlineAvailable)
        assertEquals(2, result.skippedCount)
        assertEquals(2, result.nodes.size)
        assertEquals("p0", result.nodes[0].pageId.value)
        assertEquals("p3", result.nodes[1].pageId.value)
    }

    @Test fun invalidTitleAndOutOfRangeDestAreSkippedWithoutPartialCorruption() {
        val outline = listOf(
            PdfOutlineDest(0, "Valid"),
            PdfOutlineDest(0, ""),
            PdfOutlineDest(0, "x".repeat(121)),
            PdfOutlineDest(0, "\uD800"),
            PdfOutlineDest(-1, "Negative"),
            PdfOutlineDest(6, "Beyond"))
        val result = map(outline)
        assertTrue(result.outlineAvailable)
        assertEquals(5, result.skippedCount)
        assertEquals(1, result.nodes.size)
        assertEquals("Valid", result.nodes[0].title)
    }

    @Test fun exceededEntryCapacityRejectsEntireOutlineWithoutPartialNodes() {
        val many = List(PdfOutlineRules.MAX_ENTRIES + 1) { PdfOutlineDest(0, "Entry$it") }
        val result = map(many)
        assertNull(result.nodes.orEmpty().takeIf { it.isNotEmpty() })
        assertTrue(result.nodes.isEmpty())
        assertEquals(PdfOutlineFailure.CAPACITY, result.failure)
        assertFalse(result.outlineAvailable)
    }

    @Test fun staleOrInvalidCatalogRejectsEntireOutline() {
        val outline = listOf(PdfOutlineDest(0, "Chapter"))
        for ((rev, cat, expected) in listOf(
            Triple(0L, catalog, null as PdfPageCatalogFailure?),
            Triple(0L, catalog.copy(spaceId = "other"), PdfPageCatalogFailure.WRONG_SPACE),
            Triple(0L, catalog.copy(revision = 5), PdfPageCatalogFailure.STALE_METADATA),
            Triple(0L, catalog.copy(complete = false), PdfPageCatalogFailure.INCOMPLETE))) {
            val result = map(outline, revision = rev, cat = cat)
            if (expected != null) {
                assertTrue(result.nodes.isEmpty())
                assertEquals(expected, result.catalogFailure)
                assertEquals(PdfOutlineFailure.INVALID_CATALOG, result.failure)
                assertFalse(result.outlineAvailable)
            } else {
                assertTrue(result.outlineAvailable); assertEquals(0, result.skippedCount)
                assertEquals(1, result.nodes.size)
            }
        }
    }

    @Test fun deepNestingBeyondLimitIsSkippedNotRejected() {
        var leaf = PdfOutlineDest(0, "Deep")
        for (depth in 2..PdfOutlineRules.MAX_DEPTH + 3) {
            leaf = PdfOutlineDest(0, "Level$depth", listOf(leaf))
        }
        val result = map(listOf(leaf))
        assertTrue(result.outlineAvailable)
        assertTrue(result.skippedCount > 0)
        assertEquals(1, result.nodes.size)
        assertTrue(result.nodes[0].children.size < 2)
    }

    @Test fun callerListMutationCannotChangeMappedResult() {
        val child = PdfOutlineDest(1, "Child")
        val outline = mutableListOf(PdfOutlineDest(0, "Parent", listOf(child)))
        val result = map(outline)
        outline.clear(); child.copy(title = "mutated")
        assertEquals(1, result.nodes.size)
        assertEquals("Parent", result.nodes[0].title)
        assertEquals(1, result.nodes[0].children.size)
        assertEquals("Child", result.nodes[0].children[0].title)
        assertEquals(0, result.skippedCount)
    }
}
