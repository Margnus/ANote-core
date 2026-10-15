package dev.notecore.canvas.prototype

import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.system.measureNanoTime
import kotlinx.serialization.json.*

/** Compare baseline and exact-run reduction using the same canonical, hash-checked samples. */
object BrushReductionCosts {
    private fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun read(file: File, strokes: Int, points: Int, consume: (StrokeSamples) -> Unit) {
        DataInputStream(file.inputStream().buffered()).use { stream ->
            val header = ByteArray(16).also(stream::readFully)
            val h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            require(header.copyOfRange(0, 8).decodeToString() == "NCSCALE1" && h.getInt(8) == strokes && h.getInt(12) == points)
            val bytes = ByteArray(28); val p = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            repeat(strokes) {
                val xs = DoubleArray(points); val ys = DoubleArray(points); val pressure = FloatArray(points); val times = LongArray(points)
                repeat(points) { i -> stream.readFully(bytes); xs[i] = p.getDouble(0); ys[i] = p.getDouble(8); pressure[i] = p.getFloat(16); times[i] = p.getLong(20) }
                consume(StrokeSamples.copyOf(xs, ys, pressure, times))
            }
            require(stream.read() == -1)
        }
    }
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.single())
        val manifest = Json.parseToJsonElement(File(root, "fixtures/scale/manifest.json").readText(Charsets.UTF_8)).jsonObject
        fun fixture(id: String): File {
            val entry = manifest.getValue("profiles").jsonArray.single { it.jsonObject.getValue("id").jsonPrimitive.content == id }.jsonObject
            val file = File(root, ".generated-fixtures/scale/${entry.getValue("file").jsonPrimitive.content}")
            require(digest(file) == entry.getValue("sha256").jsonPrimitive.content)
            return file
        }
        val dense = fixture("S2-ink-dense-page"); val long = fixture("S9-ink-long-stroke")
        val brushes = BrushKind.entries.map { kind ->
            var sourceCount = 0L; var keptCount = 0L; var baseVertices = 0L; var reducedVertices = 0L
            var baseBytes = 0L; var reducedBytes = 0L; var maxWorking = 0L; var index = 0
            val baseTimes = mutableListOf<Long>(); val reducedTimes = mutableListOf<Long>()
            val brush = BrushSpec(kind, 1, 10.0, -1)
            read(dense, 10000, 100) { input ->
                fun baseline() = (BrushPathPoc.build(input, brush) as BrushPathBuild.Ready).path
                fun optimized() = ReducedBrushPathPoc.build(input, brush) as ReducedBrushPathBuild.Ready
                if (index == 0) repeat(100) { baseline(); optimized() }
                var b: BrushPathPoc? = null; var r: ReducedBrushPathBuild.Ready? = null
                var bt = 0L; var rt = 0L
                if (index % 2 == 0) {
                    bt = measureNanoTime { b = baseline() }; rt = measureNanoTime { r = optimized() }
                } else {
                    rt = measureNanoTime { r = optimized() }; bt = measureNanoTime { b = baseline() }
                }
                baseTimes.add(bt); reducedTimes.add(rt)
                require(r!!.sourceSamples == 100 && input.size == 100 && r!!.path.sourceAlpha == b!!.sourceAlpha)
                sourceCount += input.size; keptCount += r!!.derivedSamples
                baseVertices += b!!.vertexCount; reducedVertices += r!!.path.vertexCount
                baseBytes += b!!.logicalBytes; reducedBytes += r!!.path.logicalBytes
                maxWorking = maxOf(maxWorking, r!!.workingLogicalBytes); index++
            }
            fun timing(values: List<Long>) = buildJsonObject {
                val sorted = values.sorted()
                put("total_ns", values.sum()); put("median_ns", sorted[sorted.size / 2]); put("p95_ns", sorted[sorted.size * 95 / 100]); put("max_ns", sorted.last())
            }
            buildJsonObject {
                put("kind", kind.name); put("strokes", index); put("canonical_samples_retained", sourceCount); put("derived_samples", keptCount)
                put("baseline_vertices", baseVertices); put("reduced_vertices", reducedVertices)
                put("baseline_output_logical_bytes", baseBytes); put("reduced_output_logical_bytes", reducedBytes)
                put("output_byte_reduction_fraction", 1.0 - reducedBytes.toDouble() / baseBytes)
                put("max_derived_working_logical_bytes", maxWorking); put("baseline_build", timing(baseTimes)); put("reduced_build", timing(reducedTimes))
            }
        }
        var longReport: JsonObject? = null
        read(long, 1, 100000) { input ->
            val brush = BrushSpec(BrushKind.MONOLINE, 1, 10.0, -1)
            val base = BrushPathPoc.build(input, brush) as BrushPathBuild.Rejected
            val reduced = ReducedBrushPathPoc.build(input, brush) as ReducedBrushPathBuild.Ready
            require(input.size == 100000 && reduced.sourceSamples == 100000)
            longReport = buildJsonObject {
                put("baseline_result", base.reason); put("reduced_result", "ready")
                put("canonical_samples_retained", input.size); put("derived_samples", reduced.derivedSamples)
                put("vertices", reduced.path.vertexCount); put("output_logical_bytes", reduced.path.logicalBytes)
                put("derived_working_logical_bytes", reduced.workingLogicalBytes)
            }
        }
        // Adversarial fallback: tiny non-lattice jitter forbids exact straight-run removal.
        // This is synthetic data, separate from the immutable S2 fixture and real samples.
        val fallback = BrushKind.entries.map { kind ->
            val brush = BrushSpec(kind, 1, 10.0, -1)
            var baselineNs = 0L; var reducedNs = 0L; var baselineBytes = 0L; var reducedBytes = 0L; var maxWorking = 0L
            repeat(1000) { stroke ->
                val input = StrokeSamples.copyOf(DoubleArray(100) { stroke * 2.0 + it / 4.0 + if (it % 2 == 0) 0.0000001 else 0.0000002 },
                    DoubleArray(100) { (if (it % 64 < 32) it % 32 else 63 - it % 64) / 4.0 },
                    FloatArray(100) { (8 + it % 16) / 32f }, LongArray(100) { it.toLong() })
                if (stroke == 0) repeat(100) { BrushPathPoc.build(input, brush); ReducedBrushPathPoc.build(input, brush) }
                var baseline: BrushPathPoc? = null; var reduced: ReducedBrushPathBuild.Ready? = null
                fun base() { baseline = (BrushPathPoc.build(input, brush) as BrushPathBuild.Ready).path }
                fun optimize() { reduced = ReducedBrushPathPoc.build(input, brush) as ReducedBrushPathBuild.Ready }
                if (stroke % 2 == 0) { baselineNs += measureNanoTime { base() }; reducedNs += measureNanoTime { optimize() } }
                else { reducedNs += measureNanoTime { optimize() }; baselineNs += measureNanoTime { base() } }
                require(reduced!!.derivedSamples == 100 && baseline!!.vertexCount == reduced!!.path.vertexCount)
                require(baseline!!.logicalBytes == reduced!!.path.logicalBytes)
                baselineBytes += baseline!!.logicalBytes; reducedBytes += reduced!!.path.logicalBytes
                maxWorking = maxOf(maxWorking, reduced!!.workingLogicalBytes)
            }
            buildJsonObject {
                put("kind", kind.name); put("strokes", 1000); put("canonical_and_derived_samples", 100000)
                put("baseline_output_logical_bytes", baselineBytes); put("reduced_output_logical_bytes", reducedBytes)
                put("baseline_build_total_ns", baselineNs); put("reduced_build_total_ns", reducedNs)
                put("max_derived_working_logical_bytes", maxWorking)
            }
        }
        val report = buildJsonObject {
            put("schema", "brush-run-reduction-cost-1"); put("status", "passed")
            put("scope", "Same-process desktop JVM comparison, alternating baseline/reduced order; 100 warmups then one pass. Reduced build includes plan and projection copies, both exclude fixture IO/canonical sample creation. Logical payloads, not actual heap. No time threshold.")
            put("java", System.getProperty("java.runtime.version")); put("os", System.getProperty("os.name")); put("arch", System.getProperty("os.arch"))
            put("physical_device_measured", false); put("actual_heap_measured", false); put("native_reduction_verified", false)
            put("scene_tolerance", 0.05); put("dense_fixture_sha256", digest(dense)); put("long_fixture_sha256", digest(long))
            putJsonObject("source_sha256") {
                listOf("canvas-core/src/commonMain/kotlin/dev/notecore/canvas/prototype/BrushReductionPoc.kt",
                    "canvas-core/src/commonMain/kotlin/dev/notecore/canvas/prototype/BrushPathPoc.kt",
                    "canvas-core/src/commonMain/kotlin/dev/notecore/canvas/prototype/BrushEnvelopePoc.kt",
                    "canvas-core/src/commonMain/kotlin/dev/notecore/canvas/prototype/Geometry.kt",
                    "canvas-core/src/commonMain/kotlin/dev/notecore/canvas/prototype/Stroke.kt",
                    "canvas-core/src/jvmTest/kotlin/dev/notecore/canvas/prototype/BrushReductionCosts.kt", "fixtures/scale/manifest.json").forEach { name ->
                    val bytes = File(root, name).readText(Charsets.UTF_8).replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
                    put(name, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
                }
            }
            put("brushes", JsonArray(brushes)); put("long_stroke", longReport!!)
            put("non_lattice_fallback_origin", "Synthetic S2-shaped local jitter; not recorded hardware data or part of S2 fixture")
            put("non_lattice_fallback", JsonArray(fallback))
        }
        File(root, "reports/brush-reduction-costs.json").writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report) + "\n", Charsets.UTF_8)
        println("Compared baseline/reduced S2 for all three brushes; S9 reduced output fits with 100000 canonical samples retained")
    }
}
