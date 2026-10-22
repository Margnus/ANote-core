package dev.notecore.canvas.prototype

import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.system.measureNanoTime
import kotlinx.serialization.json.*

/** Explicit opt-in desktop experiment. No wall-clock threshold is a unit-test assertion. */
object SpatialIndexBenchmark {
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun stats(values: List<Long>) = buildJsonObject {
        val sorted = values.sorted()
        put("min_ns", sorted.first()); put("median_ns", sorted[sorted.size / 2]); put("max_ns", sorted.last())
    }
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.single()).canonicalFile
        val manifestFile = File(root, "fixtures/scale/manifest.json")
        val manifest = Json.parseToJsonElement(manifestFile.readText(Charsets.UTF_8)).jsonObject
        val profile = manifest.getValue("profiles").jsonArray.single { it.jsonObject.getValue("id").jsonPrimitive.content == "S2-ink-dense-page" }.jsonObject
        val fixture = File(root, ".generated-fixtures/scale/${profile.getValue("file").jsonPrimitive.content}")
        require(hash(fixture) == profile.getValue("sha256").jsonPrimitive.content)
        val dense = mutableListOf<SpatialEntry>()
        DataInputStream(fixture.inputStream().buffered()).use { stream ->
            val header = ByteArray(16).also(stream::readFully)
            require(header.copyOfRange(0, 8).decodeToString() == "NCSCALE1")
            val h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            require(h.getInt(8) == 10000 && h.getInt(12) == 100)
            val bytes = ByteArray(28); val point = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            repeat(10000) { id ->
                var left = Double.POSITIVE_INFINITY; var top = Double.POSITIVE_INFINITY
                var right = Double.NEGATIVE_INFINITY; var bottom = Double.NEGATIVE_INFINITY
                repeat(100) {
                    stream.readFully(bytes)
                    val x = point.getDouble(0); val y = point.getDouble(8)
                    left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x); bottom = maxOf(bottom, y)
                }
                // MONOLINE width=2, identity transform: conservative painted bounds.
                dense.add(SpatialEntry(id, BoundsD(left - 1, top - 1, right + 1, bottom + 1)))
            }
            require(stream.read() == -1)
        }
        val uniform = (0 until 10000).map { id ->
            val x = (id % 100) * 32.0; val y = (id / 100) * 32.0
            SpatialEntry(id, BoundsD(x, y, x + 24, y + 8))
        }
        val cases = linkedMapOf(
            "S2-ink-dense-page" to dense.toList(),
            "uniform-100x100" to uniform,
            "diagonal-large-bbox" to (0 until 10000).map { SpatialEntry(it, BoundsD(-1000.0, -1000.0, 1000.0, 1000.0)) },
            "mixed-100-oversized" to uniform.map { if (it.id % 100 == 0) SpatialEntry(it.id, BoundsD(-1e20, -1e20, 1e20, 1e20)) else it },
        )
        val results = cases.map { (name, entries) ->
            val extent = if (name == "S2-ink-dense-page") 2000.0 else 3200.0
            val queries = (0 until 200).map { i ->
                val x = if (name == "S2-ink-dense-page") (i % 5) * 8.0 else ((i * 37) % 100) * 32.0 - 16
                val y = ((i * 67) % 200) * extent / 200 - 16
                BoundsD(x, y, x + 32, y + 32)
            } + listOf(BoundsD(-1e21, -1e21, 1e21, 1e21), BoundsD(1e22, 1e22, 1e22, 1e22))
            val oracle = queries.map { q -> entries.filter { it.bounds.overlaps(q) }.map { it.id }.toSet() }
            val grid = GridIndexPoc().also { entries.forEach(it::put) }; val tree = PackedRTreePoc(entries)
            val gridQueries = queries.map(grid::query); val treeQueries = queries.map(tree::query)
            require(gridQueries.map { it.ids } == oracle && treeQueries.map { it.ids } == oracle)
            val moved = entries.take(100).map { e -> e.copy(bounds = BoundsD(e.bounds.left + 7, e.bounds.top + 11, e.bounds.right + 7, e.bounds.bottom + 11)) }
            val updated = entries.toMutableList().also { list -> moved.forEach { list[it.id] = it } }.toList()
            val updateGrid = GridIndexPoc().also { entries.forEach(it::put); moved.forEach(it::put) }
            val updateTree = PackedRTreePoc(updated)
            queries.forEach { q ->
                val expected = updated.filter { it.bounds.overlaps(q) }.map { it.id }.toSet()
                require(updateGrid.query(q).ids == expected && updateTree.query(q).ids == expected)
            }
            // Warm each complete workload, then retain every measured round (no outlier removal).
            val buildGrid = mutableListOf<Long>(); val buildTree = mutableListOf<Long>()
            val queryGrid = mutableListOf<Long>(); val queryTree = mutableListOf<Long>(); val queryBrute = mutableListOf<Long>()
            val editGrid = mutableListOf<Long>(); val editTree = mutableListOf<Long>()
            var checksum = 0L
            repeat(8) { round ->
                val g = GridIndexPoc()
                var t: PackedRTreePoc? = null
                val bg = measureNanoTime { entries.forEach(g::put) }
                val bt = measureNanoTime { t = PackedRTreePoc(entries) }
                val qg = measureNanoTime { queries.forEach { checksum += g.query(it).ids.size } }
                val qt = measureNanoTime { queries.forEach { checksum += t!!.query(it).ids.size } }
                val qb = measureNanoTime { queries.forEach { q -> checksum += entries.count { it.bounds.overlaps(q) } } }
                val eg = measureNanoTime { moved.forEach(g::put) }
                val et = measureNanoTime { t = PackedRTreePoc(updated) }
                checksum += t!!.nodeCount
                if (round >= 3) {
                    buildGrid.add(bg); buildTree.add(bt); queryGrid.add(qg); queryTree.add(qt); queryBrute.add(qb)
                    editGrid.add(eg); editTree.add(et)
                }
            }
            fun counts(results: List<SpatialQuery>) = buildJsonObject {
                put("entry_tests_total", results.sumOf { it.entryTests.toLong() })
                put("entry_tests_max", results.maxOf { it.entryTests })
                put("node_or_cell_tests_total", results.sumOf { it.nodeTests.toLong() })
                putJsonArray("entry_tests_per_query") { results.forEach { add(it.entryTests) } }
            }
            buildJsonObject {
                put("dataset", name); put("entries", entries.size); put("queries", queries.size)
                put("oracle_before_and_after_100_moves", true); put("checksum", checksum)
                put("aabb_matches_total", oracle.sumOf { it.size }); put("brute_entry_tests", entries.size.toLong() * queries.size)
                putJsonObject("grid") {
                    put("cell_size", 32); put("max_cells_per_entry_or_query", 256)
                    put("stored_references", grid.storedReferences); put("overflow_entries", grid.overflowCount)
                    put("counts", counts(gridQueries)); put("build", stats(buildGrid)); put("query_batch", stats(queryGrid)); put("move_100", stats(editGrid))
                }
                putJsonObject("str_packed_rtree") {
                    put("fanout", 16); put("nodes", tree.nodeCount); put("counts", counts(treeQueries))
                    put("build", stats(buildTree)); put("query_batch", stats(queryTree)); put("move_100_full_rebuild", stats(editTree))
                }
                put("brute_query_batch", stats(queryBrute))
            }
        }
        val report = buildJsonObject {
            put("schema", "spatial-index-poc-1"); put("status", "passed")
            put("scope", "Desktop JVM AABB broad phase only; synthetic ink subset; no exact hit test, render, device latency or heap measurement")
            put("physical_device_measured", false); put("native_backends_measured", false)
            put("warmup_rounds", 3); put("measured_rounds", 5)
            put("java", System.getProperty("java.runtime.version")); put("os", System.getProperty("os.name")); put("arch", System.getProperty("os.arch"))
            put("fixture_sha256", hash(fixture))
            putJsonObject("source_sha256") {
                listOf("canvas-core/src/commonMain/kotlin/dev/notecore/canvas/prototype/SpatialIndexPoc.kt",
                    "canvas-core/src/commonMain/kotlin/dev/notecore/canvas/prototype/Geometry.kt",
                    "canvas-core/src/jvmTest/kotlin/dev/notecore/canvas/prototype/SpatialIndexBenchmark.kt",
                    "canvas-core/build.gradle.kts", "fixtures/scale/manifest.json").forEach { path ->
                    val bytes = File(root, path).readText(Charsets.UTF_8).replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
                    put(path, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
                }
            }
            put("cases", JsonArray(results))
        }
        File(root, "reports/spatial-index-poc.json").writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report) + "\n", Charsets.UTF_8)
        println("M-3 desktop experiment passed: 4 datasets, 202 queries each, before/after 100 moves. See reports/spatial-index-poc.json")
    }
}
