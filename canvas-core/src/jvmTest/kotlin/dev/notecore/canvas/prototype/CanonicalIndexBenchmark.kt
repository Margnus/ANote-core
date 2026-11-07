package dev.notecore.canvas.prototype

import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Opt-in candidate comparison; all decisions use logical counters, never a wall-clock threshold. */
object CanonicalIndexBenchmark {
    private val layer = LayerId("ink")
    private val other = LayerId("other")
    private const val generation = 1L
    private const val denseCount = 1800
    private const val longCount = 10_000
    private const val sampleLimit = 200_000
    private val budget = SpatialPointBudgetPoc(candidates = denseCount + 1, hits = denseCount + 1)
    private val handle: (ObjectId) -> Int? = { it.value.toIntOrNull() }
    private data class Stage(val name: String, val scene: SceneSnapshot, val patch: ScenePatch?)
    private data class Oracle(val broad: List<Set<Int>>, val exact: List<List<ObjectId>>)
    private data class Counters(val entries: Int, val nodes: Int, val candidates: Int, val samples: Int, val hits: Int)

    private fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
    private fun json(value: Any): JsonPrimitive = when (value) {
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }
    private fun array(values: Iterable<Any>) = JsonArray(values.map(::json))
    private fun <T> timed(times: MutableList<Long>, action: () -> T): T {
        val start = System.nanoTime()
        val result = action()
        times += System.nanoTime() - start
        return result
    }
    private fun readFixture(file: File, totalStrokes: Int, totalPoints: Int, strokes: Int, points: Int): List<StrokeSamples> =
        DataInputStream(file.inputStream().buffered()).use { stream ->
            val header = ByteArray(16).also(stream::readFully)
            require(header.copyOfRange(0, 8).decodeToString() == "NCSCALE1")
            val view = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            require(view.getInt(8) == totalStrokes && view.getInt(12) == totalPoints)
            require(strokes <= totalStrokes && points <= totalPoints && (strokes == 1 || points == totalPoints))
            val bytes = ByteArray(28)
            val row = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            List(strokes) {
                val x = DoubleArray(points); val y = DoubleArray(points)
                val pressure = FloatArray(points); val time = LongArray(points)
                for (i in 0 until points) {
                    stream.readFully(bytes)
                    x[i] = row.getDouble(0); y[i] = row.getDouble(8)
                    pressure[i] = row.getFloat(16); time[i] = row.getLong(20)
                }
                StrokeSamples.copyOf(x, y, pressure, time)
            }
        }

    private fun makeStroke(id: Int, samples: StrokeSamples) = StrokeObject(
        ObjectId(id.toString()), 1, if (id != denseCount && id % 7 == 1) other else layer,
        BrushSpec(BrushKind.entries[id % BrushKind.entries.size], 1, 2.0, 0x80254377.toInt()), samples,
        if (id % 19 == 0) Affine2D(a = 0.875, d = 1.125, tx = 2.0, ty = 4.0) else Affine2D(),
    )

    private fun stages(source: SceneSnapshot): List<Stage> {
        val first = source.objects().take(32)
        val moved = first.take(16).map { ObjectChange(it, it.copy(objectRevision = 2, transform = Affine2D(tx = 70.0))) }
        val removed = first.drop(16).map { ObjectChange(it, null) }
        val restored = first.drop(16).map { ObjectChange(null, it.copy(objectRevision = 2)) }
        return buildList {
            add(Stage("initial", source, null))
            for ((name, changes) in listOf("move" to moved, "delete" to removed, "restore" to restored)) {
                val before = last().scene
                val patch = (SceneReducer.prepareChanges(before, CommandId(name), changes) as PrepareResult.Prepared).patch
                val next = (SceneReducer.apply(before, patch) as SceneApplication.Applied).snapshot
                add(Stage(name, next, patch))
            }
        }
    }

