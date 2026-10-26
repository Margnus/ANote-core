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

    private val layer = LayerId("ink")
    private fun ink(id: Int, y: Double = 0.0, kind: BrushKind = BrushKind.MONOLINE, version: Int = 1,
        target: LayerId = layer, diagonal: Boolean = false) = StrokeObject(ObjectId(id.toString()), 1, target,
        BrushSpec(kind, version, if (kind == BrushKind.PRESSURE) 8.0 else 2.0, -1),
        StrokeSamples.copyOf(doubleArrayOf(0.0, 10.0), doubleArrayOf(y, y + if (diagonal) 10.0 else 0.0),
            floatArrayOf(0f, 1f), longArrayOf(1, 2)))
    private fun scene(vararg objects: StrokeObject) = SceneSnapshot.restored(stamp.sceneId, 0, objects.toList())
    private fun bound(scene: SceneSnapshot, overlay: Int = 256) = ready(IncrementalSpatialIndexPoc.createCanonical(
        scene, 1, { it.value.toIntOrNull() }, maxOverlay = overlay, maxBatch = overlay))
    private fun hit(index: IncrementalSpatialIndexPoc, scene: SceneSnapshot, x: Double, y: Double,
        budget: SpatialPointBudgetPoc = SpatialPointBudgetPoc()) =
        assertIs<SpatialPointQueryPoc.Ready>(index.queryPoint(scene, 1, layer, PointD(x, y), budget))

    @Test fun canonicalPointQueriesFilterBboxFalsePositivesPressureAndOtherLayers() {
        val source = scene(ink(1, diagonal = true), ink(2, 20.0, BrushKind.PRESSURE), ink(3, 40.0, BrushKind.HIGHLIGHTER),
            ink(4, target = LayerId("other")))
        val index = bound(source); val before = source.objects()
        assertTrue(1 in assertIs<SpatialIndexQueryPoc.Ready>(index.query(index.stamp, BoundsD(0.0, 10.0, 0.0, 10.0), 100, 100, 100)).query.ids)
        assertTrue(hit(index, source, 0.0, 10.0).hits.isEmpty())
        assertEquals(mapOf(1 to ObjectId("1")), hit(index, source, 5.0, 5.0).hits)
        assertEquals(mapOf(2 to ObjectId("2")), hit(index, source, 5.0, 22.6).hits)
        assertTrue(hit(index, source, 5.0, 22.7).hits.isEmpty())
        assertEquals(mapOf(3 to ObjectId("3")), hit(index, source, 5.0, 40.0).hits)
        assertTrue(hit(index, source, 5.0, 0.0).hits.isEmpty()) // Other layer only, not a geometric hit in ink.
        assertEquals(before, source.objects()); assertEquals(0L, source.revision); assertEquals(0, index.overlayCount)
    }
    @Test fun canonicalBindingRefusesUnsupportedOrUnboundedSourcesAndNoPartialPointResults() {
        val source = scene(ink(1), ink(2))
        assertEquals("geometry-unsupported-brush-version", assertIs<SpatialIndexUpdatePoc.Rejected>(
            IncrementalSpatialIndexPoc.createCanonical(scene(ink(1), ink(2, 1000.0, version = 2)), 1, { it.value.toInt() })).reason)
        assertEquals("duplicate-handle", assertIs<SpatialIndexUpdatePoc.Rejected>(
            IncrementalSpatialIndexPoc.createCanonical(source, 1, { 1 })).reason)
        assertEquals("missing-handle", assertIs<SpatialIndexUpdatePoc.Rejected>(
            IncrementalSpatialIndexPoc.createCanonical(source, 1, { null })).reason)
        assertEquals("sample-budget", assertIs<SpatialIndexUpdatePoc.Rejected>(
            IncrementalSpatialIndexPoc.createCanonical(source, 1, { it.value.toInt() }, maxSourceSamples = 3)).reason)
        val index = bound(source)
        for ((budget, reason) in listOf(SpatialPointBudgetPoc(sourceSamples = 3) to "sample-budget",
            SpatialPointBudgetPoc(hits = 1) to "hit-budget", SpatialPointBudgetPoc(candidates = 1) to "query-budget",
            SpatialPointBudgetPoc(entryTests = 0) to "query-budget", SpatialPointBudgetPoc(nodeTests = 0) to "query-budget")) {
            assertEquals(reason, assertIs<SpatialPointQueryPoc.Rejected>(index.queryPoint(source, 1, layer, PointD(5.0, 0.0), budget)).reason)
        }
        assertEquals(2, hit(index, source, 5.0, 0.0).hits.size)
        assertTrue(hit(index, source, 100.0, 100.0, SpatialPointBudgetPoc(candidates = 0, sourceSamples = 0, hits = 0)).hits.isEmpty())
        assertEquals("stale-content", assertIs<SpatialPointQueryPoc.Rejected>(index.queryPoint(source, 2, layer, PointD(5.0, 0.0))).reason)
        assertEquals("missing-object", assertIs<SpatialPointQueryPoc.Rejected>(index.queryPoint(scene(), 1, layer, PointD(5.0, 0.0))).reason)
        val generic = IncrementalSpatialIndexPoc.create(index.stamp, listOf(box(1)))
        assertEquals("unbound-index", assertIs<SpatialPointQueryPoc.Rejected>(generic.queryPoint(source, 1, layer, PointD(0.0, 0.0))).reason)
    }
    @Test fun actualCanonicalMoveDeleteRestorePatchKeepsBindingsAndRejectsLatePublication() {
        val original = ink(1, diagonal = true); var source = scene(original); var index = bound(source)
        val oldHit = hit(index, source, 5.0, 5.0)
        val moved = original.copy(objectRevision = 2, transform = Affine2D(tx = 100.0, ty = -100.0))
        for ((sequence, change) in listOf(ObjectChange(original, moved), ObjectChange(moved, null),
            ObjectChange(null, original.copy(objectRevision = 3))).withIndex()) {
            val patch = assertIs<PrepareResult.Prepared>(SceneReducer.prepareChanges(source, CommandId("exact-$sequence"), listOf(change))).patch
            val next = assertIs<SceneApplication.Applied>(SceneReducer.apply(source, patch)).snapshot
            val result = ready(index.advanceCanonical(index.stamp, source, next, patch, { it.value.toInt() }))
            assertIs<SpatialPointQueryPoc.Rejected>(index.queryPoint(next, 1, layer, PointD(5.0, 5.0)))
            assertFalse(result.canPublish(oldHit.stamp))
            val generic = ready(index.advance(index.stamp, result.stamp, patch.changes().map {
                fun spatial(obj: StrokeObject) = SpatialEntry(1, obj.bounds(), SpatialObjectBindingPoc(obj.id, obj.objectRevision, obj.layerId))
                SpatialChangePoc(it.before?.let(::spatial), it.after?.let(::spatial))
            }))
            assertEquals("unbound-index", assertIs<SpatialPointQueryPoc.Rejected>(generic.queryPoint(next, 1, layer, PointD(5.0, 5.0))).reason)
            source = next; index = result
            assertEquals(if (sequence == 2) setOf(1) else emptySet(), hit(index, source, 5.0, 5.0).hits.keys)
            assertEquals(if (sequence == 0) setOf(1) else emptySet(), hit(index, source, 105.0, -95.0).hits.keys)
        }
        index = ready(index.compact(index.stamp)); assertEquals(setOf(1), hit(index, source, 5.0, 5.0).hits.keys)
    }
    @Test fun canonicalUpdateAndPointCancellationCannotPublishPrefixOrForgeUnchangedObjects() {
        val source = scene(ink(1), ink(2)); val index = bound(source, 1)
        for (stop in 1..4) {
            var calls = 0
            assertIs<SpatialPointQueryPoc.Cancelled>(index.queryPoint(source, 1, layer, PointD(5.0, 0.0), cancelled = { ++calls == stop }))
            assertEquals(2, hit(index, source, 5.0, 0.0).hits.size)
        }
        val obj = source.find(ObjectId("1"))!!
        val moved = obj.copy(objectRevision = 2, transform = Affine2D(tx = 100.0))
        val patch = assertIs<PrepareResult.Prepared>(SceneReducer.prepareChanges(source, CommandId("bounded"), listOf(ObjectChange(obj, moved)))).patch
        val next = assertIs<SceneApplication.Applied>(SceneReducer.apply(source, patch)).snapshot
        assertEquals("sample-budget", assertIs<SpatialIndexUpdatePoc.Rejected>(index.advanceCanonical(index.stamp, source, next, patch,
            { it.value.toInt() }, maxSourceSamples = 3)).reason)
        assertEquals("metadata-budget", assertIs<SpatialIndexUpdatePoc.Rejected>(index.advanceCanonical(index.stamp, source, next, patch,
            { it.value.toInt() }, maxObjectTests = 1)).reason)
        assertIs<SpatialIndexUpdatePoc.Cancelled>(index.advanceCanonical(index.stamp, source, next, patch, { it.value.toInt() }, cancelled = { true }))
        val forged = SceneSnapshot.restored(source.id, next.revision, listOf(moved, ink(2, 500.0, version = 2)))
        assertEquals("noncanonical-unchanged", assertIs<SpatialIndexUpdatePoc.Rejected>(index.advanceCanonical(index.stamp, source, forged, patch, { it.value.toInt() })).reason)
        val accepted = ready(index.advanceCanonical(index.stamp, source, next, patch, { it.value.toInt() }))
        val other = next.find(ObjectId("2"))!!; val bad = other.copy(objectRevision = 2, brush = other.brush.copy(algorithmVersion = 2))
        val unsupported = assertIs<PrepareResult.Prepared>(SceneReducer.prepareChanges(next, CommandId("unsupported"), listOf(ObjectChange(other, bad)))).patch
        val unsupportedScene = assertIs<SceneApplication.Applied>(SceneReducer.apply(next, unsupported)).snapshot
        assertEquals("geometry-unsupported-brush-version", assertIs<SpatialIndexUpdatePoc.Rejected>(accepted.advanceCanonical(accepted.stamp,
            next, unsupportedScene, unsupported, { it.value.toInt() })).reason)
        val otherMoved = other.copy(objectRevision = 2, transform = Affine2D(tx = 200.0))
        val secondPatch = assertIs<PrepareResult.Prepared>(SceneReducer.prepareChanges(next, CommandId("second"), listOf(ObjectChange(other, otherMoved)))).patch
        val secondScene = assertIs<SceneApplication.Applied>(SceneReducer.apply(next, secondPatch)).snapshot
        assertEquals("compaction-required", assertIs<SpatialIndexUpdatePoc.Rejected>(accepted.advanceCanonical(accepted.stamp,
            next, secondScene, secondPatch, { it.value.toInt() })).reason)
        val compacted = ready(accepted.compact(accepted.stamp))
        val retried = ready(compacted.advanceCanonical(compacted.stamp, next, secondScene, secondPatch, { it.value.toInt() }))
        assertEquals(setOf(2), hit(retried, secondScene, 205.0, 0.0).hits.keys)
        assertEquals(setOf(1, 2), hit(index, source, 5.0, 0.0).hits.keys)
        assertEquals(setOf(1), hit(accepted, next, 105.0, 0.0).hits.keys)
    }

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
