package dev.notecore.canvas.prototype

import kotlin.random.Random
import kotlin.test.*

class IncrementalSpatialIndexPocTest {
    private val stamp = SpatialContentStampPoc(SceneId("incremental"), 0, 1)
    private val all = BoundsD(-1e30, -1e30, 1e30, 1e30)
    private fun box(id: Int, x: Double = id.toDouble()) = SpatialEntry(id, BoundsD(x, x, x + 1, x + 1))
    private fun ready(result: SpatialIndexUpdatePoc) = assertIs<SpatialIndexUpdatePoc.Ready>(result).index
    private fun ids(index: IncrementalSpatialIndexPoc, bounds: BoundsD = all) =
        assertIs<SpatialIndexQueryPoc.Ready>(index.query(index.stamp, bounds, 100_000, 100_000, 100_000)).query.ids
    private fun change(index: IncrementalSpatialIndexPoc, vararg changes: SpatialChangePoc) =
        ready(index.advance(index.stamp, index.stamp.copy(revision = index.stamp.revision + 1), changes.toList()))

    @Test fun moveDeleteUndoMasksOldBoundsAndPreservesSnapshots() {
        val a = box(1); val b = box(2); val moved = box(1, 100.0)
        val old = IncrementalSpatialIndexPoc.create(stamp, listOf(a, b))
        val next = change(old, SpatialChangePoc(a, moved), SpatialChangePoc(b, null))
        assertTrue(ids(next, a.bounds).isEmpty()); assertEquals(setOf(1), ids(next, moved.bounds))
        assertEquals(setOf(1, 2), ids(old)); assertEquals(1, next.tombstoneCount)
        val undo = change(next, SpatialChangePoc(moved, a), SpatialChangePoc(null, b))
        assertEquals(0, undo.overlayCount); assertEquals(setOf(1, 2), ids(undo))
        assertFalse(undo.canPublish(old.stamp)); assertEquals(2L, undo.stamp.revision)
    }

    @Test fun staleBaseSceneSessionAndRevisionExhaustionCannotPublish() {
        val index = IncrementalSpatialIndexPoc.create(stamp, listOf(box(1)))
        for (wrong in listOf(stamp.copy(sceneId = SceneId("other")), stamp.copy(sessionGeneration = 2), stamp.copy(revision = 1))) {
            assertIs<SpatialIndexUpdatePoc.Rejected>(index.advance(wrong, wrong.copy(revision = 2), emptyList()))
            assertIs<SpatialIndexUpdatePoc.Rejected>(index.advance(stamp, wrong.copy(revision = 2), emptyList()))
            assertIs<SpatialIndexQueryPoc.Rejected>(index.query(wrong, all, 100, 100, 100))
            assertFalse(index.canPublish(wrong))
        }
        val latest = change(index)
        val late = ready(index.compact(stamp))
        assertFalse(late.canPublish(latest.stamp))
        val exhausted = IncrementalSpatialIndexPoc.create(stamp.copy(revision = Long.MAX_VALUE), emptyList())
        assertIs<SpatialIndexUpdatePoc.Rejected>(exhausted.advance(exhausted.stamp, stamp, emptyList()))
    }

    @Test fun invalidBatchNeverAppliesItsValidPrefix() {
        val a = box(1); val b = box(2)
        val index = IncrementalSpatialIndexPoc.create(stamp, listOf(a, b))
        val prefix = SpatialChangePoc(a, box(1, 99.0))
        val invalid = listOf(SpatialChangePoc(box(2, 30.0), null), SpatialChangePoc(null, b),
            SpatialChangePoc(null, null), SpatialChangePoc(b, box(3)), SpatialChangePoc(a, null))
        for (suffix in invalid) {
            assertIs<SpatialIndexUpdatePoc.Rejected>(index.advance(stamp, stamp.copy(revision = 1), listOf(prefix, suffix)))
            assertEquals(setOf(1, 2), ids(index)); assertEquals(setOf(1), ids(index, BoundsD(1.0, 1.0, 1.0, 1.0)))
            assertEquals(0, index.overlayCount)
        }
    }