    private fun envelope(obj: StrokeObject) =
        (BrushEnvelopePoc.build(obj.samples, obj.brush, obj.transform, obj.samples.size) as BrushEnvelopeBuild.Ready).envelope
    private fun oracle(stage: Stage, queries: List<PointD>): Oracle {
        val rows = stage.scene.objects().map { it to envelope(it) }
        return Oracle(
            queries.map { p -> rows.filter { it.second.paintedBounds.overlaps(p.box()) }.map { handle(it.first.id)!! }.toSet() },
            queries.map { p -> rows.filter { it.first.layerId == layer && it.second.containsScene(p) }.map { it.first.id } },
        )
    }
    private fun PointD.box() = BoundsD(x, y, x, y)
    private fun build(scene: SceneSnapshot) = (IncrementalSpatialIndexPoc.createCanonical(
        scene, generation, handle, maxObjects = 5000, maxSourceSamples = sampleLimit,
    ) as SpatialIndexUpdatePoc.Ready).index

    private fun queryExact(index: IncrementalSpatialIndexPoc, stage: Stage, queries: List<PointD>) =
        queries.map { index.queryPoint(stage.scene, generation, layer, it, budget) as SpatialPointQueryPoc.Ready }

    private fun verifyQueries(index: IncrementalSpatialIndexPoc, stage: Stage, queries: List<PointD>,
        results: List<SpatialPointQueryPoc.Ready>, oracle: Oracle): Counters {
        val order = stage.scene.objects().mapIndexed { i, obj -> obj.id to i }.toMap()
        var entries = 0; var nodes = 0; var candidates = 0; var samples = 0; var hits = 0
        for ((i, point) in queries.withIndex()) {
            val broad = (index.query(index.stamp, point.box(), budget.entryTests, budget.nodeTests, budget.candidates)
                as SpatialIndexQueryPoc.Ready).query
            check(broad.ids == oracle.broad[i])
            val exact = results[i]
            check(exact.hits.values.sortedBy { order.getValue(it) } == oracle.exact[i])
            entries += exact.entryTests; nodes += exact.nodeTests; candidates += exact.candidateCount
            samples += exact.sourceSamples; hits += exact.hits.size
        }
        return Counters(entries, nodes, candidates, samples, hits)
    }
    private fun counts(c: Counters) = buildJsonObject {
        put("entry_tests", c.entries); put("node_tests", c.nodes); put("candidates", c.candidates)
        put("source_samples", c.samples); put("hits", c.hits)
    }

