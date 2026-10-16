package dev.notecore.canvas.prototype

internal class BrushRenderIdPoc
internal sealed interface BrushRenderAdmissionPoc {
    data class Accepted(val job: BrushRenderJobPoc) : BrushRenderAdmissionPoc
    data class Rejected(val reason: String) : BrushRenderAdmissionPoc
}
internal class BrushRenderComputationPoc private constructor(
    val id: BrushRenderIdPoc, val pixels: BrushTilePixelsPoc?, val failure: String?,
) {
    companion object {
        internal fun completed(id: BrushRenderIdPoc, pixels: BrushTilePixelsPoc?, failure: String?) =
            BrushRenderComputationPoc(id, pixels, failure)
    }
}

/** One transfer to one worker. No resource-pool or mutable owner state crosses that boundary.
 * Job input is cleared on exit; no real samples or document commands are changed.
 */
internal class BrushRenderJobPoc internal constructor(
    val id: BrushRenderIdPoc, catalog: ChunkedBrushPoc?, val frame: FrameToken,
    val layer: LayerId, val grid: BrushPixelGridPoc, val rgb: Int, val workingBytes: Long,
    val nativeCopyAllowanceBytes: Long, val peakBytes: Long, val maxEdgeTests: Long,
    scene: SceneSnapshot? = null,
) {
    private var input: ChunkedBrushPoc? = catalog
    private var pageInput: SceneSnapshot? = scene
    internal fun clearUnsubmittedInput() { input = null; pageInput = null }
    fun execute(cancelled: () -> Boolean = { false }): BrushRenderComputationPoc {
        pageInput?.let { scene ->
            try { return executePage(scene, cancelled) } finally { clearUnsubmittedInput() }
        }
        val catalog = checkNotNull(input) { "Job has already executed or was not dispatched" }
        try {
            val mask = when (val built = catalog.render(frame, grid as BrushTileGridPoc, maxWorkingBytes = workingBytes,
                maxEdgeTests = maxEdgeTests, cancelled = cancelled)) {
                is BrushTileBuild.Ready -> built.tile
                is BrushTileBuild.Rejected -> return BrushRenderComputationPoc.completed(id, null, built.reason)
                BrushTileBuild.Cancelled -> return BrushRenderComputationPoc.completed(id, null, "cancelled")
            }
            return when (val converted = BrushTilePixelsPoc.build(mask, rgb, grid.width * grid.height * 4L, cancelled)) {
                is BrushTilePixelBuild.Ready -> BrushRenderComputationPoc.completed(id, converted.pixels, null)
                is BrushTilePixelBuild.Rejected -> BrushRenderComputationPoc.completed(id, null, converted.reason)
                BrushTilePixelBuild.Cancelled -> BrushRenderComputationPoc.completed(id, null, "cancelled")
            }
        } finally { input = null }
    }

    private fun executePage(scene: SceneSnapshot, cancelled: () -> Boolean): BrushRenderComputationPoc {
        fun failure(reason: String) = BrushRenderComputationPoc.completed(id, null, reason)
        if (cancelled()) return failure("cancelled")
        val output = BrushTilePixelsPoc.Companion.Composition(frame, layer, grid)
        var remaining = maxEdgeTests
        var failed: String? = null
        // Iteration preserves the immutable scene's canonical draw order, including empty pages.
        scene.forEachObject { stroke ->
            if (failed != null || stroke.layerId != layer) return@forEachObject
            if (cancelled()) { failed = "cancelled"; return@forEachObject }
            val chunks = maxOf(1, (stroke.samples.size - 1 + 127) / 128)
            // Bound catalog primitive payload and construction entries before building them.
            // Headers/index objects/GC remain outside logical accounting; this is not a heap cap.
            val catalogBytes = chunks * 72L
            val renderBytes = workingBytes - catalogBytes
            if (renderBytes <= 0) { failed = "page-working-budget"; return@forEachObject }
            val catalog = when (val built = ChunkedBrushPoc.build(frame, stroke, cancelled = cancelled)) {
                is ChunkedBrushBuild.Ready -> built.catalog
                is ChunkedBrushBuild.Rejected -> { failed = built.reason; return@forEachObject }
                ChunkedBrushBuild.Cancelled -> { failed = "cancelled"; return@forEachObject }
            }
            for (y in 0 until grid.height step 128) for (x in 0 until grid.width step 128) {
                if (failed != null) return@forEachObject
                val tile = BrushTileGridPoc(PointD(grid.origin.x + x * grid.pixelSize, grid.origin.y + y * grid.pixelSize),
                    minOf(128, grid.width - x), minOf(128, grid.height - y), grid.pixelSize, grid.samplesPerAxis)
                val compositeSteps = tile.width * tile.height * 4L
                if (remaining <= compositeSteps) { failed = "page-work-budget"; return@forEachObject }
                when (val built = catalog.render(frame, tile, maxWorkingBytes = renderBytes,
                    maxEdgeTests = remaining - compositeSteps, cancelled = cancelled)) {
                    is BrushTileBuild.Ready -> {
                        remaining -= built.edgeTests + compositeSteps
                        if (!output.over(built.tile, stroke.brush.argb and 0xffffff, cancelled, x, y)) failed = "cancelled"
                    }
                    is BrushTileBuild.Rejected -> failed = built.reason
                    BrushTileBuild.Cancelled -> failed = "cancelled"
                }
            }
        }
        if (failed != null) return failure(failed!!)
        if (cancelled()) return failure("cancelled")
        return BrushRenderComputationPoc.completed(id, output.finish(), null)
    }
}

