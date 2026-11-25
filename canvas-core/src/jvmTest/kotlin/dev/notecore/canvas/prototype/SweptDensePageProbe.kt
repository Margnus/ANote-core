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
    private data class Item(val bounds: BoundsD, val stroke: StrokeObject?, val rgb: Int)
    private data class Totals(
        var pairs: Int = 0,
        var vertices: Long = 0,
        var steps: Long = 0,
        var compositeChannels: Long = 0,
        var rectangleSamples: Long = 0,
        var peakTransient: Long = 0,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val source = read(File(args.single()))
        val moved = source.mapIndexed { index, stroke ->
            if (index < 1_000) stroke.copy(transform = Affine2D(tx = 32.0, ty = 8.0)) else stroke
        }
        check(source.zip(moved).count { (a, b) -> a.transform != b.transform } == 1_000)
        check(source.zip(moved).all { (a, b) -> a.samples === b.samples })
        measure("base", 1, source)
        measure("moved-1000", 2, moved)
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

    private fun measure(label: String, revision: Long, strokes: List<StrokeObject>) {
        val start = System.nanoTime()
        val frame = FrameToken(SceneId("s2-dense-raster"), revision, 1, 1, 1, 1)
        val items = buildList {
            strokes.forEachIndexed { index, stroke ->
                val bounds = assertCatalog(frame, stroke).chunkBounds(0)
                add(Item(bounds, stroke, stroke.brush.argb and 0xffffff))
                if ((index + 1) % 100 == 0) {
                    val y = index / 100 * 20.0
                    add(Item(BoundsD(16.0, y, 40.0, y + 8.0), null, 0x903424))
                }
            }
        }
        val entries = items.mapIndexed { id, item -> SpatialEntry(id, item.bounds) }
        val index = PackedRTreePoc(entries)
        val indexed = System.nanoTime()
        val output = BrushTilePixelsPoc.Companion.Composition(frame, layer, grid)
        val totals = Totals()
        val visited = BooleanArray(items.size)
        val atlasBytes = grid.width.toLong() * grid.height * 4
        val sampleBytes = strokes.sumOf { it.samples.size * 28L }
        // Explicit payload model for item bounds plus tree bounds. JVM headers, collections and GC are outside this model.
        val boundBytes = (items.size + index.nodeCount) * 32L
        for (top in 0 until grid.height step 32) {
            val tile = BrushTileGridPoc(PointD(grid.origin.x, grid.origin.y + top * grid.pixelSize),
                grid.width, minOf(32, grid.height - top), grid.pixelSize, grid.samplesPerAxis)
            val candidates = index.query(tile.bounds).ids
            check(candidates == entries.filter { it.bounds.overlaps(tile.bounds) }.map { it.id }.toSet())
            for (id in candidates.sorted()) {
                val item = items[id]
                visited[id] = true
                totals.pairs++
                totals.compositeChannels += tile.width * tile.height * 4L
                val mask = if (item.stroke == null) {
                    totals.rectangleSamples += tile.width * tile.height * tile.samplesPerAxis * tile.samplesPerAxis.toLong()
                    totals.peakTransient = maxOf(totals.peakTransient, tile.width * tile.height * 4L)
                    rectangle(frame, tile, id, item.bounds)
                } else {
                    val catalog = assertCatalog(frame, item.stroke)
                    val ready = catalog.renderSwept(frame, tile)
                    check(ready is BrushTileBuild.Ready) { "$label id=$id top=$top $ready" }
                    totals.vertices += ready.generatedVertices
                    totals.steps += ready.edgeTests
                    totals.peakTransient = maxOf(totals.peakTransient,
                        ready.peakWorkingLogicalBytes + catalog.boundsPayloadBytes)
                    ready.tile
                }
                check(output.over(mask, item.rgb, { false }, destY = top))
            }
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
    }

    private fun assertCatalog(frame: FrameToken, stroke: StrokeObject): ChunkedBrushPoc {
        val result = ChunkedBrushPoc.build(frame, stroke)
        check(result is ChunkedBrushBuild.Ready) { result.toString() }
        check(result.catalog.chunkCount == 1)
        return result.catalog
    }

    private fun rectangle(frame: FrameToken, tile: BrushTileGridPoc, id: Int, bounds: BoundsD): BrushTileMaskPoc {
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
        return BrushTileMaskPoc(frame, GeometryCacheKey(frame.sceneId, ObjectId("rect-$id"), 1, BrushKind.MONOLINE, 1),
            layer, tile, coverage, 0.5, 0.125, 128, "synthetic-rectangle")
    }
}
