package dev.notecore.canvas.prototype

import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.system.measureNanoTime
import kotlinx.serialization.json.*

/** Exploratory reference CPU tile and fragment-index costs; no native draw/heap claim. */
object ChunkedBrushCosts {
    private fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun read(file: File, strokes: Int, points: Int, consume: (Int, StrokeSamples) -> Unit) {
        DataInputStream(file.inputStream().buffered()).use { stream ->
            val header = ByteArray(16).also(stream::readFully); val h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            require(header.copyOfRange(0, 8).decodeToString() == "NCSCALE1" && h.getInt(8) == strokes && h.getInt(12) == points)
            val bytes = ByteArray(28); val p = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            repeat(strokes) { id ->
                val xs = DoubleArray(points); val ys = DoubleArray(points); val pressure = FloatArray(points); val times = LongArray(points)
                repeat(points) { i -> stream.readFully(bytes); xs[i] = p.getDouble(0); ys[i] = p.getDouble(8); pressure[i] = p.getFloat(16); times[i] = p.getLong(20) }
                consume(id, StrokeSamples.copyOf(xs, ys, pressure, times))
            }
            require(stream.read() == -1)
        }
    }
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.single())
        val profiles = Json.parseToJsonElement(File(root, "fixtures/scale/manifest.json").readText(Charsets.UTF_8)).jsonObject.getValue("profiles").jsonArray
        fun fixture(id: String): File {
            val profile = profiles.single { it.jsonObject.getValue("id").jsonPrimitive.content == id }.jsonObject
            val file = File(root, ".generated-fixtures/scale/${profile.getValue("file").jsonPrimitive.content}")
            require(digest(file) == profile.getValue("sha256").jsonPrimitive.content); return file
        }
        val dense = fixture("S2-ink-dense-page"); val long = fixture("S9-ink-long-stroke")
        val frame = FrameToken(SceneId("chunk-benchmark"), 1, 1, 1, 1, 1)
        var longInput: StrokeSamples? = null
        read(long, 1, 100000) { _, input -> longInput = input }
        val source = longInput!!
        val longCases = BrushKind.entries.map { kind ->
            val brush = BrushSpec(kind, 1, 10.0, -1)
            val stroke = StrokeObject(ObjectId("long-${kind.name}"), 1, LayerId("ink"), brush, source)
            var catalog: ChunkedBrushPoc? = null; var full: ReducedBrushPathBuild.Ready? = null
            val fullNs = measureNanoTime { full = ReducedBrushPathPoc.build(source, brush) as ReducedBrushPathBuild.Ready }
            val catalogNs = measureNanoTime { catalog = (ChunkedBrushPoc.build(frame, stroke) as ChunkedBrushBuild.Ready).catalog }
            val c = catalog!!
            var checkedOracleSamples = 0
            val oracle = (BrushEnvelopePoc.build(source, brush) as BrushEnvelopeBuild.Ready).envelope
            val windows = (0 until 8).map { i ->
                val x = 100.0 + i * 3072
                val grid = BrushTileGridPoc(PointD(x, -8.0), 64, 32)
                val brute = (0 until c.chunkCount).filter { c.chunkBounds(it).overlaps(grid.bounds) }.toSet()
                require(c.queryChunks(grid.bounds).ids == brute)
                var rendered: BrushTileBuild.Ready? = null
                val ns = measureNanoTime { rendered = c.render(frame, grid) as BrushTileBuild.Ready }
                val r = rendered!!
                // Sparse independent raw-100k-point oracle, not a reduced-input oracle.
                // Stable probes only: sample lattice AA edges intentionally remain unclaimed.
                for (px in 0 until 64 step 11) for (py in 0 until 32 step 7) {
                    val p = PointD(x + px + 0.25, -8 + py + 0.25)
                    val expected = oracle.containsScene(p)
                    if (listOf(PointD(p.x - 0.1, p.y), PointD(p.x + 0.1, p.y), PointD(p.x, p.y - 0.1), PointD(p.x, p.y + 0.1)).all { oracle.containsScene(it) == expected }) {
                        require((r.tile.coverageBits(px, py) and 1 != 0) == expected); checkedOracleSamples++
                    }
                }
                buildJsonObject {
                    put("origin_x", x); put("candidate_chunks", r.candidateChunks); put("built_chunks", r.builtChunks)
                    put("generated_vertices", r.generatedVertices); put("raster_edge_steps", r.edgeTests)
                    put("mask_logical_bytes", r.tile.logicalBytes); put("peak_job_working_logical_bytes", r.peakWorkingLogicalBytes)
                    put("reference_cpu_render_ns", ns)
                }
            }
            val overloaded = c.render(frame, BrushTileGridPoc(PointD(-1e6, -1e6), 128, 128, pixelSize = 1e6)) as BrushTileBuild.Rejected
            require(overloaded.reason == "candidate-budget")
            buildJsonObject {
                put("kind", kind.name); put("canonical_samples", source.size); put("total_chunks", c.chunkCount)
                put("catalog_bounds_payload_bytes", c.boundsPayloadBytes); put("catalog_index_nodes", c.indexNodeCount)
                put("catalog_build_ns", catalogNs); put("whole_reduced_path_build_ns", fullNs)
                put("whole_reduced_vertices", full!!.path.vertexCount); put("whole_reduced_path_logical_bytes", full!!.path.logicalBytes)
                put("raw_oracle_stable_probe_checks", checkedOracleSamples); put("overloaded_view_result", overloaded.reason)
                put("windows", JsonArray(windows))
            }
        }

        // S2 global fragment-index experiment only. It is not a page renderer/authority.
        val objects = mutableListOf<SpatialEntry>(); val fragments = mutableListOf<SpatialEntry>(); val fragmentSamples = mutableListOf<Int>()
        read(dense, 10000, 100) { id, input ->
            val stroke = StrokeObject(ObjectId("s2-$id"), 1, LayerId("ink"), BrushSpec(BrushKind.MONOLINE, 1, 10.0, -1), input)
            objects.add(SpatialEntry(id, stroke.bounds()))
            val c = (ChunkedBrushPoc.build(frame, stroke, segmentsPerChunk = 32) as ChunkedBrushBuild.Ready).catalog
            require(c.chunkCount == 4)
            for (chunk in 0 until 4) {
                fragments.add(SpatialEntry(id * 4 + chunk, c.chunkBounds(chunk)))
                fragmentSamples.add(c.chunkEnd(chunk) - c.chunkStart(chunk) + 1)
            }
        }
        var objectTree: PackedRTreePoc? = null; var chunkTree: PackedRTreePoc? = null
        val objectBuildNs = measureNanoTime { objectTree = PackedRTreePoc(objects) }
        val fragmentBuildNs = measureNanoTime { chunkTree = PackedRTreePoc(fragments) }
        var objectPointsLocal = 0L; var chunkPointsLocal = 0L
        val queries = (0 until 200).map { i ->
            val x = (i % 13) * 4.0 - 4; val y = ((i * 67) % 200) * 10.0 - 4
            BoundsD(x, y, x + 4, y + 8)
        } + listOf(BoundsD(-100.0, -100.0, 2100.0, 2100.0), BoundsD(1e6, 1e6, 1e6 + 4, 1e6 + 8))
        val queryResults = queries.mapIndexed { i, q ->
            val a = objectTree!!.query(q); val b = chunkTree!!.query(q)
            require(a.ids == objects.filter { it.bounds.overlaps(q) }.map { it.id }.toSet())
            require(b.ids == fragments.filter { it.bounds.overlaps(q) }.map { it.id }.toSet())
            require(b.ids.map { it / 4 }.all { it in a.ids })
            val objectPoints = a.ids.size * 100L; val chunkPoints = b.ids.sumOf { fragmentSamples[it].toLong() }
            if (i < 200) { objectPointsLocal += objectPoints; chunkPointsLocal += chunkPoints }
            buildJsonObject {
                put("cohort", if (i < 200) "local" else if (i == 200) "whole-page" else "empty")
                put("object_hits", a.ids.size); put("chunk_hits", b.ids.size); put("deduplicated_stroke_hits", b.ids.map { it / 4 }.toSet().size)
                put("object_input_points", objectPoints); put("chunk_input_points_including_shared_boundaries", chunkPoints)
                put("object_entry_tests", a.entryTests); put("chunk_entry_tests", b.entryTests)
            }
        }
        val report = buildJsonObject {
            put("schema", "chunked-brush-cost-poc-1"); put("status", "passed")
            put("scope", "Desktop JVM exploratory cold costs, bounded CPU subpixel reference rasterization, S9 per-stroke window work and S2 global bounds-only index experiment. No native raster/heap, page UI, frame budget or latency acceptance.")
            put("java", System.getProperty("java.runtime.version")); put("os", System.getProperty("os.name")); put("arch", System.getProperty("os.arch"))
            put("actual_heap_measured", false); put("native_raster_verified", false); put("physical_device_measured", false)
            put("index_heap_included_in_bounds_payload", false); put("dense_fixture_sha256", digest(dense)); put("long_fixture_sha256", digest(long))
            put("long_strokes", JsonArray(longCases))
            putJsonObject("s2_bounds_index") {
                put("objects", objects.size); put("fragments", fragments.size); put("object_index_nodes", objectTree!!.nodeCount); put("fragment_index_nodes", chunkTree!!.nodeCount)
                put("object_index_build_ns", objectBuildNs); put("fragment_index_build_ns", fragmentBuildNs)
                put("local_query_object_input_points", objectPointsLocal); put("local_query_chunk_input_points", chunkPointsLocal)
                put("queries", JsonArray(queryResults))
            }
            putJsonObject("source_sha256") {
                listOf("canvas-core/src/commonMain/kotlin/dev/notecore/canvas/prototype/ChunkedBrushPoc.kt",
                    "canvas-core/src/commonMain/kotlin/dev/notecore/canvas/prototype/SpatialIndexPoc.kt",
                    "canvas-core/src/commonMain/kotlin/dev/notecore/canvas/prototype/BrushReductionPoc.kt",
                    "canvas-core/src/commonMain/kotlin/dev/notecore/canvas/prototype/BrushPathPoc.kt",
                    "canvas-core/src/commonMain/kotlin/dev/notecore/canvas/prototype/BrushEnvelopePoc.kt",
                    "canvas-core/src/commonMain/kotlin/dev/notecore/canvas/prototype/Geometry.kt",
                    "canvas-core/src/commonMain/kotlin/dev/notecore/canvas/prototype/Stroke.kt",
                    "canvas-core/src/jvmTest/kotlin/dev/notecore/canvas/prototype/ChunkedBrushCosts.kt", "fixtures/scale/manifest.json").forEach { name ->
                    val bytes = File(root, name).readText(Charsets.UTF_8).replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
                    put(name, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
                }
            }
        }
        File(root, "reports/chunked-brush-costs.json").writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report) + "\n", Charsets.UTF_8)
        println("S9 bounded window masks/union and S2 object-vs-fragment bounds experiment passed")
    }
}
