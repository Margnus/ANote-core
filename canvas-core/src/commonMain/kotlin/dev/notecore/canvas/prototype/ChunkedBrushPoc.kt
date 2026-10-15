package dev.notecore.canvas.prototype

import kotlin.math.*

internal data class BrushTileGridPoc(val origin: PointD, val width: Int, val height: Int, val pixelSize: Double = 1.0, val samplesPerAxis: Int = 2) {
    init {
        require(width in 1..128 && height in 1..128 && width * height <= 16384)
        require(pixelSize.isFinite() && pixelSize in 0.0001..1e6 && samplesPerAxis in listOf(1, 2, 4))
        require(abs(origin.x) <= 1e9 && abs(origin.y) <= 1e9)
    }
    val bounds: BoundsD get() = BoundsD(origin.x, origin.y, origin.x + width * pixelSize, origin.y + height * pixelSize)
}

internal sealed interface ChunkedBrushBuild {
    data class Ready(val catalog: ChunkedBrushPoc) : ChunkedBrushBuild
    data class Rejected(val reason: String) : ChunkedBrushBuild
    data object Cancelled : ChunkedBrushBuild
}

internal sealed interface BrushTileBuild {
    data class Ready(val tile: BrushTileMaskPoc, val candidateChunks: Int, val builtChunks: Int,
        val generatedVertices: Long, val edgeTests: Long, val peakWorkingLogicalBytes: Long) : BrushTileBuild
    data class Rejected(val reason: String) : BrushTileBuild
    data object Cancelled : BrushTileBuild
}

/** Per-stroke subpixel union: immutable bits are resolved to opacity only AFTER every chunk.
 * A different stroke needs a different mask and normal canonical z-order compositing.
 */
internal class BrushTileMaskPoc internal constructor(
    val frame: FrameToken, val geometryKey: GeometryCacheKey, val ownerLayer: LayerId,
    val grid: BrushTileGridPoc, private val coverage: IntArray, val sourceAlpha: Double,
    val sceneTolerance: Double, val segmentsPerChunk: Int,
) {
    val ruleId = "chunked-subpixel-union-draft-1"
    val logicalBytes: Long get() = coverage.size * 4L
    fun coverageBits(x: Int, y: Int): Int { require(x in 0 until grid.width && y in 0 until grid.height); return coverage[y * grid.width + x] }
    fun alphaByte(x: Int, y: Int) = (coverageBits(x, y).countOneBits() * sourceAlpha * 255 / (grid.samplesPerAxis * grid.samplesPerAxis)).roundToInt()
    fun canPublish(gate: FramePublicationGate, expectedLayer: LayerId) = ownerLayer == expectedLayer && gate.canPublish(frame)
}

/** Immutable per-stroke chunk bounds/index. Shared boundary sample covers every original edge.
 * Not a second Scene, a render planner, global cache or app rendering backend.
 */