    @JvmStatic fun main(args: Array<String>) {
        val root = File(args[0])
        val manifest = Json.parseToJsonElement(File(root, "fixtures/scale/manifest.json").readText(Charsets.UTF_8)).jsonObject
        val fixtures = linkedMapOf<String, String>()
        fun fixture(name: String): File {
            val profile = manifest.getValue("profiles").jsonArray.map { it.jsonObject }
                .single { it.getValue("id").jsonPrimitive.content == name }
            return File(root, ".generated-fixtures/scale/${profile.getValue("file").jsonPrimitive.content}").also {
                fixtures[name] = digest(it)
                check(fixtures[name] == profile.getValue("sha256").jsonPrimitive.content)
            }
        }
        val dense = readFixture(fixture("S2-ink-dense-page"), 10_000, 100, denseCount, 100)
        val longFile = fixture("S9-ink-long-stroke")
        val long = readFixture(longFile, 1, 100_000, 1, longCount).single()
        val objects = (dense + long).mapIndexed(::makeStroke)
        val source = SceneSnapshot.restored(SceneId("canonical-index-cost"), 0, objects)
        val stages = stages(source)
        val queries = objects.take(64).map { obj -> obj.transform.map(PointD(obj.samples.x(20), obj.samples.y(20))) } +
            listOf(PointD(-25.0, -25.0), PointD(10000.0, 10000.0), PointD(150.0, 0.0), PointD(73.0, 3.0)) +
            listOf(20, 500, longCount - 1).map { i -> objects.last().transform.map(PointD(long.x(i), long.y(i))) }
        val expected = stages.map { oracle(it, queries) }
        check(expected.all { result -> result.exact.takeLast(3).all { objects.last().id in it } })
        val fullBuild = stages.map { mutableListOf<Long>() }
        val deltaUpdate = stages.map { mutableListOf<Long>() }
        val fullQuery = stages.map { mutableListOf<Long>() }
        val deltaQuery = stages.map { mutableListOf<Long>() }
        val gridQuery = stages.map { mutableListOf<Long>() }
        val gridBuild = mutableListOf<Long>()
        val gridUpdate = stages.map { mutableListOf<Long>() }
        val rows = mutableListOf<JsonObject>()
        repeat(5) { round ->
            var delta = build(source)
            val grid = timed(gridBuild) {
                GridIndexPoc().also { index ->
                    source.forEachObject { obj -> index.put(SpatialEntry(handle(obj.id)!!, envelope(obj).paintedBounds)) }
                }
            }
            for ((i, stage) in stages.withIndex()) {
                val rebuilt = timed(fullBuild[i]) { build(stage.scene) }
                val oldDelta = delta
                if (i > 0) {
                    val patch = checkNotNull(stage.patch)
                    delta = timed(deltaUpdate[i]) {
                        (oldDelta.advanceCanonical(oldDelta.stamp, stages[i-1].scene, stage.scene, patch, handle) as SpatialIndexUpdatePoc.Ready).index
                    }
                    check(delta.stamp == rebuilt.stamp && !delta.canPublish(oldDelta.stamp))
                    timed(gridUpdate[i]) {
                        for (change in patch.changes()) {
                            val after = change.after
                            if (after == null) grid.remove(handle(checkNotNull(change.before).id)!!)
                            else grid.put(SpatialEntry(handle(after.id)!!, envelope(after).paintedBounds))
                        }
                    }
                }
                val fullResult: List<SpatialPointQueryPoc.Ready>
                val deltaResult: List<SpatialPointQueryPoc.Ready>
                if (round % 2 == 0) {
                    fullResult = timed(fullQuery[i]) { queryExact(rebuilt, stage, queries) }
                    deltaResult = timed(deltaQuery[i]) { queryExact(delta, stage, queries) }
                } else {
                    deltaResult = timed(deltaQuery[i]) { queryExact(delta, stage, queries) }
                    fullResult = timed(fullQuery[i]) { queryExact(rebuilt, stage, queries) }
                }
                val full = verifyQueries(rebuilt, stage, queries, fullResult, expected[i])
                val updated = verifyQueries(delta, stage, queries, deltaResult, expected[i])
                check(full.hits == updated.hits && full.candidates == updated.candidates && full.samples == updated.samples)
                val broad = timed(gridQuery[i]) { queries.map { grid.query(it.box()) } }
                check(broad.map { it.ids } == expected[i].broad)
                if (round == 4) rows += buildJsonObject {
                    put("stage", stage.name); put("revision", stage.scene.revision); put("objects", stage.scene.objectCount)
                    put("rebuild_source_samples", stage.scene.objects().sumOf { it.samples.size })
                    put("delta_changed_sample_budget", stage.patch?.changes()?.sumOf {
                        (it.before?.samples?.size ?: 0) + (it.after?.samples?.size ?: 0)
                    } ?: 0)
                    put("delta_validates_old_object_metadata", if (i == 0) 0 else stages[i-1].scene.objectCount)
                    put("rebuild_bounds", rebuilt.retainedBoundsCount); put("delta_bounds", delta.retainedBoundsCount)
                    put("delta_overlay", delta.overlayCount); put("delta_tombstones", delta.tombstoneCount)
                    // Four Double coordinates per retained bound, excluding tree/maps and work buffers.
                    // Old/new delta snapshots share the base; this sum conservatively counts it twice.
                    put("delta_old_new_bound_scalar_upper_bytes", if (i == 0) 0 else
                        (oldDelta.retainedBoundsCount + delta.retainedBoundsCount) * 32)
                    put("grid_references", grid.storedReferences); put("grid_overflow", grid.overflowCount)
                    put("grid_entry_tests", broad.sumOf { it.entryTests })
                    put("rebuild_query_work", counts(full)); put("delta_query_work", counts(updated))
                    put("rebuild_ns", array(fullBuild[i].drop(2))); put("delta_update_ns", array(deltaUpdate[i].drop(2)))
                    put("rebuild_query_ns", array(fullQuery[i].drop(2))); put("delta_query_ns", array(deltaQuery[i].drop(2)))
                    put("grid_broad_only_ns", array(gridQuery[i].drop(2)))
                    put("grid_broad_only_update_ns", array(gridUpdate[i].drop(2)))
                }
            }
        }
        val fullLong = readFixture(longFile, 1, 100_000, 1, 100_000).single()
        val oversized = SceneSnapshot.restored(source.id, 0, objects.dropLast(1) + makeStroke(denseCount, fullLong))
        fun reason(result: SpatialIndexUpdatePoc) = (result as SpatialIndexUpdatePoc.Rejected).reason
        check(reason(IncrementalSpatialIndexPoc.createCanonical(oversized, generation, handle, maxSourceSamples = sampleLimit)) == "sample-budget")
        val base = build(source); val moved = stages[1]
        check(reason(base.advanceCanonical(base.stamp, source, moved.scene, moved.patch!!, handle,
            maxSourceSamples = 3199)) == "sample-budget")
        val firstHit = expected[0].exact.indexOfFirst { it.isNotEmpty() }
        check(firstHit >= 0)
        val point = queries[firstHit]
        check((base.queryPoint(source, generation, layer, point, budget.copy(sourceSamples = 0)) as SpatialPointQueryPoc.Rejected).reason == "sample-budget")
        check((base.queryPoint(source, generation, layer, point, budget.copy(hits = 0)) as SpatialPointQueryPoc.Rejected).reason == "hit-budget")
        check((base.queryPoint(moved.scene, generation, layer, point, budget) as SpatialPointQueryPoc.Rejected).reason == "stale-content")
        check(base.canPublish(base.stamp) && base.overlayCount == 0)
        val limited = (IncrementalSpatialIndexPoc.createCanonical(source, generation, handle,
            maxOverlay = 16, maxBatch = 16, maxSourceSamples = sampleLimit) as SpatialIndexUpdatePoc.Ready).index
        val firstDelta = (limited.advanceCanonical(limited.stamp, source, moved.scene, moved.patch, handle) as SpatialIndexUpdatePoc.Ready).index
        val removed = stages[2]
        val removal = checkNotNull(removed.patch)
        check(reason(firstDelta.advanceCanonical(firstDelta.stamp, moved.scene, removed.scene, removal, handle)) == "compaction-required")
        check(firstDelta.overlayCount == 16 && firstDelta.canPublish(firstDelta.stamp))
        val compacted = (firstDelta.compact(firstDelta.stamp) as SpatialIndexUpdatePoc.Ready).index
        check(compacted.overlayCount == 0 && compacted.stamp == firstDelta.stamp)
        val resumed = (compacted.advanceCanonical(compacted.stamp, moved.scene, removed.scene, removal, handle) as SpatialIndexUpdatePoc.Ready).index
        verifyQueries(resumed, removed, queries, queryExact(resumed, removed, queries), expected[2])
        val report = buildJsonObject {
            put("status", "passed"); put("schema", "canonical-index-candidate-cost-1")
            put("scope", "Desktop JVM reference geometry. Grid is broad phase only. STR rebuild and delta share exact kernel. Exact timings include the actual queryPoint call and result list. Independent broad parity and canonical ordering run outside timing.")
            put("java", System.getProperty("java.runtime.version")); put("arch", System.getProperty("os.arch"))
            put("fixtures", buildJsonObject { fixtures.forEach { (k, v) -> put(k, v) } })
            put("dense_strokes", denseCount); put("long_stroke_prefix_points", longCount); put("queries_per_stage", queries.size)
            put("long_stroke_exact_anchor_hits_per_stage", 3)
            put("oracle", "Direct canonical-order scan using common geometry, independent of index; no AA/render oracle.")
            put("rows", JsonArray(rows)); put("warmup_rounds", 2); put("measured_rounds", 3)
            put("grid_broad_only_build_ns", array(gridBuild.drop(2)))
            put("refusals", array(listOf("oversized_source_sample-budget", "delta_one_below_sample-budget",
                "exact_query_sample-budget", "exact_query_hit-budget", "stale_revision", "overlay_compaction-required")))
            put("physical_device_verified", false); put("c1_complete", false)
        }
        File(args[1]).writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report) + "\n", Charsets.UTF_8)
        println("Compared 4 canonical stages, ${queries.size} queries per stage; 6 typed refusals confirmed.")
    }
}