    @Test fun capacityBackpressureRequiresCompactionWithoutDroppingAcceptedContent() {
        val a = box(1); val b = box(2)
        val index = IncrementalSpatialIndexPoc.create(stamp, listOf(a, b), maxObjects = 3, maxOverlay = 1, maxBatch = 1)
        val moved = box(1, 100.0)
        val next = change(index, SpatialChangePoc(a, moved))
        val rejected = assertIs<SpatialIndexUpdatePoc.Rejected>(next.advance(next.stamp, next.stamp.copy(revision = 2), listOf(SpatialChangePoc(b, null))))
        assertEquals("compaction-required", rejected.reason); assertEquals(setOf(1, 2), ids(next))
        val compact = ready(next.compact(next.stamp))
        assertEquals(next.stamp, compact.stamp); assertEquals(0, compact.overlayCount)
        val deleted = change(compact, SpatialChangePoc(b, null))
        assertEquals(setOf(1), ids(deleted)); assertEquals(1, deleted.tombstoneCount)
        val inserted = change(ready(deleted.compact(deleted.stamp)), SpatialChangePoc(null, box(3)))
        val removed = change(inserted, SpatialChangePoc(box(3), null))
        assertEquals(0, removed.overlayCount); assertEquals(1, removed.objectCount)
        assertIs<SpatialIndexUpdatePoc.Rejected>(index.advance(stamp, stamp.copy(revision = 1), listOf(SpatialChangePoc(a, null), SpatialChangePoc(b, null))))
        val full = IncrementalSpatialIndexPoc.create(stamp, listOf(a), 1, 1, 1)
        assertEquals("object-budget", assertIs<SpatialIndexUpdatePoc.Rejected>(full.advance(stamp, stamp.copy(revision = 1), listOf(SpatialChangePoc(null, b)))).reason)
    }

    @Test fun degenerateQueryBudgetsNeverReturnAPartialHitSet() {
        val entries = (0 until 1000).map { SpatialEntry(it, BoundsD(0.0, 0.0, 1.0, 1.0)) }
        val index = IncrementalSpatialIndexPoc.create(stamp, entries)
        val complete = assertIs<SpatialIndexQueryPoc.Ready>(index.query(stamp, all, 1000, 1000, 1000)).query
        assertEquals(1000, complete.ids.size); assertEquals(1000, complete.entryTests)
        for ((tests, nodes, hits) in listOf(Triple(999, 1000, 1000), Triple(1000, complete.nodeTests - 1, 1000), Triple(1000, 1000, 999))) {
            assertIs<SpatialIndexQueryPoc.Rejected>(index.query(stamp, all, tests, nodes, hits))
        }
        val next = change(index, SpatialChangePoc(entries[0], box(0, 50.0)))
        assertIs<SpatialIndexQueryPoc.Rejected>(next.query(next.stamp, all, 999, 1000, 1000))
        assertEquals(1000, ids(next).size)
        assertFailsWith<IllegalArgumentException> { next.query(next.stamp, all, -1, 1, 1) }
    }

    @Test fun cancellationDuringPreparationAndCompactionLeavesOriginalUsable() {
        val a = box(1); val b = box(2)
        val index = IncrementalSpatialIndexPoc.create(stamp, listOf(a, b))
        for (stopAt in 1..4) {
            var calls = 0
            assertIs<SpatialIndexUpdatePoc.Cancelled>(index.advance(stamp, stamp.copy(revision = 1),
                listOf(SpatialChangePoc(a, null), SpatialChangePoc(b, null))) { ++calls == stopAt })
            assertEquals(setOf(1, 2), ids(index))
        }
        val next = change(index, SpatialChangePoc(a, box(1, 90.0)), SpatialChangePoc(b, null))
        // before materialization, during base/overlay scan, and after packing
        for (stopAt in 1..6) {
            var calls = 0
            assertIs<SpatialIndexUpdatePoc.Cancelled>(next.compact(next.stamp) { ++calls == stopAt })
            assertEquals(setOf(1), ids(next)); assertEquals(2, next.overlayCount)
        }
    }

