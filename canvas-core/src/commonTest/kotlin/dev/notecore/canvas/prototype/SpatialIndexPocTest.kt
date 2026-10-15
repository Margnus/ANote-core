package dev.notecore.canvas.prototype

import kotlin.random.Random
import kotlin.test.*

class SpatialIndexPocTest {
    private fun check(entries: List<SpatialEntry>, queries: List<BoundsD>) {
        val grid = GridIndexPoc().also { index -> entries.forEach(index::put) }
        val tree = PackedRTreePoc(entries)
        queries.forEach { query ->
            val oracle = entries.filter { it.bounds.overlaps(query) }.map { it.id }.toSet()
            assertEquals(oracle, grid.query(query).ids)
            assertEquals(oracle, tree.query(query).ids)
        }
    }

    @Test fun inclusiveEdgesNegativeCellsAndZeroArea() {
        check(listOf(SpatialEntry(1, BoundsD(-32.0, -32.0, 0.0, 0.0)), SpatialEntry(2, BoundsD(32.0, 32.0, 32.0, 32.0))),
            listOf(BoundsD(0.0, 0.0, 32.0, 32.0), BoundsD(-32.0, -32.0, -32.0, -32.0), BoundsD(0.1, 0.1, 31.9, 31.9)))
    }

    @Test fun oversizedAndUnrepresentableCellsFallBackWithoutDroppingObjects() {
        val bounds = BoundsD(-Double.MAX_VALUE, -Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE)
        check(listOf(SpatialEntry(1, bounds), SpatialEntry(2, BoundsD(1e20, 1e20, 1e20, 1e20))),
            listOf(bounds, BoundsD(1e20, 1e20, 1e20, 1e20), BoundsD(0.0, 0.0, 0.0, 0.0)))
        val grid = GridIndexPoc(maxCells = 1)
        grid.put(SpatialEntry(1, bounds)); assertEquals(1, grid.storedReferences)
        assertEquals(1, grid.overflowCount); grid.remove(1)
        assertTrue(grid.query(bounds).ids.isEmpty()); assertEquals(0, grid.storedReferences)
    }

    @Test fun replaceRemoveAndSnapshotIsolation() {
        val entries = mutableListOf(SpatialEntry(1, BoundsD(0.0, 0.0, 1.0, 1.0)))
        val tree = PackedRTreePoc(entries)
        val grid = GridIndexPoc().also { it.put(entries.single()) }
        val moved = SpatialEntry(1, BoundsD(100.0, 100.0, 101.0, 101.0))
        grid.put(moved); entries.clear()
        assertEquals(setOf(1), tree.query(BoundsD(0.0, 0.0, 1.0, 1.0)).ids)
        assertTrue(grid.query(BoundsD(0.0, 0.0, 1.0, 1.0)).ids.isEmpty())
        assertEquals(setOf(1), grid.query(moved.bounds).ids)
        grid.remove(1); grid.remove(1); assertEquals(0, grid.storedReferences)
    }

    @Test fun randomEditsQueriesAgreeWithBruteForce() {
        val random = Random(20261015)
        fun box(): BoundsD {
            val x = random.nextDouble(-200.0, 200.0); val y = random.nextDouble(-200.0, 200.0)
            return BoundsD(x, y, x + random.nextDouble(0.0, 100.0), y + random.nextDouble(0.0, 100.0))
        }
        val entries = mutableMapOf<Int, SpatialEntry>(); val grid = GridIndexPoc(8.0, 32)
        repeat(500) {
            val id = random.nextInt(150)
            if (random.nextInt(4) == 0) { entries.remove(id); grid.remove(id) }
            else { val entry = SpatialEntry(id, box()); entries[id] = entry; grid.put(entry) }
            val query = box(); val oracle = entries.values.filter { it.bounds.overlaps(query) }.map { it.id }.toSet()
            assertEquals(oracle, grid.query(query).ids)
            assertEquals(oracle, PackedRTreePoc(entries.values.toList(), 4).query(query).ids)
        }
    }

    @Test fun overlappingLargeBoxesExplicitlyDegenerateToAllEntries() {
        val entries = (0 until 10000).map { SpatialEntry(it, BoundsD(-1000.0, -1000.0, 1000.0, 1000.0)) }
        val query = BoundsD(0.0, 0.0, 1.0, 1.0)
        val grid = GridIndexPoc().also { entries.forEach(it::put) }
        assertEquals(10000, grid.query(query).entryTests)
        assertEquals(10000, PackedRTreePoc(entries).query(query).entryTests)
        check(entries, listOf(query))
    }

    @Test fun invalidConfigurationDuplicatesAndEmptyInputs() {
        assertFailsWith<IllegalArgumentException> { GridIndexPoc(0.0) }
        assertFailsWith<IllegalArgumentException> { GridIndexPoc(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { PackedRTreePoc(emptyList(), 1) }
        val entry = SpatialEntry(1, BoundsD(0.0, 0.0, 0.0, 0.0))
        assertFailsWith<IllegalArgumentException> { PackedRTreePoc(listOf(entry, entry)) }
        check(emptyList(), listOf(entry.bounds))
    }
}
