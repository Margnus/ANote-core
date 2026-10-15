package dev.notecore.canvas.prototype

import java.util.concurrent.atomic.AtomicBoolean

/** Uses the existing real serial executor. Cancellation is cooperative, never Thread.interrupt.
 * Owner ACK settlement happens after drainResults has cleared executor input/result references.
 */
internal class BrushRenderExecutorPoc(
    frame: FrameToken, layer: LayerId, residentBudget: Long, totalBudget: Long, maxJobs: Int = 4,
    private val beforeCompute: (BrushRenderJobPoc) -> Unit = {},
    private val permitDelivery: (BrushRenderJobPoc) -> Boolean = { true },
) {
    private val executor = run {
        require(maxJobs in 1..128 && residentBudget > 0 && totalBudget >= residentBudget)
        BoundedOwnerExecutor(maxJobs, totalBudget, "NoteCore-mask-worker")
    }
    private val owner = BrushRenderOwnerPoc(frame, layer, residentBudget, totalBudget, maxJobs,
        checkOwner = executor::checkOwnerThread)
    private class Dispatch(val job: BrushRenderJobPoc, val cancelled: AtomicBoolean = AtomicBoolean(false)) {
        var ticket: OwnerWorkTicket? = null
    }
    private val dispatched = mutableMapOf<BrushRenderIdPoc, Dispatch>()
    private val acknowledged = mutableListOf<BrushRenderJobPoc>()
    val chargedBytes: Long get() = owner.chargedBytes
    val retiredBytes: Long get() = owner.retiredBytes
    val pendingJobs: Int get() = owner.pendingJobs
    val isDrained: Boolean get() = owner.isDrained && executor.status().isDrained
    fun executorStatus() = executor.status()

    fun submit(catalog: ChunkedBrushPoc, frame: FrameToken, layer: LayerId, grid: BrushTileGridPoc,
        rgb: Int, workingBytes: Long = 1024 * 1024, nativeCopyAllowanceBytes: Long = 0,
    ): BrushRenderAdmissionPoc {
        executor.checkOwnerThread()
        val admission = owner.admit(catalog, frame, layer, grid, rgb, workingBytes, nativeCopyAllowanceBytes)
        if (admission !is BrushRenderAdmissionPoc.Accepted) return admission
        val job = admission.job; val dispatch = Dispatch(job)
        val submitted = executor.submit(job, job.peakBytes, { input ->
            executor.checkWorkerThread(); beforeCompute(input); input.execute(dispatch.cancelled::get)
        }) { input, result ->
            check(owner.receive(input, result))
            if (!permitDelivery(input)) ResultDisposition.RETRY_REQUIRED else {
                acknowledged.add(input); ResultDisposition.ACKNOWLEDGED
            }
        }
        if (submitted is WorkAdmission.Rejected) {
            check(owner.rejectDispatch(job))
            return BrushRenderAdmissionPoc.Rejected("executor-${submitted.reason.name.lowercase()}")
        }
        dispatch.ticket = (submitted as WorkAdmission.Accepted).ticket
        dispatched[job.id] = dispatch
        return admission
    }
    fun cancel(job: BrushRenderJobPoc): Boolean {
        executor.checkOwnerThread()
        val entry = dispatched[job.id] ?: return false
        if (!owner.cancel(job)) return false
        entry.cancelled.set(true); return true
    }
    fun expect(frame: FrameToken, layer: LayerId): Boolean {
        executor.checkOwnerThread()
        if (!owner.expect(frame, layer)) return false
        dispatched.values.filter { it.job.frame != frame }.forEach { cancel(it.job) }
        return true
    }
    fun drain(limit: Int = 16): List<Pair<BrushRenderIdPoc, BrushRenderAckPoc>> {
        executor.drainResults(limit)
        val settled = acknowledged.map { job ->
            val result = owner.acknowledge(job)
            if (result != BrushRenderAckPoc.QUARANTINED) dispatched.remove(job.id)
            job.id to result
        }
        acknowledged.clear(); return settled
    }
    fun retryDelivery(job: BrushRenderJobPoc): Boolean {
        executor.checkOwnerThread()
        val ticket = dispatched[job.id]?.ticket ?: return false
        return executor.retryDelivery(ticket)
    }
    fun reconcileNoOutput(job: BrushRenderJobPoc): Boolean {
        executor.checkOwnerThread()
        if (!executor.status().workerStopped || !owner.reconcileNoOutput(job)) return false
        dispatched.remove(job.id); return true
    }
    fun borrow(job: BrushRenderJobPoc) = owner.borrow(job)
    fun borrowNative(job: BrushRenderJobPoc) = owner.borrowNative(job)
    fun returnReader(reader: BrushRenderReaderPoc) = owner.returnReader(reader)
    fun completeNativeRelease(reader: BrushRenderReaderPoc, outcome: ResourceReleaseOutcome) = owner.completeNativeRelease(reader, outcome)
    fun reconcileNativeReleased(reader: BrushRenderReaderPoc) = owner.reconcileNativeReleased(reader)
    fun canPublish(reader: BrushRenderReaderPoc) = owner.canPublish(reader)
    fun close() {
        executor.checkOwnerThread(); owner.close()
        dispatched.values.forEach { it.cancelled.set(true) }
        executor.stopAccepting()
    }
    fun awaitWorkerStop(timeoutMillis: Long) = executor.awaitWorkerStop(timeoutMillis)
}
