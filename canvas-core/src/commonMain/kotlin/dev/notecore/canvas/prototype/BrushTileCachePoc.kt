package dev.notecore.canvas.prototype

internal sealed interface CachedBrushTileBuild {
    data class Ready(val reader: BrushTileReaderPoc, val cacheHit: Boolean) : CachedBrushTileBuild
    data class Rejected(val reason: String) : CachedBrushTileBuild
    data object Cancelled : CachedBrushTileBuild
}

/** The frame belongs to this presentation, never to the cached pixels' historical producer. */
internal class BrushTileReaderPoc internal constructor(
    val frame: FrameToken, private val lease: ResourceLease<BrushTileMaskPoc>,
) {
    val grid: BrushTileGridPoc get() = lease.value.grid
    val ownerLayer: LayerId get() = lease.value.ownerLayer
    fun coverageBits(x: Int, y: Int) = lease.value.coverageBits(x, y)
    fun alphaByte(x: Int, y: Int) = lease.value.alphaByte(x, y)
    fun canPublish(gate: FramePublicationGate, layer: LayerId) = ownerLayer == layer && gate.canPublish(frame)
    internal fun token() = lease
}

/** Synchronous single-owner CPU experiment. No worker/GPU ownership or native image cache implied.
 * Only packed mask bytes are charged; job temporaries have render's independent working budget.
 * Catalog identities are deliberately conservative: a rebuilt catalog misses even with equal IDs.
 * The owner must return every reader; copying pixels or retaining value references outside its
 * lease is not supported. Dropping pool references does not confirm actual GC/heap reclamation.
 */
internal class BrushTileCachePoc(residentBudget: Long, totalBudget: Long, maxResources: Int = 256, maxReaders: Int = 1024) {
    private data class Key(val catalog: Any, val grid: BrushTileGridPoc, val tolerance: Double)
    private val pool = ResourceLeasePool<Key, BrushTileMaskPoc>(residentBudget, totalBudget,
        maxResources, maxReaders, ResourceLane.OWNER)
    val chargedBytes: Long get() = pool.chargedBytes
    val residentBytes: Long get() = pool.residentBytes
    val retiredBytes: Long get() = pool.retiredBytes
    val borrowedBytes: Long get() = pool.borrowedBytes
    val outstandingReaders: Int get() = pool.outstandingLeases
    val isDrained: Boolean get() = pool.isDrained

    fun acquire(catalog: ChunkedBrushPoc, frame: FrameToken, layer: LayerId, grid: BrushTileGridPoc,
        sceneTolerance: Double = 0.05, maxWorkingBytes: Long = 1024 * 1024,
        maxCandidateChunks: Int = 128, maxEdgeTests: Long = 20_000_000,
        cancelled: () -> Boolean = { false },
    ): CachedBrushTileBuild {
        require(sceneTolerance.isFinite() && sceneTolerance in 0.0001..4.0)
        require(maxWorkingBytes > 0 && maxCandidateChunks > 0 && maxEdgeTests > 0)
        val captured = catalog.contentFrame
        if (frame.sceneId != captured.sceneId || frame.sceneRevision != captured.sceneRevision ||
            frame.sessionGeneration != captured.sessionGeneration || layer != catalog.ownerLayer)
            return CachedBrushTileBuild.Rejected("content-owner-mismatch")
        if (cancelled()) return CachedBrushTileBuild.Cancelled
        val key = Key(catalog.cacheIdentity, grid, sceneTolerance)
        when (val existing = pool.borrow(key)) {
            is ResourceBorrow.Borrowed -> return CachedBrushTileBuild.Ready(BrushTileReaderPoc(frame, existing.lease), true)
            ResourceBorrow.Closed -> return CachedBrushTileBuild.Rejected("closed")
            ResourceBorrow.LeaseLimit -> return CachedBrushTileBuild.Rejected("reader-budget")
            ResourceBorrow.Missing -> Unit
        }
        // Do not render and evict useful entries when no result reader can be admitted.
        if (pool.outstandingLeases >= pool.maxLeases) return CachedBrushTileBuild.Rejected("reader-budget")
        releaseUnused()
        val reservation = when (val reserved = pool.reserve(grid.width * grid.height * 4L)) {
            is ReservationResult.Reserved -> reserved.reservation
            is ReservationResult.Rejected -> return CachedBrushTileBuild.Rejected("cache-${reserved.reason.name.lowercase()}")
        }
        // Failed/cancelled render owns and drops all private job allocations. No cache output is
        // transferred in that case; the reservation concerns retained output, not transient heap.
        var transferred = false
        try {
            val tile = when (val result = catalog.render(frame, grid, sceneTolerance, maxWorkingBytes,
                maxCandidateChunks, maxEdgeTests, cancelled)) {
                is BrushTileBuild.Ready -> result.tile
                is BrushTileBuild.Rejected -> return CachedBrushTileBuild.Rejected(result.reason)
                BrushTileBuild.Cancelled -> return CachedBrushTileBuild.Cancelled
            }
            val publication = pool.publish(reservation, key, tile)
            transferred = publication is ResourcePublication.Resident || publication is ResourcePublication.RetiredAfterClose
            check(transferred) { "Exclusive reservation lost" }
            releaseUnused()
            return when (val reader = pool.borrow(key)) {
                is ResourceBorrow.Borrowed -> CachedBrushTileBuild.Ready(BrushTileReaderPoc(frame, reader.lease), false)
                ResourceBorrow.Closed -> CachedBrushTileBuild.Rejected("closed")
                else -> error("Single-owner reader admission changed during render")
            }
        } finally {
            if (!transferred) pool.cancelUnallocated(reservation)
        }
    }

    fun returnReader(reader: BrushTileReaderPoc): LeaseReturn {
        val result = pool.returnLease(reader.token())
        releaseUnused()
        return result
    }

    /** CPU arrays only: release pool/ticket references, with no claim that GC has run. */
    private fun releaseUnused() {
        while (true) {
            val next = pool.nextRelease(ResourceLane.OWNER)
            if (next !is ResourceReleaseDispatch.Ready) return
            check(pool.completeRelease(next.ticket, ResourceReleaseOutcome.RELEASED) == ResourceCompletion.UPDATED)
        }
    }

    fun close() { pool.close(); releaseUnused() }
}
