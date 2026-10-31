package dev.notecore.canvas.prototype

import java.util.ArrayDeque

/** Creator thread owns admissions and results; one real serial worker owns execution.
 * Slots/declared bytes include queued, running, ready and blocked delivery until receiver ACK.
 * No unbounded completion posts, task cancellation, automatic execution retry or shutdownNow.
 */
class BoundedOwnerExecutor(
    private val maxEntries: Int = 64, private val byteBudget: Long = 8L * 1024 * 1024,
    threadName: String = "NoteCore-worker",
    private val requestOwnerWake: (() -> Unit)? = null,
) {
    private val identity = Any()
    private val ownerThread = Thread.currentThread()
    private val monitor = java.lang.Object()
    private abstract class Work(val ticket: OwnerWorkTicket, val bytes: Long) {
        var stage = OwnedWorkStage.QUEUED
        var deliveryFailure: String? = null
        abstract fun execute()
        abstract fun deliver(): ResultDisposition
        abstract fun workerTerminated(detail: String)
        abstract fun clear()
    }
    private class TypedWork<I : Any, R : Any>(ticket: OwnerWorkTicket, bytes: Long, input: I,
        compute: (I) -> R, receive: (I, WorkResult<R>) -> ResultDisposition) : Work(ticket, bytes) {
        private var input: I? = input
        private var compute: ((I) -> R)? = compute
        private var receive: ((I, WorkResult<R>) -> ResultDisposition)? = receive
        private var result: WorkResult<R>? = null
        override fun execute() {
            val action = checkNotNull(compute)
            result = try { WorkResult.Succeeded(action(checkNotNull(input))) }
            catch (failure: Throwable) { WorkResult.OutcomeUnknown(failure.javaClass.simpleName.take(80), (failure.message ?: "Worker execution failed").take(256)) }
            finally { compute = null }
        }
        override fun deliver() = checkNotNull(receive)(checkNotNull(input), checkNotNull(result))
        override fun workerTerminated(detail: String) {
            if (result == null) result = if (stage == OwnedWorkStage.QUEUED) WorkResult.NotExecuted(detail)
                else WorkResult.OutcomeUnknown("WorkerTerminated", detail)
            compute = null
        }
        override fun clear() { input = null; compute = null; receive = null; result = null }
    }
    private val entries = linkedMapOf<OwnerWorkTicket, Work>()
    private val pending = ArrayDeque<Work>(maxEntries)
    private val ready = ArrayDeque<Work>(maxEntries)
    private var accepting = true
    private var nextTicket = 0L
    private var chargedBytes = 0L
    private var workerStopped = false
    private var workerFailure: String? = null
    private var ownerWakeFailure: String? = null
    private var draining = false // owner-only: callbacks cannot recursively drain another result
    private val worker: Thread
    init {
        require(maxEntries > 0 && byteBudget >= 0 && threadName.isNotBlank() && threadName.length <= 64)
        worker = Thread({ workerLoop() }, threadName).apply { isDaemon = false; start() }
    }
    fun checkOwnerThread() { check(Thread.currentThread() === ownerThread) { "Operation requires executor owner thread" } }
    fun checkOutsideDrain() { checkOwnerThread(); check(!draining) { "ACK requires drain to return" } }
    fun checkWorkerThread() { check(Thread.currentThread() === worker) { "Operation requires executor worker thread" } }

    /** Rejected work remains caller-owned and has never been invoked. Charge covers declared peak input/result cost. */
    fun <I : Any, R : Any> submit(input: I, chargedBytes: Long, compute: (I) -> R, receive: (I, WorkResult<R>) -> ResultDisposition): WorkAdmission {
        checkOwnerThread()
        return synchronized(monitor) {
            val failure = when {
                workerFailure != null -> WorkAdmissionFailure.WORKER_TERMINATED
                !accepting -> WorkAdmissionFailure.STOPPED
                chargedBytes <= 0 -> WorkAdmissionFailure.INVALID_CHARGE
                entries.size >= maxEntries -> WorkAdmissionFailure.ENTRY_CAPACITY
                chargedBytes > byteBudget - this.chargedBytes -> WorkAdmissionFailure.BYTE_CAPACITY
                nextTicket == Long.MAX_VALUE -> WorkAdmissionFailure.ID_EXHAUSTED
                else -> null
            }
            if (failure != null) WorkAdmission.Rejected(failure) else {
                val ticket = OwnerWorkTicket(identity, ++nextTicket)
                val job = TypedWork(ticket, chargedBytes, input, compute, receive)
                entries[ticket] = job; pending.addLast(job); this.chargedBytes += chargedBytes
                monitor.notifyAll(); WorkAdmission.Accepted(ticket)
            }
        }
    }
    /** Control tasks have no data payload. Commands/resources must use the explicit-input overload. */
    fun <R : Any> submit(chargedBytes: Long, compute: () -> R, receive: (WorkResult<R>) -> ResultDisposition): WorkAdmission =
        submit(Unit, chargedBytes, { compute() }) { _, result -> receive(result) }
    private fun workerLoop() {
        try {
            while (true) {
                Thread.interrupted() // a previous task's self-interrupt must not contaminate the next job
                val job = synchronized(monitor) {
                    while (pending.isEmpty() && accepting) {
                        Thread.interrupted()
                        monitor.wait()
                    }
                    if (pending.isEmpty()) null else pending.removeFirst().also { it.stage = OwnedWorkStage.RUNNING }
                } ?: break
                job.execute()
                synchronized(monitor) { ready.addLast(job); job.stage = OwnedWorkStage.READY; monitor.notifyAll() }
                wakeOwner()
            }
        } catch (failure: Throwable) {
            // Complete reserved result slots in FIFO order. Never-started work is distinct from unknown execution.
            synchronized(monitor) {
                workerFailure = failure.javaClass.simpleName.take(80); accepting = false
                for (job in entries.values) if (job.stage == OwnedWorkStage.QUEUED || job.stage == OwnedWorkStage.RUNNING) {
                    job.workerTerminated(checkNotNull(workerFailure)); job.stage = OwnedWorkStage.READY; ready.addLast(job)
                }
                pending.clear() // every pending entry now has a retained NotExecuted result; nothing is discarded
                monitor.notifyAll()
            }
        } finally {
            synchronized(monitor) { workerStopped = true; monitor.notifyAll() }
            wakeOwner()
        }
    }
    /** Wake is a no-payload scheduling hint; failures never discard results or kill accepted work. */
    private fun wakeOwner() {
        try { requestOwnerWake?.invoke() } catch (failure: Throwable) {
            synchronized(monitor) { ownerWakeFailure = failure.javaClass.simpleName.take(80) }
        }
    }
    /** Owner event-loop pumps a finite number of results. Receiver exceptions block FIFO and keep the exact result. */
    fun drainResults(limit: Int = 16): ResultDrain {
        checkOwnerThread(); require(limit > 0); check(!draining) { "Result delivery cannot recursively drain" }
        draining = true
        var acknowledged = 0
        var blocked: OwnerWorkTicket? = null
        try {
            repeat(limit) {
                val job = synchronized(monitor) {
                    ready.peekFirst()?.takeIf { it.stage == OwnedWorkStage.READY }?.also { it.stage = OwnedWorkStage.DELIVERING }
                }
                if (job == null) {
                    blocked = synchronized(monitor) { ready.peekFirst()?.takeIf { it.stage == OwnedWorkStage.DELIVERY_BLOCKED }?.ticket }
                    return ResultDrain(acknowledged, blocked)
                }
                var failure: String? = null
                val disposition = try { job.deliver() } catch (error: Throwable) {
                    failure = "${error.javaClass.simpleName}: ${(error.message ?: "Receiver failed").take(256)}".take(336)
                    ResultDisposition.RETRY_REQUIRED
                }
                synchronized(monitor) {
                    if (disposition == ResultDisposition.ACKNOWLEDGED) {
                        check(ready.removeFirst() === job)
                        entries.remove(job.ticket); chargedBytes -= job.bytes; job.clear(); acknowledged++
                    } else {
                        job.stage = OwnedWorkStage.DELIVERY_BLOCKED
                        job.deliveryFailure = failure ?: "Receiver requested explicit retry"
                        blocked = job.ticket
                    }
                }
                if (blocked != null) return ResultDrain(acknowledged, blocked)
            }
            return ResultDrain(acknowledged, null)
        } finally { draining = false }
    }
    fun retryDelivery(ticket: OwnerWorkTicket): Boolean {
        checkOwnerThread()
        return synchronized(monitor) {
            val job = entries[ticket]?.takeIf { ticket.owner === identity && it.stage == OwnedWorkStage.DELIVERY_BLOCKED } ?: return@synchronized false
            job.stage = OwnedWorkStage.READY; job.deliveryFailure = null; true
        }
    }
    /** Stop only admissions. Queued jobs execute and completed results remain owned until receiver ACK. */
    fun stopAccepting() { checkOwnerThread(); synchronized(monitor) { accepting = false; monitor.notifyAll() } }
    fun status(): OwnerExecutorStatus {
        checkOwnerThread()
        return synchronized(monitor) {
            OwnerExecutorStatus(accepting, workerStopped, workerFailure, chargedBytes,
                entries.values.map { OwnedWorkSummary(it.ticket, it.stage, it.bytes, it.deliveryFailure) }, ownerThread.id, worker.id, ownerWakeFailure)
        }
    }
    /** Diagnostic/test wait only; production UI must pump without blocking. A timeout does not cancel anything. */
    fun awaitWorkerStop(timeoutMillis: Long): Boolean {
        checkOwnerThread(); require(timeoutMillis in 1..60_000)
        worker.join(timeoutMillis)
        return !worker.isAlive
    }
}
