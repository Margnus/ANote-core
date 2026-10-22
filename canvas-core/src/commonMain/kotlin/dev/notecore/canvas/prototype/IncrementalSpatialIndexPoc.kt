package dev.notecore.canvas.prototype

/** Owner-supplied content identity, not a viewport token or a new document authority.
 * Undo must advance revision; reopening/replacing an owner must advance sessionGeneration.
 * IDs are stable derived integer handles; adapters must not reuse a live object's handle.
 */
internal data class SpatialContentStampPoc(val sceneId: SceneId, val revision: Long, val sessionGeneration: Long) {
    init { require(revision >= 0 && sessionGeneration >= 0) }
}

internal data class SpatialChangePoc(val before: SpatialEntry?, val after: SpatialEntry?)
internal sealed interface SpatialIndexUpdatePoc {
    data class Ready(val index: IncrementalSpatialIndexPoc) : SpatialIndexUpdatePoc
    data class Rejected(val reason: String) : SpatialIndexUpdatePoc
    data object Cancelled : SpatialIndexUpdatePoc
}
internal sealed interface SpatialIndexQueryPoc {
    data class Ready(val query: SpatialQuery) : SpatialIndexQueryPoc
    data class Rejected(val reason: String) : SpatialIndexQueryPoc
}

/** Immutable STR base + bounded replacement/deletion overlay. Only derived IDs/bounds are retained.
 * No canonical objects/samples, worker, queue, resource pool, persistence or rendering authority.
 * The caller owns publication: recheck canPublish against its CURRENT canonical content stamp.
 * Budgets count records/tests/results, not heap; compaction sorting is not interruptible mid-sort.
 */
internal class IncrementalSpatialIndexPoc private constructor(
    val stamp: SpatialContentStampPoc,
    private val base: Map<Int, SpatialEntry>, private val tree: PackedRTreePoc,
    private val overlay: Map<Int, SpatialEntry?>, val objectCount: Int,
    private val maxObjects: Int, private val maxOverlay: Int, private val maxBatch: Int,
) {
    val overlayCount: Int get() = overlay.size
    val tombstoneCount: Int get() = overlay.values.count { it == null }
    val retainedBoundsCount: Int get() = base.size + overlay.values.count { it != null }
    fun canPublish(current: SpatialContentStampPoc): Boolean = current == stamp
    private fun entry(id: Int): SpatialEntry? = if (overlay.containsKey(id)) overlay[id] else base[id]

    fun advance(expected: SpatialContentStampPoc, next: SpatialContentStampPoc,
        changes: List<SpatialChangePoc>, cancelled: () -> Boolean = { false },
    ): SpatialIndexUpdatePoc {
        fun reject(reason: String) = SpatialIndexUpdatePoc.Rejected(reason)
        if (expected != stamp) return reject("stale-base")
        if (next.sceneId != stamp.sceneId || next.sessionGeneration != stamp.sessionGeneration ||
            stamp.revision == Long.MAX_VALUE || next.revision != stamp.revision + 1) return reject("invalid-next-content")
        if (changes.size > maxBatch) return reject("batch-budget")
        if (cancelled()) return SpatialIndexUpdatePoc.Cancelled
        val seen = mutableSetOf<Int>()
        var count = objectCount
        // Validate the complete batch before copying/publishing an overlay; no prefix application.
        for (change in changes) {
            if (cancelled()) return SpatialIndexUpdatePoc.Cancelled
            val id = change.after?.id ?: change.before?.id ?: return reject("empty-change")
            if (change.before != null && change.before.id != id) return reject("changed-id")
            if (!seen.add(id)) return reject("duplicate-change")
            if (entry(id) != change.before) return reject("stale-entry")
            if (change.before == null) count++
            if (change.after == null) count--
        }
        if (count > maxObjects) return reject("object-budget")
        val updated = overlay.toMutableMap()
        for (change in changes) {
            val id = change.after?.id ?: change.before!!.id
            // Returning to base state (or deleting an overlay-only insert) needs no tombstone.
            if (base[id] == change.after) updated.remove(id) else updated[id] = change.after
        }
        if (updated.size > maxOverlay) return reject("compaction-required")
        if (cancelled()) return SpatialIndexUpdatePoc.Cancelled
        return SpatialIndexUpdatePoc.Ready(IncrementalSpatialIndexPoc(next, base, tree, updated.toMap(),
            count, maxObjects, maxOverlay, maxBatch))
    }

    fun query(current: SpatialContentStampPoc, bounds: BoundsD, maxEntryTests: Int,
        maxNodeTests: Int, maxResults: Int,
    ): SpatialIndexQueryPoc {
        require(maxEntryTests >= 0 && maxNodeTests >= 0 && maxResults >= 0)
        if (!canPublish(current)) return SpatialIndexQueryPoc.Rejected("stale-content")
        val result = tree.queryWithin(bounds, maxEntryTests, maxNodeTests, maxResults, overlay.keys)
            ?: return SpatialIndexQueryPoc.Rejected("query-budget")
        val ids = result.ids.toMutableSet()
        var tests = result.entryTests
        for ((id, replacement) in overlay) {
            if (replacement == null) continue
            if (tests == maxEntryTests) return SpatialIndexQueryPoc.Rejected("query-budget")
            tests++
            if (replacement.bounds.overlaps(bounds)) {
                if (ids.size == maxResults) return SpatialIndexQueryPoc.Rejected("query-budget")
                ids.add(id)
            }
        }
        return SpatialIndexQueryPoc.Ready(SpatialQuery(ids, tests, result.nodeTests))
    }

    /** Existing owner must admit this bounded job and account for old+new snapshots simultaneously.
     * Cancellation checked before/after STR packing, not claimed as native sort interruption.
     */
    fun compact(expected: SpatialContentStampPoc, cancelled: () -> Boolean = { false }): SpatialIndexUpdatePoc {
        if (!canPublish(expected)) return SpatialIndexUpdatePoc.Rejected("stale-content")
        if (cancelled()) return SpatialIndexUpdatePoc.Cancelled
        val live = mutableListOf<SpatialEntry>()
        for ((id, entry) in base) {
            if (cancelled()) return SpatialIndexUpdatePoc.Cancelled
            if (id !in overlay) live.add(entry)
        }
        for (entry in overlay.values) {
            if (cancelled()) return SpatialIndexUpdatePoc.Cancelled
            if (entry != null) live.add(entry)
        }
        val compacted = create(stamp, live, maxObjects, maxOverlay, maxBatch)
        if (cancelled()) return SpatialIndexUpdatePoc.Cancelled
        return SpatialIndexUpdatePoc.Ready(compacted)
    }

    companion object {
        fun create(stamp: SpatialContentStampPoc, entries: List<SpatialEntry>,
            maxObjects: Int = 100_000, maxOverlay: Int = 256, maxBatch: Int = 256,
        ): IncrementalSpatialIndexPoc {
            require(maxObjects in 1..100_000 && maxOverlay in 1..maxObjects && maxBatch in 1..maxOverlay)
            require(entries.size <= maxObjects && entries.map { it.id }.toSet().size == entries.size)
            val base = entries.associateBy { it.id }
            return IncrementalSpatialIndexPoc(stamp, base, PackedRTreePoc(base.values.toList()), emptyMap(),
                base.size, maxObjects, maxOverlay, maxBatch)
        }
    }
}
