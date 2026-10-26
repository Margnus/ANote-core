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
internal data class SpatialPointBudgetPoc(val entryTests: Int = 100_000, val nodeTests: Int = 100_000,
    val candidates: Int = 1024, val sourceSamples: Int = 200_000, val hits: Int = 128) {
    init { require(entryTests >= 0 && nodeTests >= 0 && candidates >= 0 && sourceSamples in 0..2_000_000 && hits >= 0) }
}
internal sealed interface SpatialPointQueryPoc {
    data class Ready(val stamp: SpatialContentStampPoc, val hits: Map<Int, ObjectId>, val candidateCount: Int,
        val entryTests: Int, val nodeTests: Int, val sourceSamples: Int) : SpatialPointQueryPoc
    data class Rejected(val reason: String) : SpatialPointQueryPoc
    data object Cancelled : SpatialPointQueryPoc
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
    private val canonicalBindings: Boolean = false,
) {
    val overlayCount: Int get() = overlay.size
    val tombstoneCount: Int get() = overlay.values.count { it == null }
    val retainedBoundsCount: Int get() = base.size + overlay.values.count { it != null }
    fun canPublish(current: SpatialContentStampPoc): Boolean = current == stamp
    private fun entry(id: Int): SpatialEntry? = if (overlay.containsKey(id)) overlay[id] else base[id]

    fun advance(expected: SpatialContentStampPoc, next: SpatialContentStampPoc,
        changes: List<SpatialChangePoc>, cancelled: () -> Boolean = { false },
    ): SpatialIndexUpdatePoc = advanceInternal(expected, next, changes, cancelled, false)

    private fun advanceInternal(expected: SpatialContentStampPoc, next: SpatialContentStampPoc,
        changes: List<SpatialChangePoc>, cancelled: () -> Boolean, bound: Boolean,
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
            count, maxObjects, maxOverlay, maxBatch, bound))
    }

    /** Consume the owner's actual immutable before/after roots and prepared patch. Stable handles
     * are owner-supplied, never hash codes or draw-order positions. No canonical state is published here.
     */
    fun advanceCanonical(expected: SpatialContentStampPoc, before: SceneSnapshot, after: SceneSnapshot,
        patch: ScenePatch, handle: (ObjectId) -> Int?, maxSourceSamples: Int = 400_000, maxObjectTests: Int = 100_000,
        cancelled: () -> Boolean = { false }): SpatialIndexUpdatePoc {
        require(maxSourceSamples in 0..2_000_000)
        require(maxObjectTests in 0..100_000)
        fun reject(reason: String) = SpatialIndexUpdatePoc.Rejected(reason)
        if (!canonicalBindings) return reject("unbound-index")
        if (expected != stamp || before.id != stamp.sceneId || before.revision != stamp.revision || before.objectCount != objectCount) return reject("stale-base")
        if (stamp.revision == Long.MAX_VALUE || after.id != before.id || after.revision != before.revision + 1 ||
            patch.sceneId != before.id || patch.baseRevision != before.revision) return reject("invalid-next-content")
        if (before.objectCount > maxObjectTests) return reject("metadata-budget")
        if (after.objectCount > maxObjects) return reject("object-budget")
        if (patch.changeCount > maxBatch) return reject("batch-budget")
        if (cancelled()) return SpatialIndexUpdatePoc.Cancelled
        val changes = ArrayList<SpatialChangePoc>(patch.changeCount)
        val changedIds = mutableSetOf<ObjectId>()
        var samples = 0
        var count = before.objectCount
        for (change in patch.changes()) {
            if (cancelled()) return SpatialIndexUpdatePoc.Cancelled
            val id = change.after?.id ?: change.before?.id ?: return reject("empty-change")
            if (!changedIds.add(id)) return reject("duplicate-change")
            val sourceCost = (change.before?.samples?.size ?: 0).toLong() + (change.after?.samples?.size ?: 0)
            if (sourceCost > maxSourceSamples - samples) return reject("sample-budget")
            samples += sourceCost.toInt()
            if (before.find(id) != change.before || after.find(id) !== change.after ||
                change.before?.id?.let { it != id } == true) return reject("noncanonical-change")
            val key = handle(id) ?: return reject("missing-handle")
            val previous = entry(key)
            if (change.before == null) {
                if (previous != null) return reject("reused-handle")
                count++
            } else if (previous?.binding != binding(change.before)) return reject("stale-binding")
            if (change.after == null) count--
            val replacement = change.after?.let { obj ->
                when (val built = BrushEnvelopePoc.build(obj.samples, obj.brush, obj.transform, obj.samples.size)) {
                    is BrushEnvelopeBuild.Rejected -> return reject("geometry-${built.reason}")
                    is BrushEnvelopeBuild.Ready -> SpatialEntry(key, built.envelope.paintedBounds, binding(obj))
                }
            }
            changes.add(SpatialChangePoc(previous, replacement))
        }
        if (count != after.objectCount) return reject("noncanonical-count")
        // A real reducer patch shares all unaffected immutable objects. Check those references
        // without creating another canonical scene or scanning their point arrays.
        var unchanged = true
        before.forEachObject { obj -> if (obj.id !in changedIds && after.find(obj.id) !== obj) unchanged = false }
        if (!unchanged) return reject("noncanonical-unchanged")
        if (cancelled()) return SpatialIndexUpdatePoc.Cancelled
        return advanceInternal(expected, stamp.copy(revision = after.revision), changes, cancelled, true)
    }

    /** Bounded geometric filtering, not z order, lasso, AA-pixel coverage or an App edit receipt.
     * Only validated canonical construction/update may authorize this path; generic bbox updates revoke it.
     * Build/contain calls are bounded by sourceSamples, cancellation is observed between those calls.
     */
    fun queryPoint(scene: SceneSnapshot, sessionGeneration: Long, layerId: LayerId, point: PointD,
        budget: SpatialPointBudgetPoc = SpatialPointBudgetPoc(), cancelled: () -> Boolean = { false }): SpatialPointQueryPoc {
        fun reject(reason: String) = SpatialPointQueryPoc.Rejected(reason)
        if (!canonicalBindings) return reject("unbound-index")
        if (scene.id != stamp.sceneId || scene.revision != stamp.revision || sessionGeneration != stamp.sessionGeneration)
            return reject("stale-content")
        if (cancelled()) return SpatialPointQueryPoc.Cancelled
        val broad = when (val result = query(stamp, BoundsD(point.x, point.y, point.x, point.y),
            budget.entryTests, budget.nodeTests, budget.candidates)) {
            is SpatialIndexQueryPoc.Rejected -> return reject(result.reason)
            is SpatialIndexQueryPoc.Ready -> result.query
        }
        val hits = mutableMapOf<Int, ObjectId>()
        var samples = 0
        for (key in broad.ids) {
            if (cancelled()) return SpatialPointQueryPoc.Cancelled
            val bound = entry(key)?.binding ?: return reject("missing-binding")
            val obj = scene.find(bound.objectId) ?: return reject("missing-object")
            if (binding(obj) != bound) return reject("stale-binding")
            if (obj.layerId != layerId) continue
            if (obj.samples.size > budget.sourceSamples - samples) return reject("sample-budget")
            samples += obj.samples.size
            val envelope = when (val built = BrushEnvelopePoc.build(obj.samples, obj.brush, obj.transform, obj.samples.size)) {
                is BrushEnvelopeBuild.Rejected -> return reject("geometry-${built.reason}")
                is BrushEnvelopeBuild.Ready -> built.envelope
            }
            if (cancelled()) return SpatialPointQueryPoc.Cancelled
            if (envelope.containsScene(point)) {
                if (hits.size == budget.hits) return reject("hit-budget")
                hits[key] = obj.id
            }
        }
        if (cancelled()) return SpatialPointQueryPoc.Cancelled
        return SpatialPointQueryPoc.Ready(stamp, hits.toMap(), broad.ids.size, broad.entryTests, broad.nodeTests, samples)
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
        val compacted = createEntries(stamp, live, maxObjects, maxOverlay, maxBatch, canonicalBindings)
        if (cancelled()) return SpatialIndexUpdatePoc.Cancelled
        return SpatialIndexUpdatePoc.Ready(compacted)
    }

    companion object {
        private fun binding(obj: StrokeObject) = SpatialObjectBindingPoc(obj.id, obj.objectRevision, obj.layerId)
        /** Validate ALL source geometry before creating the original derived index. Unsupported objects
         * outside a later query cannot silently disappear. No samples/oracles are retained by entries.
         */
        fun createCanonical(scene: SceneSnapshot, sessionGeneration: Long, handle: (ObjectId) -> Int?,
            maxObjects: Int = 100_000, maxOverlay: Int = 256, maxBatch: Int = 256,
            maxSourceSamples: Int = 1_000_000, cancelled: () -> Boolean = { false }): SpatialIndexUpdatePoc {
            require(maxSourceSamples in 0..2_000_000)
            require(maxObjects in 1..100_000 && maxOverlay in 1..maxObjects && maxBatch in 1..maxOverlay)
            val stamp = SpatialContentStampPoc(scene.id, scene.revision, sessionGeneration)
            if (scene.objectCount > maxObjects) return SpatialIndexUpdatePoc.Rejected("object-budget")
            if (cancelled()) return SpatialIndexUpdatePoc.Cancelled
            val entries = ArrayList<SpatialEntry>(scene.objectCount)
            val handles = mutableSetOf<Int>()
            var samples = 0; var failure: String? = null; var stopped = false
            // The immutable visitor may finish its bounded metadata traversal after cancellation;
            // no further geometry/binding work is performed once stopped.
            scene.forEachObject { obj ->
                if (failure != null || stopped) return@forEachObject
                if (cancelled()) { stopped = true; return@forEachObject }
                if (obj.samples.size > maxSourceSamples - samples) { failure = "sample-budget"; return@forEachObject }
                samples += obj.samples.size
                val key = handle(obj.id)
                if (key == null) { failure = "missing-handle"; return@forEachObject }
                if (!handles.add(key)) { failure = "duplicate-handle"; return@forEachObject }
                when (val built = BrushEnvelopePoc.build(obj.samples, obj.brush, obj.transform, obj.samples.size)) {
                    is BrushEnvelopeBuild.Rejected -> failure = "geometry-${built.reason}"
                    is BrushEnvelopeBuild.Ready -> entries.add(SpatialEntry(key, built.envelope.paintedBounds, binding(obj)))
                }
            }
            if (stopped || cancelled()) return SpatialIndexUpdatePoc.Cancelled
            failure?.let { return SpatialIndexUpdatePoc.Rejected(it) }
            val result = createEntries(stamp, entries, maxObjects, maxOverlay, maxBatch, true)
            if (cancelled()) return SpatialIndexUpdatePoc.Cancelled
            return SpatialIndexUpdatePoc.Ready(result)
        }
        fun create(stamp: SpatialContentStampPoc, entries: List<SpatialEntry>,
            maxObjects: Int = 100_000, maxOverlay: Int = 256, maxBatch: Int = 256,
        ): IncrementalSpatialIndexPoc = createEntries(stamp, entries, maxObjects, maxOverlay, maxBatch, false)

        private fun createEntries(stamp: SpatialContentStampPoc, entries: List<SpatialEntry>, maxObjects: Int,
            maxOverlay: Int, maxBatch: Int, bound: Boolean): IncrementalSpatialIndexPoc {
            require(maxObjects in 1..100_000 && maxOverlay in 1..maxObjects && maxBatch in 1..maxOverlay)
            require(entries.size <= maxObjects && entries.map { it.id }.toSet().size == entries.size)
            val base = entries.associateBy { it.id }
            return IncrementalSpatialIndexPoc(stamp, base, PackedRTreePoc(base.values.toList()), emptyMap(),
                base.size, maxObjects, maxOverlay, maxBatch, bound)
        }
    }
}
