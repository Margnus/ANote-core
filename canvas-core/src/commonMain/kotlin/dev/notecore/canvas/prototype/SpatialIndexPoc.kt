package dev.notecore.canvas.prototype

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/** M-3 experiments only: page-space AABBs, inclusive edges, unordered IDs, single owner.
 * Callers must supply conservative painted bounds, including brush coverage and transforms.
 * These indexes neither own scene objects nor perform exact geometry hit testing.
 */
internal data class SpatialObjectBindingPoc(val objectId: ObjectId, val revision: Long, val layerId: LayerId)
internal data class SpatialEntry(val id: Int, val bounds: BoundsD, val binding: SpatialObjectBindingPoc? = null)
internal data class SpatialQuery(val ids: Set<Int>, val entryTests: Int, val nodeTests: Int)
internal fun BoundsD.overlaps(other: BoundsD) =
    left <= other.right && right >= other.left && top <= other.bottom && bottom >= other.top

/** Bounded replication per object/query; oversized ranges use an explicit full/overflow scan.
 * No lossy coordinate clamping: coordinates outside the safe cell range also use fallback.
 */
internal class GridIndexPoc(private val cellSize: Double = 32.0, private val maxCells: Int = 256) {
    init { require(cellSize.isFinite() && cellSize > 0 && maxCells in 1..65536) }
    private data class Cell(val x: Int, val y: Int)
    private val entries = mutableMapOf<Int, SpatialEntry>()
    private val cells = mutableMapOf<Cell, MutableSet<Int>>()
    private val overflow = mutableSetOf<Int>()
    val storedReferences: Int get() = cells.values.sumOf { it.size } + overflow.size
    val overflowCount: Int get() = overflow.size

    private fun keys(bounds: BoundsD): List<Cell>? {
        val values = listOf(bounds.left, bounds.top, bounds.right, bounds.bottom).map { floor(it / cellSize) }
        if (values.any { !it.isFinite() || it < Int.MIN_VALUE.toDouble() || it > Int.MAX_VALUE.toDouble() }) return null
        val x0 = values[0].toInt(); val y0 = values[1].toInt()
        val x1 = values[2].toInt(); val y1 = values[3].toInt()
        val width = x1.toLong() - x0 + 1; val height = y1.toLong() - y0 + 1
        // Check before multiplication/allocation, including the complete signed Int range.
        if (width > maxCells || height > maxCells || width * height > maxCells) return null
        return buildList {
            for (x in x0.toLong()..x1.toLong()) for (y in y0.toLong()..y1.toLong()) add(Cell(x.toInt(), y.toInt()))
        }
    }

    fun put(entry: SpatialEntry) {
        remove(entry.id)
        entries[entry.id] = entry
        val keys = keys(entry.bounds)
        if (keys == null) overflow.add(entry.id)
        else keys.forEach { cells.getOrPut(it) { mutableSetOf() }.add(entry.id) }
    }

    fun remove(id: Int) {
        val previous = entries.remove(id) ?: return
        if (overflow.remove(id)) return
        keys(previous.bounds)!!.forEach { key ->
            cells[key]?.let { ids -> if (ids.remove(id) && ids.isEmpty()) cells.remove(key) }
        }
    }

    fun query(bounds: BoundsD): SpatialQuery {
        val keys = keys(bounds)
        val candidates = if (keys == null) entries.keys else buildSet {
            addAll(overflow)
            keys.forEach { cells[it]?.let(::addAll) }
        }
        return SpatialQuery(candidates.filterTo(mutableSetOf()) { entries.getValue(it).bounds.overlaps(bounds) }, candidates.size, keys?.size ?: 0)
    }
}

/** Immutable STR-packed R-tree candidate. Updates intentionally rebuild a complete snapshot;
 * this PoC measures that cost instead of claiming a dynamic insertion/deletion implementation.
 */
internal class PackedRTreePoc(entries: List<SpatialEntry>, private val fanout: Int = 16) {
    private data class Node(val bounds: BoundsD, val entries: List<SpatialEntry>, val children: List<Node>)
    private val root: Node?
    var nodeCount: Int = 0
        private set
    init {
        require(fanout in 2..128)
        require(entries.map { it.id }.toSet().size == entries.size) { "Duplicate spatial ID" }
        var level = pack(entries.toList(), { it.bounds }) { group -> Node(union(group.map { it.bounds }), group, emptyList()) }
        nodeCount += level.size
        while (level.size > 1) {
            level = pack(level, { it.bounds }) { group -> Node(union(group.map { it.bounds }), emptyList(), group) }
            nodeCount += level.size
        }
        root = level.singleOrNull()
    }

    private fun union(bounds: List<BoundsD>) = bounds.reduce { a, b -> a.union(b) }
    private fun <T> pack(items: List<T>, bounds: (T) -> BoundsD, node: (List<T>) -> Node): List<Node> {
        if (items.isEmpty()) return emptyList()
        val groups = (items.size + fanout - 1) / fanout
        val slices = ceil(sqrt(groups.toDouble())).toInt()
        val sliceSize = ((groups + slices - 1) / slices) * fanout
        // Half first avoids overflow for finite coordinates near Double.MAX_VALUE.
        return items.sortedBy { bounds(it).left / 2 + bounds(it).right / 2 }.chunked(sliceSize).flatMap { slice ->
            slice.sortedBy { bounds(it).top / 2 + bounds(it).bottom / 2 }.chunked(fanout).map(node)
        }
    }

    fun query(bounds: BoundsD): SpatialQuery = checkNotNull(queryWithin(bounds))

    /** A budget failure never exposes a prefix as a complete hit set. Exclusions mask old bounds. */
    fun queryWithin(bounds: BoundsD, maxEntryTests: Int = Int.MAX_VALUE,
        maxNodeTests: Int = Int.MAX_VALUE, maxResults: Int = Int.MAX_VALUE,
        excludedIds: Set<Int> = emptySet(),
    ): SpatialQuery? {
        require(maxEntryTests >= 0 && maxNodeTests >= 0 && maxResults >= 0)
        val ids = mutableSetOf<Int>()
        var entryTests = 0; var nodeTests = 0
        fun visit(node: Node): Boolean {
            if (nodeTests == maxNodeTests) return false
            nodeTests++
            if (!node.bounds.overlaps(bounds)) return true
            for (entry in node.entries) {
                if (entry.id in excludedIds) continue
                if (entryTests == maxEntryTests) return false
                entryTests++
                if (entry.bounds.overlaps(bounds)) {
                    if (ids.size == maxResults) return false
                    ids.add(entry.id)
                }
            }
            for (child in node.children) if (!visit(child)) return false
            return true
        }
        if (root != null && !visit(root)) return null
        return SpatialQuery(ids, entryTests, nodeTests)
    }
}