    @Test fun randomEditsAndCompactionsMatchIndependentBruteForce() {
        val random = Random(20261017)
        val oracle = mutableMapOf<Int, SpatialEntry>()
        var index = IncrementalSpatialIndexPoc.create(stamp, emptyList(), 128, 8, 4)
        repeat(1500) {
            val id = random.nextInt(128); val before = oracle[id]
            val after = if (before != null && random.nextInt(3) == 0) null else box(id, random.nextDouble(-300.0, 300.0))
            val delta = listOf(SpatialChangePoc(before, after)); val nextStamp = index.stamp.copy(revision = index.stamp.revision + 1)
            var result = index.advance(index.stamp, nextStamp, delta)
            if (result is SpatialIndexUpdatePoc.Rejected) {
                assertEquals("compaction-required", result.reason)
                index = ready(index.compact(index.stamp)); result = index.advance(index.stamp, nextStamp, delta)
            }
            index = ready(result)
            if (after == null) oracle.remove(id) else oracle[id] = after
            val x = random.nextDouble(-350.0, 350.0); val y = random.nextDouble(-350.0, 350.0)
            val query = BoundsD(x, y, x + 100, y + 100)
            assertEquals(oracle.values.filter { it.bounds.overlaps(query) }.map { it.id }.toSet(), ids(index, query))
            assertEquals(oracle.keys.toSet(), ids(index)); assertEquals(oracle.size, index.objectCount)
            assertTrue(index.overlayCount <= 8); assertTrue(index.retainedBoundsCount <= 136)
        }
    }

    @Test fun canonicalScenePatchesFeedDerivedBoundsWithoutAnotherAuthority() {
        fun spatial(obj: StrokeObject) = SpatialEntry(obj.id.value.toInt(), obj.bounds())
        val a = StrokeObject(ObjectId("1"), 1, LayerId("layer"), BrushSpec(BrushKind.MONOLINE, 1, 2.0, -1),
            StrokeSamples.copyOf(doubleArrayOf(0.0, 8.0), doubleArrayOf(0.0, 8.0), floatArrayOf(1f, 1f), longArrayOf(0, 1)))
        var scene = SceneSnapshot.restored(stamp.sceneId, 0, listOf(a))
        var index = IncrementalSpatialIndexPoc.create(stamp, scene.objects().map(::spatial))
        val moved = a.copy(objectRevision = 2, transform = Affine2D(tx = 100.0, ty = -100.0))
        val edits = listOf(ObjectChange(a, moved), ObjectChange(moved, null), ObjectChange(null, moved.copy(objectRevision = 3)))
        for ((sequence, edit) in edits.withIndex()) {
            val patch = assertIs<PrepareResult.Prepared>(SceneReducer.prepareChanges(scene,
                CommandId("patch-$sequence"), listOf(edit))).patch
            val next = assertIs<SceneApplication.Applied>(SceneReducer.apply(scene, patch)).snapshot
            val changes = patch.changes().map { SpatialChangePoc(it.before?.let(::spatial), it.after?.let(::spatial)) }
            val derived = ready(index.advance(index.stamp, index.stamp.copy(revision = next.revision), changes))
            assertIs<SpatialIndexQueryPoc.Rejected>(index.query(derived.stamp, all, 100, 100, 100))
            scene = next; index = derived
            for (query in listOf(all, BoundsD(-5.0, -5.0, 20.0, 20.0), BoundsD(90.0, -110.0, 120.0, -80.0))) {
                assertEquals(scene.objects().filter { it.bounds().overlaps(query) }.map { it.id.value.toInt() }.toSet(), ids(index, query))
            }
        }
        assertEquals(3L, scene.revision); assertEquals(1, scene.objectCount)
    }

    @Test fun inputIsolationEmptyQueriesAndExtremeFiniteBounds() {
        val huge = SpatialEntry(1, BoundsD(-Double.MAX_VALUE, -Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE))
        val input = mutableListOf(huge)
        val index = IncrementalSpatialIndexPoc.create(stamp, input)
        input.clear(); assertEquals(setOf(1), ids(index))
        val delta = mutableListOf(SpatialChangePoc(huge, null))
        val next = ready(index.advance(stamp, stamp.copy(revision = 1), delta)); delta.clear()
        assertTrue(ids(next).isEmpty()); assertEquals(setOf(1), ids(index))
        val empty = IncrementalSpatialIndexPoc.create(stamp, emptyList())
        assertTrue(assertIs<SpatialIndexQueryPoc.Ready>(empty.query(stamp, all, 0, 0, 0)).query.ids.isEmpty())
        assertFailsWith<IllegalArgumentException> { IncrementalSpatialIndexPoc.create(stamp, listOf(huge, huge)) }
        assertFailsWith<IllegalArgumentException> { IncrementalSpatialIndexPoc.create(stamp, emptyList(), 1, 2, 1) }
    }
}
