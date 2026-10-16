package dev.notecore.canvas.prototype

/** Unfrozen ObjC export shim; internal renderer/types remain internal. No scheduling here. */
class AppleBrushJobExperiment internal constructor(internal val job: BrushRenderJobPoc) {
    val frame: FrameToken get() = job.frame
    val peakBytes: Long get() = job.peakBytes
    val nativeAllowance: Long get() = job.nativeCopyAllowanceBytes
    val width: Int get() = job.grid.width
    val height: Int get() = job.grid.height
    fun execute(cancelled: () -> Boolean): AppleBrushResultExperiment =
        // Kotlin exceptions must not cross an unannotated ObjC boundary and terminate the host.
        // Mint once here; Swift retries delivery of this exact wrapper, never this computation.
        AppleBrushResultExperiment(try { WorkResult.Succeeded(job.execute(cancelled)) }
            catch (failure: Throwable) { WorkResult.OutcomeUnknown("kotlin-compute", failure.toString().take(256)) })
    fun unknown(message: String): AppleBrushResultExperiment =
        AppleBrushResultExperiment(WorkResult.OutcomeUnknown("swift-compute", message.take(256)))
}
class AppleBrushResultExperiment internal constructor(internal val result: WorkResult<BrushRenderComputationPoc>) {
    val outcomeUnknown: Boolean get() = result is WorkResult.OutcomeUnknown
}
class AppleBrushReaderExperiment internal constructor(internal val reader: BrushRenderReaderPoc) {
    fun byteAt(offset: Int): Int = reader.byteAt(offset)
}
class AppleBrushOwnerExperiment(
    frame: FrameToken, layer: LayerId, residentBudget: Long, totalBudget: Long, maxJobs: Int,
    checkOwner: () -> Unit,
) {
    private val owner = BrushRenderOwnerPoc(frame, layer, residentBudget, totalBudget, maxJobs, checkOwner = checkOwner)
    private val check = checkOwner
    val chargedBytes: Long get() = owner.chargedBytes
    val retiredBytes: Long get() = owner.retiredBytes
    val isDrained: Boolean get() = owner.isDrained
    // Bounded catalog preparation on owner; only job.execute crosses to the existing worker.
    fun admit(stroke: StrokeObject, frame: FrameToken, originX: Double, originY: Double,
        width: Int, height: Int, pixelSize: Double, workingBytes: Long, nativeAllowance: Long,
    ): AppleBrushJobExperiment? {
        check()
        if (workingBytes <= 0 || nativeAllowance < 0 || width !in 1..128 || height !in 1..128 ||
            !originX.isFinite() || !originY.isFinite() || kotlin.math.abs(originX) > 1e9 || kotlin.math.abs(originY) > 1e9 ||
            !pixelSize.isFinite() || pixelSize !in 0.0001..1e6) return null
        val catalog = (ChunkedBrushPoc.build(frame, stroke, segmentsPerChunk = 1) as? ChunkedBrushBuild.Ready)?.catalog ?: return null
        val admission = owner.admit(catalog, frame, stroke.layerId,
            BrushTileGridPoc(PointD(originX, originY), width, height, pixelSize, 4),
            stroke.brush.argb and 0xffffff, workingBytes, nativeAllowance)
        return (admission as? BrushRenderAdmissionPoc.Accepted)?.let { AppleBrushJobExperiment(it.job) }
    }
    fun reject(job: AppleBrushJobExperiment): Boolean = owner.rejectDispatch(job.job)
    fun admitPage(scene: SceneSnapshot, frame: FrameToken, layer: LayerId, originX: Double, originY: Double,
        width: Int, height: Int, pixelSize: Double, workingBytes: Long, nativeAllowance: Long,
    ): AppleBrushJobExperiment? {
        check()
        if (workingBytes <= 0 || nativeAllowance < 0 || width !in 1..128 || height !in 1..128 ||
            !originX.isFinite() || !originY.isFinite() || kotlin.math.abs(originX) > 1e9 || kotlin.math.abs(originY) > 1e9 ||
            !pixelSize.isFinite() || pixelSize !in 0.0001..1e6) return null
        return (owner.admitPage(scene, frame, layer,
            BrushTileGridPoc(PointD(originX, originY), width, height, pixelSize, 4),
            workingBytes, nativeAllowance) as? BrushRenderAdmissionPoc.Accepted)?.let { AppleBrushJobExperiment(it.job) }
    }
    fun receive(job: AppleBrushJobExperiment, result: AppleBrushResultExperiment): Boolean = owner.receive(job.job, result.result)
    fun acknowledge(job: AppleBrushJobExperiment): String = owner.acknowledge(job.job).name
    fun cancel(job: AppleBrushJobExperiment): Boolean = owner.cancel(job.job)
    fun expect(frame: FrameToken, layer: LayerId): Boolean = owner.expect(frame, layer)
    fun close() = owner.close()
    fun reconcileNoOutput(job: AppleBrushJobExperiment): Boolean = owner.reconcileNoOutput(job.job)
    fun borrowNative(job: AppleBrushJobExperiment): AppleBrushReaderExperiment? = owner.borrowNative(job.job)?.let(::AppleBrushReaderExperiment)
    fun canPublish(reader: AppleBrushReaderExperiment): Boolean = owner.canPublish(reader.reader)
    fun release(reader: AppleBrushReaderExperiment, unknown: Boolean): Boolean = owner.completeNativeRelease(reader.reader,
        if (unknown) ResourceReleaseOutcome.UNKNOWN else ResourceReleaseOutcome.RELEASED)
    fun reconcileReleased(reader: AppleBrushReaderExperiment): Boolean = owner.reconcileNativeReleased(reader.reader)
}