internal enum class BrushRenderAckPoc { PUBLISHED, DISCARDED, FAILED, QUARANTINED, NOT_READY }
internal class BrushRenderReaderPoc internal constructor(private val lease: ResourceLease<BrushTilePixelsPoc>, val nativeCopyAllowanceBytes: Long) {
    val frame: FrameToken get() = lease.value.frame
    val layer: LayerId get() = lease.value.ownerLayer
    val grid: BrushPixelGridPoc get() = lease.value.grid
    fun byteAt(offset: Int) = lease.value.byteAt(offset)
    internal fun token() = lease
}

/** Real renderer/ResourceLeasePool integration. The executor is the queue authority; this owner
 * holds only the bounded admission/handoff ledger, not a second scheduling queue.
 * All owner methods require checkOwner; only job.execute runs on a worker.
 */
internal class BrushRenderOwnerPoc(
    initial: FrameToken, private var layer: LayerId, residentBudget: Long, totalBudget: Long,
    private val maxJobs: Int = 4, maxReaders: Int = 16, private val checkOwner: () -> Unit = {},
) {
    private class Pending(val job: BrushRenderJobPoc, val reservation: ResourceReservation) {
        var result: WorkResult<BrushRenderComputationPoc>? = null
        var cancelled = false
        var quarantined = false
    }
    private val gate = FramePublicationGate(initial)
    private val pool = ResourceLeasePool<BrushRenderIdPoc, BrushTilePixelsPoc>(residentBudget, totalBudget,
        maxResources = 256, maxLeases = maxReaders, releaseLane = ResourceLane.OWNER)
    private val pending = mutableMapOf<BrushRenderIdPoc, Pending>()
    // A per-output native allowance authorizes one native allocation group, not one per reader.
    private val nativeReaders = mutableMapOf<BrushRenderIdPoc, BrushRenderReaderPoc>()
    private val unknownNativeReleases = mutableSetOf<BrushRenderReaderPoc>()
    private var closed = false
    private var expected = initial
    init { require(maxJobs in 1..128) }
    val chargedBytes: Long get() { checkOwner(); return pool.chargedBytes }
    val pendingJobs: Int get() { checkOwner(); return pending.size }
    val retiredBytes: Long get() { checkOwner(); return pool.retiredBytes }
    val isDrained: Boolean get() { checkOwner(); return closed && pending.isEmpty() && pool.isDrained }

    /** A complete requested region from the canonical immutable scene, not a second page model.
     * Full scene sample retention is charged because the job holds the scene, including other layers.
     * Catalogs are built one at a time on the existing worker within the declared working budget.
     */
    fun admitPage(scene: SceneSnapshot, frame: FrameToken, expectedLayer: LayerId, grid: BrushPixelGridPoc,
        workingBytes: Long = 1024 * 1024, nativeCopyAllowanceBytes: Long = 0,
        maxEdgeTests: Long = 20_000_000,
    ): BrushRenderAdmissionPoc {
        checkOwner(); require(workingBytes > 0 && nativeCopyAllowanceBytes >= 0 && maxEdgeTests > 0)
        if (closed) return BrushRenderAdmissionPoc.Rejected("closed")
        if (!gate.canPublish(frame) || expectedLayer != layer || scene.id != frame.sceneId || scene.revision != frame.sceneRevision)
            return BrushRenderAdmissionPoc.Rejected("content-owner-mismatch")
        if (pending.size >= maxJobs) return BrushRenderAdmissionPoc.Rejected("job-capacity")
        if (scene.objectCount > 4096) return BrushRenderAdmissionPoc.Rejected("page-object-budget")
        var points = 0L; var selected = 0; var invalid = false
        scene.forEachObject { stroke ->
            points += stroke.samples.size
            if (stroke.layerId == layer) {
                selected++
                if (stroke.brush.kind != BrushKind.MONOLINE || stroke.brush.algorithmVersion != 1) invalid = true
            }
        }
        if (points > 200000 || selected > 256) return BrushRenderAdmissionPoc.Rejected("page-input-budget")
        if (invalid) return BrushRenderAdmissionPoc.Rejected("unsupported-page-brush")
        var peak = points * 28 + scene.objectCount * 8L
        for (bytes in listOf(workingBytes, grid.width * grid.height * 4L, nativeCopyAllowanceBytes)) {
            if (bytes > Long.MAX_VALUE - peak) return BrushRenderAdmissionPoc.Rejected("charge-overflow")
            peak += bytes
        }
        releaseUnused()
        val reservation = when (val reserved = pool.reservePeak(peak, grid.width * grid.height * 4L + nativeCopyAllowanceBytes)) {
            is ReservationResult.Reserved -> reserved.reservation
            is ReservationResult.Rejected -> return BrushRenderAdmissionPoc.Rejected("pool-${reserved.reason.name.lowercase()}")
        }
        val job = BrushRenderJobPoc(BrushRenderIdPoc(), null, frame, layer, grid, 0, workingBytes,
            nativeCopyAllowanceBytes, peak, maxEdgeTests, scene)
        pending[job.id] = Pending(job, reservation)
        return BrushRenderAdmissionPoc.Accepted(job)
    }

    fun admit(catalog: ChunkedBrushPoc, frame: FrameToken, expectedLayer: LayerId, grid: BrushTileGridPoc,
        rgb: Int, workingBytes: Long = 1024 * 1024, nativeCopyAllowanceBytes: Long = 0,
        maxEdgeTests: Long = 20_000_000,
    ): BrushRenderAdmissionPoc {
        checkOwner(); require(workingBytes > 0 && nativeCopyAllowanceBytes >= 0 && maxEdgeTests > 0)
        if (closed) return BrushRenderAdmissionPoc.Rejected("closed")
        if (!gate.canPublish(frame) || expectedLayer != layer || catalog.ownerLayer != layer ||
            catalog.contentFrame.sceneId != frame.sceneId || catalog.contentFrame.sceneRevision != frame.sceneRevision ||
            catalog.contentFrame.sessionGeneration != frame.sessionGeneration) return BrushRenderAdmissionPoc.Rejected("content-owner-mismatch")
        if (rgb !in 0..0xffffff) return BrushRenderAdmissionPoc.Rejected("rgb-range")
        if (pending.size >= maxJobs) return BrushRenderAdmissionPoc.Rejected("job-capacity")
        var peak = catalog.inputPayloadBytes
        for (bytes in listOf(workingBytes, grid.width * grid.height * 4L, nativeCopyAllowanceBytes)) {
            if (bytes > Long.MAX_VALUE - peak) return BrushRenderAdmissionPoc.Rejected("charge-overflow")
            peak += bytes
        }
        releaseUnused()
        val reservation = when (val reserved = pool.reservePeak(peak, grid.width * grid.height * 4L + nativeCopyAllowanceBytes)) {
            is ReservationResult.Reserved -> reserved.reservation
            is ReservationResult.Rejected -> return BrushRenderAdmissionPoc.Rejected("pool-${reserved.reason.name.lowercase()}")
        }
        val job = BrushRenderJobPoc(BrushRenderIdPoc(), catalog, frame, layer, grid, rgb, workingBytes,
            nativeCopyAllowanceBytes, peak, maxEdgeTests)
        pending[job.id] = Pending(job, reservation)
        return BrushRenderAdmissionPoc.Accepted(job)
    }

    /** Only after executor admission rejected this exact input; it has never reached a worker. */
    fun rejectDispatch(job: BrushRenderJobPoc): Boolean {
        checkOwner()
        val entry = pending[job.id]?.takeIf { it.job === job && it.result == null } ?: return false
        pending.remove(job.id); job.clearUnsubmittedInput()
        return pool.cancelUnallocated(entry.reservation)
    }
    fun cancel(job: BrushRenderJobPoc): Boolean {
        checkOwner(); val entry = pending[job.id]?.takeIf { it.job === job } ?: return false
        entry.cancelled = true; return true // No charge reduction and no concurrent writes to job input.
    }
    fun receive(job: BrushRenderJobPoc, result: WorkResult<BrushRenderComputationPoc>): Boolean {
        checkOwner(); val entry = pending[job.id]?.takeIf { it.job === job && !it.quarantined } ?: return false
        if (entry.result != null) return entry.result === result // Idempotent retry of exact delivery.
        if (result is WorkResult.Succeeded && result.value.id !== job.id) return false
        entry.result = result; return true
    }

    /** Called only AFTER the executor has cleared input/result references on ACK, not inside its
     * receiver callback. Peak charge survives READY, receiver failure, cancellation and close.
     */
    fun acknowledge(job: BrushRenderJobPoc): BrushRenderAckPoc {
        checkOwner(); val entry = pending[job.id]?.takeIf { it.job === job } ?: return BrushRenderAckPoc.NOT_READY
        val result = entry.result ?: return BrushRenderAckPoc.NOT_READY
        if (result is WorkResult.OutcomeUnknown) { entry.quarantined = true; return BrushRenderAckPoc.QUARANTINED }
        pending.remove(job.id)
        val pixels = (result as? WorkResult.Succeeded)?.value?.pixels
        if (pixels == null) {
            job.clearUnsubmittedInput(); check(pool.cancelUnallocated(entry.reservation))
            return BrushRenderAckPoc.FAILED
        }
        val reservation = checkNotNull(pool.settleReservation(entry.reservation, pixels.logicalBytes + job.nativeCopyAllowanceBytes))
        val publish = !closed && !entry.cancelled && gate.canPublish(job.frame) && layer == job.layer
        val publication = if (publish) pool.publish(reservation, job.id, pixels) else pool.publishRetired(reservation, pixels)
        check(publication is ResourcePublication.Resident || publication is ResourcePublication.RetiredAfterClose || publication is ResourcePublication.Retired)
        releaseUnused()
        return if (publish) BrushRenderAckPoc.PUBLISHED else BrushRenderAckPoc.DISCARDED
    }
    /** Explicit reconciliation after the executor proves worker termination/no escaped CPU output.
     * No automatic retry of an unknown compute outcome. Native allocation is not done by this job.
     */
    fun reconcileNoOutput(job: BrushRenderJobPoc): Boolean {
        checkOwner(); val entry = pending[job.id]?.takeIf { it.job === job && it.quarantined } ?: return false
        pending.remove(job.id); job.clearUnsubmittedInput(); return pool.cancelUnallocated(entry.reservation)
    }
    fun borrow(job: BrushRenderJobPoc): BrushRenderReaderPoc? {
        checkOwner()
        val result = pool.borrow(job.id)
        return if (result is ResourceBorrow.Borrowed) BrushRenderReaderPoc(result.lease, 0) else null
    }
    fun borrowNative(job: BrushRenderJobPoc): BrushRenderReaderPoc? {
        checkOwner()
        if (job.nativeCopyAllowanceBytes <= 0 || job.id in nativeReaders) return null
        val result = pool.borrow(job.id)
        if (result !is ResourceBorrow.Borrowed) return null
        return BrushRenderReaderPoc(result.lease, job.nativeCopyAllowanceBytes).also { nativeReaders[job.id] = it }
    }
    fun returnReader(reader: BrushRenderReaderPoc): LeaseReturn {
        checkOwner(); check(nativeReaders.values.none { it === reader }) { "Native destruction confirmation required" }
        val result = pool.returnLease(reader.token()); releaseUnused(); return result
    }
    /** Called AFTER native release attempts. Failed/unknown destruction keeps the unique native
     * permit and CPU lease charged. UNKNOWN cannot authorize another destructive retry.
     */
    fun completeNativeRelease(reader: BrushRenderReaderPoc, outcome: ResourceReleaseOutcome): Boolean {
        checkOwner()
        if (nativeReaders.values.none { it === reader } || reader in unknownNativeReleases) return false
        if (outcome == ResourceReleaseOutcome.UNKNOWN) unknownNativeReleases.add(reader)
        if (outcome != ResourceReleaseOutcome.RELEASED) return false
        return forgetNative(reader)
    }
    fun reconcileNativeReleased(reader: BrushRenderReaderPoc): Boolean {
        checkOwner(); if (!unknownNativeReleases.remove(reader)) return false
        return forgetNative(reader)
    }
    private fun forgetNative(reader: BrushRenderReaderPoc): Boolean {
        val id = nativeReaders.entries.firstOrNull { it.value === reader }?.key ?: return false
        nativeReaders.remove(id)
        check(pool.returnLease(reader.token()) == LeaseReturn.RETURNED); releaseUnused(); return true
    }
    fun canPublish(reader: BrushRenderReaderPoc): Boolean {
        checkOwner(); return !closed && gate.canPublish(reader.frame) && reader.layer == layer
    }
    fun expect(frame: FrameToken, expectedLayer: LayerId): Boolean {
        checkOwner(); if (closed || !gate.expect(frame)) return false
        if (frame == expected && expectedLayer == layer) return true
        expected = frame
        layer = expectedLayer; pool.evictAll(); releaseUnused(); return true
    }
    private fun releaseUnused() {
        while (true) {
            val next = pool.nextRelease(ResourceLane.OWNER)
            if (next !is ResourceReleaseDispatch.Ready) return
            check(pool.completeRelease(next.ticket, ResourceReleaseOutcome.RELEASED) == ResourceCompletion.UPDATED)
        }
    }
    fun close() { checkOwner(); closed = true; gate.close(); pool.close(); releaseUnused() }
}
