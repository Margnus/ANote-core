package dev.notecore.canvas.prototype

import java.io.File
import java.security.MessageDigest
import kotlin.system.measureNanoTime
import kotlinx.serialization.json.*

/** Deterministic viewport workload and logical retention accounting, not a device benchmark. */
object BrushTileCacheCosts {
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.single())
        val frame = FrameToken(SceneId("cache-trace"), 1, 1, 1, 1, 1)
        val layer = LayerId("ink")
        val n = 100000
        val source = StrokeSamples.copyOf(DoubleArray(n) { it / 4.0 }, DoubleArray(n) { 16.0 },
            FloatArray(n) { 1f }, LongArray(n) { it.toLong() })
        val stroke = StrokeObject(ObjectId("long"), 1, layer, BrushSpec(BrushKind.HIGHLIGHTER, 1, 8.0, -1), source)
        val catalog = (ChunkedBrushPoc.build(frame, stroke) as ChunkedBrushBuild.Ready).catalog
        val bytes = 32 * 16 * 4L
        val cache = BrushTileCachePoc(bytes * 4, bytes * 6)
        fun grid(id: Int) = BrushTileGridPoc(PointD(id * 32.0, 8.0), 32, 16)
        var hits = 0; var misses = 0; var checks = 0; var maxCharged = 0L
        var hitNs = 0L; var missNs = 0L
        val trace = List(4) { listOf(0, 1, 2, 3, 2, 1, 4, 5, 2, 1) }.flatten()
        trace.forEachIndexed { i, id ->
            val current = frame.copy(viewportVersion = i + 1L, requestSequence = i + 1L)
            lateinit var result: CachedBrushTileBuild.Ready
            val ns = measureNanoTime { result = cache.acquire(catalog, current, layer, grid(id)) as CachedBrushTileBuild.Ready }
            if (result.cacheHit) { hits++; hitNs += ns } else { misses++; missNs += ns }
            val reference = (catalog.render(current, grid(id)) as BrushTileBuild.Ready).tile
            for (y in 0 until 16) for (x in 0 until 32) {
                check(reference.coverageBits(x, y) == result.reader.coverageBits(x, y)); checks++
            }
            check(result.reader.canPublish(FramePublicationGate(current), layer))
            maxCharged = maxOf(maxCharged, cache.chargedBytes)
            cache.returnReader(result.reader)
        }
        cache.close(); check(cache.isDrained && source.size == n)

        // Resident budget is one mask; keep two generations borrowed to force total-budget denial.
        val pressure = BrushTileCachePoc(bytes, bytes * 2)
        val a = (pressure.acquire(catalog, frame, layer, grid(0)) as CachedBrushTileBuild.Ready).reader
        val b = (pressure.acquire(catalog, frame, layer, grid(1)) as CachedBrushTileBuild.Ready).reader
        val denial = pressure.acquire(catalog, frame, layer, grid(2)) as CachedBrushTileBuild.Rejected
        check(denial.reason == "cache-total_limit" && pressure.chargedBytes == bytes * 2 && pressure.retiredBytes == bytes)
        val retained = pressure.chargedBytes
        pressure.returnReader(a)
        val afterReturn = pressure.chargedBytes
        val retry = pressure.acquire(catalog, frame, layer, grid(2)) as CachedBrushTileBuild.Ready
        pressure.returnReader(b); pressure.returnReader(retry.reader); pressure.close(); check(pressure.isDrained)

        val sourceFiles = listOf("canvas-core/src/commonMain", "canvas-core/src/commonTest").flatMap { dir ->
            File(root, dir).walkTopDown().filter { it.extension == "kt" }.toList()
        } + File(root, "canvas-core/src/jvmTest/kotlin/dev/notecore/canvas/prototype/BrushTileCacheCosts.kt") + File(root, "canvas-core/build.gradle.kts")
        val report = buildJsonObject {
            put("status", "passed"); put("scope", "JVM synthetic viewport trace; logical packed CPU-mask accounting only")
            put("canonical_points", source.size); put("requests", trace.size); put("hits", hits); put("misses", misses)
            put("coverage_pixels_compared_to_uncached", checks); put("resident_budget_bytes", bytes * 4)
            put("total_budget_bytes", bytes * 6); put("max_post_acquire_charged_bytes", maxCharged)
            put("hit_elapsed_ns_total", hitNs); put("miss_elapsed_ns_total", missNs)
            put("retained_reader_charged_bytes", retained); put("after_old_reader_return_bytes", afterReturn)
            put("budget_rejection", denial.reason); put("retry_after_return", true); put("closed_drained", true)
            put("physical_device_measured", false); put("heap_or_gpu_bytes_measured", false)
            put("excluded", "canonical arrays, chunk catalog/index, render temporaries, object headers, GC delay and native Bitmap/texture copies; timing is one exploratory JVM run without confidence intervals")
            putJsonObject("source_sha256") { sourceFiles.sortedBy { it.path }.forEach { file ->
                val text = file.readText(Charsets.UTF_8).replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
                put(file.relativeTo(root).invariantSeparatorsPath,
                    MessageDigest.getInstance("SHA-256").digest(text).joinToString("") { "%02x".format(it) })
            } }
        }
        File(root, "reports/brush-tile-cache-costs.json").writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report) + "\n", Charsets.UTF_8)
        println("cache trace: requests=${trace.size} hits=$hits misses=$misses compared=$checks charged=$maxCharged drained=true")
    }
}
