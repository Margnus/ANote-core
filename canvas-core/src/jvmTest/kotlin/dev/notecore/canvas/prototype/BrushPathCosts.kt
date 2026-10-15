package dev.notecore.canvas.prototype

import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.system.measureNanoTime
import kotlinx.serialization.json.*

/** Single-pass exploratory desktop costs, kept separate from correctness test pass criteria. */
object BrushPathCosts {
    private fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun read(file: File, expectedStrokes: Int, expectedPoints: Int, consume: (StrokeSamples) -> Unit) {
        DataInputStream(file.inputStream().buffered()).use { input ->
            val header = ByteArray(16).also(input::readFully)
            require(header.copyOfRange(0, 8).decodeToString() == "NCSCALE1")
            val h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            require(h.getInt(8) == expectedStrokes && h.getInt(12) == expectedPoints)
            val bytes = ByteArray(28); val point = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            repeat(expectedStrokes) {
                val xs = DoubleArray(expectedPoints); val ys = DoubleArray(expectedPoints)
                val pressure = FloatArray(expectedPoints); val time = LongArray(expectedPoints)
                repeat(expectedPoints) { i ->
                    input.readFully(bytes); xs[i] = point.getDouble(0); ys[i] = point.getDouble(8)
                    pressure[i] = point.getFloat(16); time[i] = point.getLong(20)
                }
                consume(StrokeSamples.copyOf(xs, ys, pressure, time))
            }
            require(input.read() == -1)
        }
    }
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.single())
        val manifestFile = File(root, "fixtures/scale/manifest.json")
        val profiles = Json.parseToJsonElement(manifestFile.readText(Charsets.UTF_8)).jsonObject.getValue("profiles").jsonArray
        fun fixture(id: String): File {
            val profile = profiles.single { it.jsonObject.getValue("id").jsonPrimitive.content == id }.jsonObject
            val file = File(root, ".generated-fixtures/scale/${profile.getValue("file").jsonPrimitive.content}")
            require(digest(file) == profile.getValue("sha256").jsonPrimitive.content)
            return file
        }
        val dense = fixture("S2-ink-dense-page"); val longStroke = fixture("S9-ink-long-stroke")
        val results = BrushKind.entries.map { kind ->
            val times = mutableListOf<Long>(); var totalVertices = 0L; var totalLogicalBytes = 0L; var peakLogical = 0L
            val brush = BrushSpec(kind, 1, 10.0, -1)
            read(dense, 10000, 100) { samples ->
                // Fixed preheat only on the first stroke; timings across subsequent strokes
                // share JIT/GC and are not independent repetitions or mobile throughput.
                if (times.isEmpty()) repeat(100) { check(BrushPathPoc.build(samples, brush) is BrushPathBuild.Ready) }
                var path: BrushPathPoc? = null
                val ns = measureNanoTime { path = (BrushPathPoc.build(samples, brush) as BrushPathBuild.Ready).path }
                times.add(ns)
                totalVertices += path!!.vertexCount; totalLogicalBytes += path!!.logicalBytes
                peakLogical = maxOf(peakLogical, path!!.logicalBytes)
            }
            val sorted = times.sorted()
            buildJsonObject {
                put("kind", kind.name); put("strokes", times.size); put("source_points", 1000000)
                put("total_vertices", totalVertices); put("sum_output_logical_bytes", totalLogicalBytes)
                put("max_single_output_logical_bytes", peakLogical)
                put("build_total_ns", times.sum()); put("build_median_ns", sorted[sorted.size / 2]); put("build_p95_ns", sorted[sorted.size * 95 / 100]); put("build_max_ns", sorted.last())
            }
        }
        var longResult = "not-run"; var longSamples = 0; var longNanos = 0L
        read(longStroke, 1, 100000) { samples ->
            longSamples = samples.size
            longNanos = measureNanoTime {
                longResult = (BrushPathPoc.build(samples, BrushSpec(BrushKind.MONOLINE, 1, 10.0, -1)) as BrushPathBuild.Rejected).reason
            }
            require(samples.size == 100000 && longResult == "output-budget")
        }
        val report = buildJsonObject {
            put("schema", "brush-path-cost-poc-1"); put("status", "passed")
            put("java", System.getProperty("java.runtime.version")); put("os", System.getProperty("os.name")); put("arch", System.getProperty("os.arch"))
            put("scope", "Desktop JVM one pass per brush, 100 initial warmup strokes. Build timings exclude fixture IO/sample copying, include output allocation and possible GC. Outputs released per stroke. No wall-clock threshold.")
            put("scene_tolerance", 0.05); put("max_vertices_per_output", 500000); put("max_bytes_per_output", 16777216)
            put("actual_heap_measured", false); put("physical_device_measured", false); put("native_raster_cost_measured", false)
            put("long_stroke_samples_retained", longSamples); put("long_stroke_result", longResult); put("long_stroke_admission_ns", longNanos)
            put("dense_fixture_sha256", digest(dense)); put("long_fixture_sha256", digest(longStroke))
            putJsonObject("source_sha256") {
                listOf("canvas-core/src/commonMain/kotlin/dev/notecore/canvas/prototype/BrushPathPoc.kt",
                    "canvas-core/src/commonMain/kotlin/dev/notecore/canvas/prototype/Stroke.kt",
                    "canvas-core/src/commonMain/kotlin/dev/notecore/canvas/prototype/Geometry.kt",
                    "canvas-core/src/jvmTest/kotlin/dev/notecore/canvas/prototype/BrushPathCosts.kt",
                    "canvas-core/build.gradle.kts", "fixtures/scale/manifest.json").forEach { name ->
                    val bytes = File(root, name).readText(Charsets.UTF_8).replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
                    put(name, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
                }
            }
            put("brushes", JsonArray(results))
        }
        File(root, "reports/brush-path-costs.json").writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report) + "\n", Charsets.UTF_8)
        println("M-2B S2 cost experiment: 3 brushes x 1,000,000 points; S9 output admission explicitly rejected with all samples retained")
    }
}
