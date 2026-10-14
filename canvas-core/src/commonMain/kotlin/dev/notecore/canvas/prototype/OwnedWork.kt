package dev.notecore.canvas.prototype

/** Opaque identity does not retain closures, payloads, results or an executor. */
class OwnerWorkTicket internal constructor(internal val owner: Any, val number: Long)
enum class WorkAdmissionFailure { STOPPED, ENTRY_CAPACITY, BYTE_CAPACITY, INVALID_CHARGE, ID_EXHAUSTED, WORKER_TERMINATED }
sealed interface WorkAdmission {
    data class Accepted(val ticket: OwnerWorkTicket) : WorkAdmission
    data class Rejected(val reason: WorkAdmissionFailure) : WorkAdmission
}
sealed interface WorkResult<out R : Any> {
    data class Succeeded<R : Any>(val value: R) : WorkResult<R>
    /** Work may have applied a side effect before throwing. Never implies rollback or safe re-execution. */
    data class OutcomeUnknown(val exceptionType: String, val detail: String) : WorkResult<Nothing>
    /** Worker terminated before this queued job started; compute was never invoked. */
    data class NotExecuted(val detail: String) : WorkResult<Nothing>
}
enum class ResultDisposition { ACKNOWLEDGED, RETRY_REQUIRED }
enum class OwnedWorkStage { QUEUED, RUNNING, READY, DELIVERING, DELIVERY_BLOCKED }
data class OwnedWorkSummary(val ticket: OwnerWorkTicket, val stage: OwnedWorkStage, val chargedBytes: Long, val deliveryFailure: String?)
data class OwnerExecutorStatus(
    val accepting: Boolean, val workerStopped: Boolean, val workerFailure: String?,
    val chargedBytes: Long, val entries: List<OwnedWorkSummary>, val ownerThreadId: Long, val workerThreadId: Long,
    val ownerWakeFailure: String? = null,
) {
    val isDrained: Boolean get() = !accepting && workerStopped && entries.isEmpty()
}
data class ResultDrain(val acknowledged: Int, val blocked: OwnerWorkTicket?)
