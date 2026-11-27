package dev.notecore.canvas.prototype

import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Isolated full synthetic page accounting. Rectangles stand in for mixed drawable objects. */
object SweptDensePageProbe {
    private val layer = LayerId("ink")
    private val grid = BrushFrameGridPoc(PointD(-8.0, -8.0), 32, 512, pixelSize = 4.0, samplesPerAxis = 2)
    private const val WORK_LIMIT = 20_000_000L
    private const val CACHE_BYTES = 48L * 1024

    private data class TilePlan(val grid: BrushTileGridPoc, val candidates: List<Int>, val stamp: LongArray)
    private data class CachedTile(val stamp: LongArray, val rgba: ByteArray) {
        val logicalBytes: Long get() = rgba.size + stamp.size * 8L
    }
    private data class Item(
        val id: Int,
        val revision: Long,
        val bounds: BoundsD,
        val stroke: StrokeObject?,
        val rgb: Int,
    )
    private data class TraceResult(val hits: Int, val invalidated: Int, val evictions: Int)
    private data class StableDependency(val id: Int, val revision: Long)
    private data class LayerCheckpoint(val key: CompositionTileKeyPoc<StableDependency>, val pixels: BrushTilePixelsPoc,
        val keyEntries: Int,
    ) {
        val logicalBytes: Long get() = pixels.logicalBytes + keyEntries * 16L
    }
    private data class Totals(
        var pairs: Int = 0,
        var vertices: Long = 0,
        var steps: Long = 0,
        var compositeChannels: Long = 0,
        var rectangleSamples: Long = 0,
        var copiedChannels: Long = 0,
        var peakTransient: Long = 0,
    ) {
        val work: Long get() = steps + compositeChannels + rectangleSamples + copiedChannels
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val source = read(File(args.single()))
        val moved = source.mapIndexed { index, stroke ->
            if (index < 1_000) stroke.copy(objectRevision = 2, transform = Affine2D(tx = 32.0, ty = 8.0)) else stroke
        }
        check(source.zip(moved).count { (a, b) -> a.transform != b.transform } == 1_000)
        check(source.zip(moved).all { (a, b) -> a.samples === b.samples })
        val cache = WeightedLruCache<Int, CachedTile>(CACHE_BYTES) { it.logicalBytes }
        measure("base", 1, source, cache, listOf(0, 32, 64, 32, 0))
        val result = measure("moved-1000", 2, moved, cache, listOf(64, 0, 32, 96, 128, 160, 64))
        check(result.invalidated == 2 && result.evictions > 0)
        verifyCompositionDependencies()
    }

