package dev.notecore.canvas.prototype

/** One worker-owned atlas; only finish transfers pixels. The scene is canonical and immutable.
 * A slice is bounded preparation, one candidate stroke/tile, or one empty tile. It is NOT vsync.
 * The original executor holds the whole job's reservation across every successful continuation.
 */
internal class StagedBrushPagePoc(private val scene: SceneSnapshot, private val frame: FrameToken,
    private val layer: LayerId, private val grid: BrushPixelGridPoc, private val workingBytes: Long,
    private val maxWork: Long,
) {
    private var output: BrushTilePixelsPoc.Companion.Composition? = null
    private var strokes: List<StrokeObject> = emptyList()
    private var index: PackedRTreePoc? = null
    private var tile: BrushTileGridPoc? = null
    private var candidates: List<Int> = emptyList()
    private var candidate = 0
    private var x = 0
    private var y = 0
    var slices = 0; private set
    var work = 0L; private set
    private var terminal = false
    data class Step(val more: Boolean, val pixels: BrushTilePixelsPoc? = null, val failure: String? = null)

    fun advance(cancelled: () -> Boolean): Step {
        check(!terminal); slices++
        fun fail(reason: String): Step { terminal = true; clear(); return Step(false, failure = reason) }
        if (cancelled()) return fail("cancelled")
        if (output == null) {
            val selected = mutableListOf<StrokeObject>()
            val entries = mutableListOf<SpatialEntry>()
            scene.forEachObject { stroke ->
                if (stroke.layerId == layer && !cancelled()) {
                    entries.add(SpatialEntry(selected.size, stroke.bounds())); selected.add(stroke)
                }
            }
            if (cancelled()) return fail("cancelled")
            work = grid.width * grid.height * 4L + scene.objectCount
            scene.forEachObject { work += it.samples.size }
            if (work > maxWork) return fail("page-work-budget")
            strokes = selected; index = PackedRTreePoc(entries)
            output = BrushTilePixelsPoc.Companion.Composition(frame, layer, grid)
            return Step(true)
        }
        if (tile == null) {
            val next = BrushTileGridPoc(PointD(grid.origin.x + x * grid.pixelSize, grid.origin.y + y * grid.pixelSize),
                minOf(TILE, grid.width - x), minOf(TILE, grid.height - y), grid.pixelSize, grid.samplesPerAxis)
            val query = checkNotNull(index).query(next.bounds)
            work += query.entryTests + query.nodeTests
            if (work > maxWork) return fail("page-work-budget")
            candidates = query.ids.sorted(); candidate = 0; tile = next
        }
        val current = checkNotNull(tile)
        if (candidate < candidates.size) {
            val stroke = strokes[candidates[candidate]]
            val catalogBytes = SweptDiscGeometryPoc.segmentCount(stroke.samples) * 72L
            val renderBytes = workingBytes - catalogBytes
            if (renderBytes <= 0) return fail("page-working-budget")
            val catalog = when (val built = ChunkedBrushPoc.build(frame, stroke, cancelled = cancelled)) {
                is ChunkedBrushBuild.Ready -> built.catalog
                is ChunkedBrushBuild.Rejected -> return fail(built.reason)
                ChunkedBrushBuild.Cancelled -> return fail("cancelled")
            }
            val channels = current.width * current.height * 4L
            val available = minOf(MAX_SLICE_WORK, maxWork - work) - channels
            if (available <= 0) return fail("page-work-budget")
            when (val rendered = catalog.renderSwept(frame, current, maxWorkingBytes = renderBytes,
                maxEdgeTests = available, cancelled = cancelled)) {
                is BrushTileBuild.Ready -> {
                    work += rendered.edgeTests + channels
                    if (!checkNotNull(output).over(rendered.tile, stroke.brush.argb and 0xffffff, cancelled, x, y))
                        return fail("cancelled")
                }
                is BrushTileBuild.Rejected -> return fail(rendered.reason)
                BrushTileBuild.Cancelled -> return fail("cancelled")
            }
            candidate++
        }
        if (candidate == candidates.size) {
            tile = null; candidates = emptyList()
            x += TILE
            if (x >= grid.width) { x = 0; y += TILE }
        }
        if (cancelled()) return fail("cancelled")
        if (y < grid.height) return Step(true)
        val pixels = checkNotNull(output).finish()
        terminal = true; clear()
        return Step(false, pixels)
    }
    private fun clear() { output = null; strokes = emptyList(); index = null; tile = null; candidates = emptyList() }
    companion object {
        const val TILE = 32
        const val MAX_SLICE_WORK = 20_000_000L
        /** Logical reserve for selected references, bounds/tree construction and query scratch.
         * Includes preparation temporaries; not an ART/Kotlin Native heap bound.
         */
        fun planningBytes(objects: Int) = 4096L + objects * 512L
    }
}