internal class ChunkedBrushPoc private constructor(
    private val captured: FrameToken, private val stroke: StrokeObject, val segmentsPerChunk: Int,
    private val boxes: DoubleArray, private val index: PackedRTreePoc,
) {
    // Opaque derived-cache identity: keys must not retain the catalog/canonical sample arrays.
    internal val cacheIdentity = Any()
    internal val contentFrame: FrameToken get() = captured
    val ownerLayer: LayerId get() = stroke.layerId
    // Conservative per-job retention charge, even when several jobs share the same source.
    // Index object headers/references are excluded; catalog count is separately bounded by host.
    val inputPayloadBytes: Long get() = stroke.samples.size * 28L + boundsPayloadBytes
    val chunkCount: Int get() = boxes.size / 4
    val boundsPayloadBytes: Long get() = boxes.size * 8L
    val indexNodeCount: Int get() = index.nodeCount
    val geometryKey = GeometryCacheKey(captured.sceneId, stroke.id, stroke.objectRevision, stroke.brush.kind, stroke.brush.algorithmVersion)
    fun chunkStart(id: Int): Int { require(id in 0 until chunkCount); return id * segmentsPerChunk }
    fun chunkEnd(id: Int) = minOf(stroke.samples.size - 1, chunkStart(id) + segmentsPerChunk)
    fun chunkBounds(id: Int): BoundsD {
        require(id in 0 until chunkCount)
        return BoundsD(boxes[id * 4], boxes[id * 4 + 1], boxes[id * 4 + 2], boxes[id * 4 + 3])
    }
    fun queryChunks(bounds: BoundsD): SpatialQuery = index.query(bounds)

    fun render(frame: FrameToken, grid: BrushTileGridPoc, sceneTolerance: Double = 0.05,
        maxWorkingBytes: Long = 1024 * 1024, maxCandidateChunks: Int = 128, maxEdgeTests: Long = 20_000_000,
        cancelled: () -> Boolean = { false },
    ): BrushTileBuild {
        require(maxWorkingBytes > 0 && maxCandidateChunks > 0 && maxEdgeTests > 0)
        require(sceneTolerance.isFinite() && sceneTolerance in 0.0001..4.0)
        if (cancelled()) return BrushTileBuild.Cancelled
        if (frame.sceneId != captured.sceneId || frame.sceneRevision != captured.sceneRevision) return BrushTileBuild.Rejected("content-frame-mismatch")
        val candidates = queryChunks(grid.bounds).ids.sorted()
        if (candidates.size > maxCandidateChunks) return BrushTileBuild.Rejected("candidate-budget")
        val maskBytes = grid.width * grid.height * 4L
        val stableBytes = maskBytes + candidates.size * 4L
        if (stableBytes > maxWorkingBytes) return BrushTileBuild.Rejected("working-budget")
        val mask = IntArray(grid.width * grid.height)
        var vertices = 0L; var work = 0L; var peakWorking = stableBytes; var built = 0; var fullPixels = 0
        val ss = grid.samplesPerAxis; val full = (1 shl (ss * ss)) - 1
        fun alpha() = (stroke.brush.argb ushr 24) / 255.0 * if (stroke.brush.kind == BrushKind.HIGHLIGHTER) 0.25 else 1.0

        for (id in candidates) {
            if (fullPixels == mask.size) break // Union is complete at every subpixel; later chunks cannot change it.
            if (cancelled()) return BrushTileBuild.Cancelled
            val start = chunkStart(id); val end = chunkEnd(id); val count = end - start + 1
            // Caller input is already canonical/owned. Account both temporary projection copies.
            val projectionBytes = count * 56L
            val available = maxWorkingBytes - stableBytes - projectionBytes
            if (available <= 0) return BrushTileBuild.Rejected("working-budget")
            val xs = DoubleArray(count); val ys = DoubleArray(count)
            val pressure = FloatArray(count); val time = LongArray(count)
            repeat(count) { i ->
                xs[i] = stroke.samples.x(start + i); ys[i] = stroke.samples.y(start + i)
                pressure[i] = stroke.samples.pressure(start + i); time[i] = stroke.samples.timeMicros(start + i)
            }
            val input = StrokeSamples.copyOf(xs, ys, pressure, time)
            val produced = when (val result = ReducedBrushPathPoc.build(input, stroke.brush, stroke.transform,
                sceneTolerance, maxWorkingBytes = available, cancelled = cancelled)) {
                is ReducedBrushPathBuild.Ready -> result
                is ReducedBrushPathBuild.Rejected -> return BrushTileBuild.Rejected(result.reason)
                ReducedBrushPathBuild.Cancelled -> return BrushTileBuild.Cancelled
            }
            val path = produced.path
            peakWorking = maxOf(peakWorking, stableBytes + projectionBytes + produced.workingLogicalBytes)
            vertices += path.vertexCount; built++

            for (contour in 0 until path.contourCount) {
                val first = path.contourStart(contour); val last = path.contourEnd(contour)
                var left = Double.POSITIVE_INFINITY; var top = Double.POSITIVE_INFINITY
                var right = Double.NEGATIVE_INFINITY; var bottom = Double.NEGATIVE_INFINITY
                for (i in first until last) {
                    if (work >= maxEdgeTests) return BrushTileBuild.Rejected("raster-work-budget")
                    if (work++ % 256 == 0L && cancelled()) return BrushTileBuild.Cancelled
                    left = minOf(left, path.x(i)); top = minOf(top, path.y(i))
                    right = maxOf(right, path.x(i)); bottom = maxOf(bottom, path.y(i))
                }
                val x0 = floor((left - grid.origin.x) / grid.pixelSize).coerceIn(0.0, grid.width.toDouble()).toInt()
                val x1 = floor((right - grid.origin.x) / grid.pixelSize).coerceIn(-1.0, grid.width - 1.0).toInt()
                val y0 = floor((top - grid.origin.y) / grid.pixelSize).coerceIn(0.0, grid.height.toDouble()).toInt()
                val y1 = floor((bottom - grid.origin.y) / grid.pixelSize).coerceIn(-1.0, grid.height - 1.0).toInt()
                for (y in y0..y1) for (x in x0..x1) {
                    val pixel = y * grid.width + x
                    if (mask[pixel] == full) continue
                    for (sy in 0 until ss) for (sx in 0 until ss) {
                        val bit = 1 shl (sy * ss + sx)
                        if (mask[pixel] and bit != 0) continue
                        val px = grid.origin.x + (x + (sx + 0.5) / ss) * grid.pixelSize
                        val py = grid.origin.y + (y + (sy + 0.5) / ss) * grid.pixelSize
                        var winding = 0
                        for (i in first until last) {
                            if (work >= maxEdgeTests) return BrushTileBuild.Rejected("raster-work-budget")
                            if (work++ % 256 == 0L && cancelled()) return BrushTileBuild.Cancelled
                            val j = if (i + 1 == last) first else i + 1
                            val cross = (path.x(j) - path.x(i)) * (py - path.y(i)) - (px - path.x(i)) * (path.y(j) - path.y(i))
                            if (path.y(i) <= py && path.y(j) > py && cross > 0) winding++
                            if (path.y(i) > py && path.y(j) <= py && cross < 0) winding--
                        }
                        if (winding != 0) {
                            val before = mask[pixel]
                            mask[pixel] = before or bit
                            if (mask[pixel] == full && before != full) fullPixels++
                        }
                    }
                }
            }
        }
        if (cancelled()) return BrushTileBuild.Cancelled
        return BrushTileBuild.Ready(BrushTileMaskPoc(frame, geometryKey, stroke.layerId, grid, mask, alpha(), sceneTolerance, segmentsPerChunk), candidates.size, built, vertices, work, peakWorking)
    }

    companion object {
        fun build(frame: FrameToken, stroke: StrokeObject, segmentsPerChunk: Int = 128,
            maxChunks: Int = 4096, maxBoundsBytes: Long = 128 * 1024, cancelled: () -> Boolean = { false },
        ): ChunkedBrushBuild {
            require(segmentsPerChunk in 1..1024 && maxChunks in 1..4096 && maxBoundsBytes > 0)
            if (cancelled()) return ChunkedBrushBuild.Cancelled
            if (stroke.samples.size > 200000) return ChunkedBrushBuild.Rejected("sample-budget")
            if (stroke.brush.algorithmVersion != 1) return ChunkedBrushBuild.Rejected("unsupported-brush-version")
            if (stroke.transform.inverseOrNull() == null) return ChunkedBrushBuild.Rejected("noninvertible-transform")
            val chunks = maxOf(1, (stroke.samples.size - 1 + segmentsPerChunk - 1) / segmentsPerChunk)
            if (chunks > maxChunks || chunks * 32L > maxBoundsBytes) return ChunkedBrushBuild.Rejected("catalog-budget")
            val boxes = DoubleArray(chunks * 4)
            val entries = ArrayList<SpatialEntry>(chunks)
            for (id in 0 until chunks) {
                val start = id * segmentsPerChunk; val end = minOf(stroke.samples.size - 1, start + segmentsPerChunk)
                var left = Double.POSITIVE_INFINITY; var top = Double.POSITIVE_INFINITY
                var right = Double.NEGATIVE_INFINITY; var bottom = Double.NEGATIVE_INFINITY
                for (i in start..end) {
                    if (i % 256 == 0 && cancelled()) return ChunkedBrushBuild.Cancelled
                    left = minOf(left, stroke.samples.x(i)); right = maxOf(right, stroke.samples.x(i))
                    top = minOf(top, stroke.samples.y(i)); bottom = maxOf(bottom, stroke.samples.y(i))
                }
                val r = stroke.brush.width / 2
                val t = stroke.transform
                val x0 = left - r; val x1 = right + r; val y0 = top - r; val y1 = bottom + r
                val xs = doubleArrayOf(t.a * x0 + t.c * y0 + t.tx, t.a * x1 + t.c * y0 + t.tx,
                    t.a * x1 + t.c * y1 + t.tx, t.a * x0 + t.c * y1 + t.tx)
                val ys = doubleArrayOf(t.b * x0 + t.d * y0 + t.ty, t.b * x1 + t.d * y0 + t.ty,
                    t.b * x1 + t.d * y1 + t.ty, t.b * x0 + t.d * y1 + t.ty)
                if (xs.any { !it.isFinite() || abs(it) > 1e12 } || ys.any { !it.isFinite() || abs(it) > 1e12 })
                    return ChunkedBrushBuild.Rejected("coordinate-range")
                val bounds = BoundsD(xs.min(), ys.min(), xs.max(), ys.max())
                boxes[id * 4] = bounds.left; boxes[id * 4 + 1] = bounds.top
                boxes[id * 4 + 2] = bounds.right; boxes[id * 4 + 3] = bounds.bottom
                entries.add(SpatialEntry(id, bounds))
            }
            if (cancelled()) return ChunkedBrushBuild.Cancelled
            val index = PackedRTreePoc(entries)
            if (cancelled()) return ChunkedBrushBuild.Cancelled
            return ChunkedBrushBuild.Ready(ChunkedBrushPoc(frame, stroke, segmentsPerChunk, boxes, index))
        }
    }
}