    private fun read(file: File): List<StrokeObject> = DataInputStream(file.inputStream().buffered()).use { input ->
        val header = ByteArray(16).also(input::readFully)
        check(header.copyOfRange(0, 8).decodeToString() == "NCSCALE1")
        val counts = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        check(counts.getInt(8) == 10_000 && counts.getInt(12) == 100)
        val record = ByteArray(28)
        val data = ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN)
        val strokes = List(10_000) { id ->
            val xs = DoubleArray(100)
            val ys = DoubleArray(100)
            val pressure = FloatArray(100)
            val time = LongArray(100)
            for (i in xs.indices) {
                input.readFully(record)
                xs[i] = data.getDouble(0)
                ys[i] = data.getDouble(8)
                pressure[i] = data.getFloat(16)
                time[i] = data.getLong(20)
            }
            val kind = BrushKind.entries[id % BrushKind.entries.size]
            StrokeObject(ObjectId("s2-$id"), 1, layer, BrushSpec(kind, 1, 4.0, 0xff395284.toInt()),
                StrokeSamples.copyOf(xs, ys, pressure, time), Affine2D())
        }
        check(input.read() == -1)
        strokes
    }

    private fun measure(label: String, revision: Long, strokes: List<StrokeObject>,
        cache: WeightedLruCache<Int, CachedTile>, trace: List<Int>,
    ): TraceResult {
        val start = System.nanoTime()
        val frame = FrameToken(SceneId("s2-dense-raster"), revision, 1, 1, 1, 1)
        val items = denseItems(strokes, frame)
        val entries = items.mapIndexed { id, item -> SpatialEntry(id, item.bounds) }
        val index = PackedRTreePoc(entries)
        val indexed = System.nanoTime()
        val output = BrushTilePixelsPoc.Companion.Composition(frame, layer, grid)
        val totals = Totals()
        val atlasBytes = grid.width.toLong() * grid.height * 4
        val sampleBytes = strokes.sumOf { it.samples.size * 28L }
        // Explicit payload model for item bounds plus tree bounds. JVM headers, collections and GC are outside this model.
        val boundBytes = (items.size + index.nodeCount) * 32L
        val plans = planTiles(items, entries, index, (0 until grid.height step 32).toList())
        val visited = BooleanArray(items.size)
        plans.forEach { (top, plan) ->
            plan.candidates.forEach { visited[it] = true }
            check(composeTile(frame, items, plan, output, totals, Long.MAX_VALUE, top))
        }
        check(visited.all { it })
        val pixels = output.finish()
        val digest = MessageDigest.getInstance("SHA-256")
        for (i in 0 until pixels.logicalBytes.toInt()) digest.update(pixels.byteAt(i).toByte())
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        val rendered = System.nanoTime()
        println("S2 page label=$label objects=${items.size} pairs=${totals.pairs} vertices=${totals.vertices} " +
            "edgeTests=${totals.steps} compositeChannels=${totals.compositeChannels} rectangleSamples=${totals.rectangleSamples} sampleBytes=$sampleBytes boundBytes=$boundBytes atlasBytes=$atlasBytes " +
            "peakTransientLogicalBytes=${totals.peakTransient} measuredLogicalSubtotal=${sampleBytes + boundBytes + atlasBytes + totals.peakTransient} " +
            "indexNs=${indexed - start} renderingAndDigestNs=${rendered - indexed} rgbaSha256=$hash")
        val result = verifyTrace(label, frame, items, plans, pixels, cache, trace)
        check(result.hits in 1 until trace.size)
        if (label == "base") verifyUnresolvedCosts(items)
        return result
    }

    private fun denseItems(strokes: List<StrokeObject>, frame: FrameToken): List<Item> = buildList {
        strokes.forEachIndexed { index, stroke ->
            val bounds = assertCatalog(frame, stroke).chunkBounds(0)
            add(Item(size, stroke.objectRevision, bounds, stroke, stroke.brush.argb and 0xffffff))
            if ((index + 1) % 100 == 0) {
                val y = index / 100 * 20.0
                add(Item(size, 1, BoundsD(16.0, y, 40.0, y + 8.0), null, 0x903424))
            }
        }
    }

    private fun planTiles(items: List<Item>, entries: List<SpatialEntry>, index: PackedRTreePoc,
        tops: List<Int>,
    ): Map<Int, TilePlan> = tops.associateWith { top ->
        val tile = BrushTileGridPoc(PointD(grid.origin.x, grid.origin.y + top * grid.pixelSize),
            grid.width, minOf(32, grid.height - top), grid.pixelSize, grid.samplesPerAxis)
        val candidates = index.query(tile.bounds).ids
        check(candidates == entries.filter { it.bounds.overlaps(tile.bounds) }.map { it.id }.toSet())
        val ordered = candidates.sorted()
        // The index returns positions in the current canonical order. Cache identity uses stable
        // item IDs, with a revision covering every pixel dependency within this fixed-grid probe.
        val stamp = LongArray(ordered.size * 2)
        ordered.forEachIndexed { offset, position ->
            val item = items[position]
            stamp[offset * 2] = item.id.toLong()
            stamp[offset * 2 + 1] = item.revision
        }
        TilePlan(tile, ordered, stamp)
    }

    private fun verifyCompositionDependencies() {
        val scene = SceneId("s2-composite-identity")
        val samples = StrokeSamples.copyOf(doubleArrayOf(12.0, 36.0), doubleArrayOf(20.0, 20.0),
            floatArrayOf(0.5f, 0.5f), longArrayOf(0, 1))
        fun stroke(id: Int, color: Int): Item {
            val ink = StrokeObject(ObjectId("identity-$id"), 1, layer,
                BrushSpec(BrushKind.HIGHLIGHTER, 1, 12.0, color), samples, Affine2D())
            val frame = FrameToken(scene, 1, 1, 1, 1, 1)
            return Item(id, ink.objectRevision, assertCatalog(frame, ink).chunkBounds(0), ink, color and 0xffffff)
        }
        val blue = stroke(11, 0xff0039cf.toInt())
        val red = stroke(12, 0xffc93300.toInt())
        val proxy = Item(20, 1, BoundsD(16.0, 16.0, 40.0, 24.0), null, 0x205888)
        val unrelated = Item(21, 1, BoundsD(16.0, 136.0, 40.0, 144.0), null, 0x226644)
        val changedProxy = proxy.copy(revision = 2, rgb = 0x4366b8)
        val baseline = listOf(blue, proxy, red, unrelated)
        val reordered = listOf(red, proxy, blue, unrelated)
        val positionsChanged = listOf(unrelated, red, proxy, blue)
        val replaced = listOf(unrelated, red, changedProxy, blue)
        val versions = listOf(baseline, baseline, reordered, positionsChanged, replaced, replaced)
        val expected = listOf(
            TraceResult(0, 0, 0), TraceResult(2, 0, 0), TraceResult(1, 1, 0),
            TraceResult(2, 0, 0), TraceResult(1, 1, 0), TraceResult(2, 0, 0),
        )
        val cache = WeightedLruCache<Int, CachedTile>(CACHE_BYTES) { it.logicalBytes }
        val tops = listOf(0, 32)
        val pixelBytes = tops.size * 32 * grid.width * 4
        var previous: BrushTilePixelsPoc? = null
        versions.forEachIndexed { version, items ->
            val frame = FrameToken(scene, version + 1L, 1, 1, 1, 1)
            val entries = items.mapIndexed { position, item -> SpatialEntry(position, item.bounds) }
            val plans = planTiles(items, entries, PackedRTreePoc(entries), tops)
            val output = BrushTilePixelsPoc.Companion.Composition(frame, layer, grid)
            plans.forEach { (top, plan) -> check(composeTile(frame, items, plan, output, Totals(), WORK_LIMIT, top)) }
            val cold = output.finish()
            previous?.let { before ->
                val split = pixelBytes / 2
                check((0 until split).any { before.byteAt(it) != cold.byteAt(it) } == (version == 2 || version == 4))
                check((split until pixelBytes).all { before.byteAt(it) == cold.byteAt(it) })
            }
            check(verifyTrace("composition-${version + 1}", frame, items, plans, cold, cache, tops) == expected[version])
            previous = cold
        }
    }

    private fun verifyUnresolvedCosts(source: List<Item>) {
        // Dense brush workload is unchanged. A subset of the existing rectangles models an
        // opaque renderer whose output can change without changing the document revision.
        fun unresolved(item: Item) = item.stroke == null && item.bounds.top < 160
        val tops = listOf(0, 32, 64)
        for (tailOnly in listOf(false, true)) {
            val label = if (tailOnly) "tail" else "interleaved"
            val items = if (tailOnly) source.filterNot(::unresolved) + source.filter(::unresolved) else source
            val changed = items.map { if (unresolved(it)) it.copy(rgb = 0x246e94) else it }
            val entries = items.mapIndexed { i, item -> SpatialEntry(i, item.bounds) }
            val plans = planTiles(items, entries, PackedRTreePoc(entries), tops)
            val priorCold = mutableMapOf<Int, BrushTilePixelsPoc>()
            var initialCold = emptyList<BrushTilePixelsPoc>()
            val strategies = listOf(false, true).associateWith {
                WeightedLruCache<Int, LayerCheckpoint>(CACHE_BYTES) { it.logicalBytes }
            }
            for (sequence in 1L..3L) {
                val frame = FrameToken(SceneId("s2-opaque-$label"), 1, 1, 1, 1, sequence)
                val content = if (sequence == 1L) items else changed
                val cold = plans.mapValues { (top, plan) ->
                    val output = BrushTilePixelsPoc.Companion.Composition(frame, layer, plan.grid)
                    check(composeTile(frame, content, plan, output, Totals(), WORK_LIMIT))
                    output.finish().also { current ->
                        val old = priorCold.put(top, current)
                        if (old != null) {
                            val changedPixels = (0 until current.logicalBytes.toInt())
                                .any { old.byteAt(it) != current.byteAt(it) }
                            check(changedPixels == (sequence == 2L && top != 64))
                        }
                    }
                }
                if (sequence == 1L) initialCold = tops.map(cold::getValue)
                for ((prefixOnly, cache) in strategies) {
                    var fullHits = 0
                    var prefixHits = 0
                    var work = 0L
                    var maxTileWork = 0L
                    for ((top, plan) in plans) {
                        val dependencies = plan.candidates.map { position ->
                            val item = content[position]
                            if (unresolved(item)) null else StableDependency(item.id, item.revision)
                        }
                        val firstUnknown = dependencies.indexOf(null).let { if (it < 0) dependencies.size else it }
                        val cut = if (prefixOnly) firstUnknown else dependencies.size
                        check((firstUnknown < dependencies.size) == (top != 64))
                        val key = CompositionTileKeyPoc.capture(frame, plan.grid, "s2-rgba8",
                            dependencies.take(cut))
                        val totals = Totals()
                        val prior = cache.get(top)
                        val checkpoint = if (prior?.key == key) {
                            if (cut == dependencies.size) fullHits++ else prefixHits++
                            prior
                        } else {
                            val prefix = BrushTilePixelsPoc.Companion.Composition(frame, layer, plan.grid)
                            check(composeTile(frame, content, plan, prefix, totals, WORK_LIMIT, end = cut))
                            LayerCheckpoint(key, prefix.finish(), cut).also {
                                check(cache.put(top, it) is CacheAdmission.Stored)
                            }
                        }
                        val pixels = if (cut == dependencies.size) checkpoint.pixels else {
                            totals.copiedChannels += checkpoint.pixels.logicalBytes
                            val resumed = BrushTilePixelsPoc.Companion.Composition(frame, layer, plan.grid,
                                checkpoint.pixels)
                            check(composeTile(frame, content, plan, resumed, totals, WORK_LIMIT, from = cut))
                            resumed.finish()
                        }
                        val expected = cold.getValue(top)
                        check((0 until pixels.logicalBytes.toInt()).all { pixels.byteAt(it) == expected.byteAt(it) })
                        check(totals.work <= WORK_LIMIT)
                        work += totals.work
                        maxTileWork = maxOf(maxTileWork, totals.work)
                    }
                    if (sequence > 1) {
                        check(fullHits == 1 && prefixHits == if (prefixOnly) 2 else 0)
                    }
                    println("S2 opaque order=$label strategy=${if (prefixOnly) "prefix" else "whole"} " +
                        "request=$sequence fullHits=$fullHits prefixHits=$prefixHits " +
                        "work=$work maxTileWork=$maxTileWork residentLogicalBytes=${cache.residentBytes} coldByteChecks=12288")
                }
                if (!tailOnly && sequence == 3L) {
                    val cache = strategies.getValue(true)
                    val checkpoints = tops.map { checkNotNull(cache.get(it)) }
                    verifyStagedDelivery(items, changed, tops.map(plans::getValue), checkpoints,
                        initialCold, tops.map(cold::getValue), cache.residentBytes)
                }
            }
        }
    }

    private fun verifyStagedDelivery(
        oldItems: List<Item>, currentItems: List<Item>, plans: List<TilePlan>, checkpoints: List<LayerCheckpoint>,
        old: List<BrushTilePixelsPoc>, current: List<BrushTilePixelsPoc>, prefixBytes: Long,
    ) {
        val workingLimit = 64L * 1024
        S2StagedFrameProbe.verify(old, current, prefixBytes, WORK_LIMIT, workingLimit) { frame, slot, limit ->
            val plan = plans[slot]
            val prefix = checkpoints[slot]
            val totals = Totals(copiedChannels = prefix.pixels.logicalBytes)
            if (totals.work > limit) return@verify null
            val output = BrushTilePixelsPoc.Companion.Composition(frame, layer, plan.grid, prefix.pixels)
            val items = if (frame.requestSequence == 4L) oldItems else currentItems
            if (!composeTile(frame, items, plan, output, totals, limit, from = prefix.keyEntries,
                    workingLimit = workingLimit)) return@verify null
            check(totals.peakTransient <= workingLimit)
            S2StagedFrameProbe.TileResult(output.finish(), totals.work)
        }
    }

    private fun composeTile(frame: FrameToken, items: List<Item>, plan: TilePlan,
        output: BrushTilePixelsPoc.Companion.Composition, totals: Totals, workLimit: Long, destY: Int = 0,
        from: Int = 0, end: Int = plan.candidates.size, workingLimit: Long = 1024 * 1024,
    ): Boolean {
        val tile = plan.grid
        val channelSteps = tile.width * tile.height * 4L
        val sampleSteps = tile.width * tile.height * tile.samplesPerAxis * tile.samplesPerAxis.toLong()
        for (position in from until end) {
            val item = items[plan.candidates[position]]
            val remaining = workLimit - totals.work - channelSteps
            if (remaining <= 0) return false
            val mask = if (item.stroke == null) {
                if (remaining < sampleSteps) return false
                totals.rectangleSamples += sampleSteps
                totals.peakTransient = maxOf(totals.peakTransient, tile.width * tile.height * 4L)
                rectangle(frame, tile, item)
            } else {
                val catalog = assertCatalog(frame, item.stroke)
                val ready = when (val rendered = catalog.renderSwept(frame, tile,
                    maxWorkingBytes = workingLimit - catalog.boundsPayloadBytes,
                    maxEdgeTests = minOf(remaining, WORK_LIMIT))) {
                    is BrushTileBuild.Ready -> rendered
                    is BrushTileBuild.Rejected -> {
                        check(rendered.reason in setOf("raster-work-budget", "working-budget")) { rendered.reason }
                        return false
                    }
                    BrushTileBuild.Cancelled -> error("Unexpected cancellation")
                }
                totals.vertices += ready.generatedVertices
                totals.steps += ready.edgeTests
                totals.peakTransient = maxOf(totals.peakTransient,
                    ready.peakWorkingLogicalBytes + catalog.boundsPayloadBytes)
                ready.tile
            }
            totals.pairs++
            totals.compositeChannels += channelSteps
            check(output.over(mask, item.rgb, { false }, destY = destY))
        }
        return true
    }

    private fun renderTile(frame: FrameToken, items: List<Item>, plan: TilePlan, workLimit: Long,
        totals: Totals,
    ): CachedTile? {
        val output = BrushTilePixelsPoc.Companion.Composition(frame, layer, plan.grid)
        if (!composeTile(frame, items, plan, output, totals, workLimit)) return null
        val pixels = output.finish()
        return CachedTile(plan.stamp, ByteArray(pixels.logicalBytes.toInt()) { pixels.byteAt(it).toByte() })
    }

    private fun verifyTrace(label: String, frame: FrameToken, items: List<Item>, plans: Map<Int, TilePlan>,
        coldPage: BrushTilePixelsPoc, cache: WeightedLruCache<Int, CachedTile>, trace: List<Int>,
    ): TraceResult {
        var hits = 0
        var misses = 0
        var stale = 0
        var evictions = 0
        var peakResident = cache.residentBytes
        var maxTileWork = 0L
        var totalWork = 0L
        for (top in trace) {
            val plan = plans.getValue(top)
            val cached = cache.get(top)
            val result = if (cached != null && cached.stamp.contentEquals(plan.stamp)) {
                hits++
                cached
            } else {
                misses++
                if (cached != null) stale++
                val totals = Totals()
                val fresh = checkNotNull(renderTile(frame, items, plan, WORK_LIMIT, totals)) { "budget $label top=$top" }
                val stored = cache.put(top, fresh)
                check(stored is CacheAdmission.Stored)
                evictions += stored.displaced.count { it.key != top }
                peakResident = maxOf(peakResident, cache.residentBytes)
                maxTileWork = maxOf(maxTileWork, totals.work)
                totalWork += totals.work
                if (misses == 1) {
                    val exact = checkNotNull(renderTile(frame, items, plan, totals.work, Totals()))
                    check(exact.rgba.contentEquals(fresh.rgba))
                    check(renderTile(frame, items, plan, totals.work - 1, Totals()) == null)
                }
                fresh
            }
            val byteOffset = top * grid.width * 4
            result.rgba.forEachIndexed { i, byte -> check(byte.toInt() and 255 == coldPage.byteAt(byteOffset + i)) }
        }
        check(peakResident <= CACHE_BYTES && maxTileWork <= WORK_LIMIT)
        println("S2 visible label=$label requests=${trace.size} hits=$hits misses=$misses invalidated=$stale " +
            "evictions=$evictions peakResidentBytes=$peakResident maxTileWork=$maxTileWork totalWork=$totalWork " +
            "coldByteChecks=${trace.size * grid.width * 32 * 4} exactAndMinusOne=${misses > 0}")
        return TraceResult(hits, stale, evictions)
    }

    private fun assertCatalog(frame: FrameToken, stroke: StrokeObject): ChunkedBrushPoc {
        val result = ChunkedBrushPoc.build(frame, stroke)
        check(result is ChunkedBrushBuild.Ready) { result.toString() }
        check(result.catalog.chunkCount == 1)
        return result.catalog
    }

    private fun rectangle(frame: FrameToken, tile: BrushTileGridPoc, item: Item): BrushTileMaskPoc {
        val bounds = item.bounds
        val ss = tile.samplesPerAxis
        val coverage = IntArray(tile.width * tile.height)
        for (y in 0 until tile.height) for (x in 0 until tile.width) for (sy in 0 until ss) for (sx in 0 until ss) {
            val px = tile.origin.x + (x + (sx + 0.5) / ss) * tile.pixelSize
            val py = tile.origin.y + (y + (sy + 0.5) / ss) * tile.pixelSize
            if (px >= bounds.left && px < bounds.right && py >= bounds.top && py < bounds.bottom) {
                val offset = y * tile.width + x
                coverage[offset] = coverage[offset] or (1 shl (sy * ss + sx))
            }
        }
        return BrushTileMaskPoc(frame, GeometryCacheKey(frame.sceneId, ObjectId("rect-${item.id}"), item.revision, BrushKind.MONOLINE, 1),
            layer, tile, coverage, 0.5, 0.125, 128, "synthetic-rectangle")
    }
}
