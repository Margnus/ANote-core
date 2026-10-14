package dev.notecore.canvas.prototype

/** A completed stroke proposal has stable identity; the authority chooses its base on first dispatch. */
data class StrokeSubmission(val id: CommandId, val objectId: ObjectId, val stroke: CompletedInput) {
    /** Logical admission charge, NOT measured JVM/native heap or the document's total memory. */
    val chargedBytes: Long get() = stroke.samples.size.toLong() * 28 + 256 +
        2L * (id.value.length + objectId.value.length + stroke.target.sceneId.value.length + stroke.target.layerId.value.length)
}

sealed interface SubmissionState {
    data object Queued : SubmissionState
    data class InFlight(val attemptNumber: Long) : SubmissionState
    data class RetryRequired(val detail: String) : SubmissionState
    data class Blocked(val reason: Rejection) : SubmissionState
    /** Authority acceptance. The current receipt is MEMORY_ONLY, not a saved-to-disk indicator. */
    data class Confirmed(val receipt: CommitReceipt) : SubmissionState
}

enum class AdmissionRejection { STOPPED, WRONG_SCENE, REUSED_ID, ENTRY_CAPACITY, BYTE_CAPACITY }
sealed interface AdmissionResult {
    data object Admitted : AdmissionResult
    data class Existing(val state: SubmissionState) : AdmissionResult
    data class Rejected(val reason: AdmissionRejection) : AdmissionResult
}

/** Opaque per-dispatch capability. A completion from an older attempt or another queue is rejected. */
class SubmissionAttempt internal constructor(val id: CommandId, val number: Long, val patch: ScenePatch)
sealed interface DispatchResult {
    data class Ready(val attempt: SubmissionAttempt) : DispatchResult
    data object NoWork : DispatchResult
    data class Busy(val id: CommandId) : DispatchResult
    data class RetryRequired(val id: CommandId) : DispatchResult
    data class Blocked(val id: CommandId, val reason: Rejection) : DispatchResult
    data object AttemptNumbersExhausted : DispatchResult
}
enum class CompletionUpdate { UPDATED, STALE_ATTEMPT, INVALID_RECEIPT }

/** Bounded, single-owner FIFO model. No coroutines, thread safety, disk, automatic retry or rebase.
 * Confirmed payloads remain charged until collected. Recovery removes blocked work only by explicit handoff.
 */
class StrokeSubmissionQueue(val sceneId: SceneId, val maxEntries: Int = 64, val byteBudget: Long = 8L * 1024 * 1024) {
    private class Entry(val submission: StrokeSubmission) {
        var state: SubmissionState = SubmissionState.Queued
        var patch: ScenePatch? = null
        var attempt: SubmissionAttempt? = null
    }
    private val entries = linkedMapOf<CommandId, Entry>()
    private var accepting = true
    private var nextAttempt = 0L
    var chargedBytes: Long = 0; private set
    val retainedEntries: Int get() = entries.size
    val unconfirmedEntries: Int get() = entries.values.count { it.state !is SubmissionState.Confirmed }
    init { require(maxEntries > 0 && byteBudget >= 0) }

    fun state(id: CommandId): SubmissionState? = entries[id]?.state
    fun submission(id: CommandId): StrokeSubmission? = entries[id]?.submission

    fun enqueue(submission: StrokeSubmission): AdmissionResult {
        entries[submission.id]?.let { old ->
            return if (old.submission == submission) AdmissionResult.Existing(old.state)
            else AdmissionResult.Rejected(AdmissionRejection.REUSED_ID)
        }
        if (!accepting) return AdmissionResult.Rejected(AdmissionRejection.STOPPED)
        if (submission.stroke.target.sceneId != sceneId) return AdmissionResult.Rejected(AdmissionRejection.WRONG_SCENE)
        if (entries.size >= maxEntries) return AdmissionResult.Rejected(AdmissionRejection.ENTRY_CAPACITY)
        if (submission.chargedBytes > byteBudget - chargedBytes) return AdmissionResult.Rejected(AdmissionRejection.BYTE_CAPACITY)
        entries[submission.id] = Entry(submission)
        chargedBytes += submission.chargedBytes
        return AdmissionResult.Admitted
    }

    /** At first dispatch prepare against the authority snapshot, never the UI's DOWN revision. */
    fun dispatch(scene: SceneSnapshot): DispatchResult {
        val entry = entries.values.firstOrNull { it.state !is SubmissionState.Confirmed } ?: return DispatchResult.NoWork
        val submission = entry.submission
        when (val state = entry.state) {
            is SubmissionState.InFlight -> return DispatchResult.Busy(submission.id)
            is SubmissionState.RetryRequired -> return DispatchResult.RetryRequired(submission.id)
            is SubmissionState.Blocked -> return DispatchResult.Blocked(submission.id, state.reason)
            else -> Unit
        }
        if (scene.id != sceneId) return DispatchResult.Blocked(submission.id, Rejection.WRONG_SCENE)
        if (nextAttempt == Long.MAX_VALUE) return DispatchResult.AttemptNumbersExhausted
        if (entry.patch == null) {
            val command = submission.stroke.command(submission.id, submission.objectId, scene.revision)
            when (val prepared = SceneReducer.prepare(scene, command)) {
                is PrepareResult.Prepared -> entry.patch = prepared.patch
                is PrepareResult.Rejected -> {
                    entry.state = SubmissionState.Blocked(prepared.reason)
                    return DispatchResult.Blocked(submission.id, prepared.reason)
                }
            }
        }
        // After an ambiguous transport failure replay the EXACT patch, including its original base.
        val attempt = SubmissionAttempt(submission.id, ++nextAttempt, entry.patch!!)
        entry.attempt = attempt
        entry.state = SubmissionState.InFlight(attempt.number)
        return DispatchResult.Ready(attempt)
    }

    private fun active(attempt: SubmissionAttempt): Entry? = entries[attempt.id]?.takeIf {
        it.attempt === attempt && it.state is SubmissionState.InFlight
    }

    fun complete(attempt: SubmissionAttempt, result: CommitResult): CompletionUpdate {
        val entry = active(attempt) ?: return CompletionUpdate.STALE_ATTEMPT
        if (result is CommitResult.Accepted && (result.receipt.commandId != attempt.id ||
                    result.receipt.sceneRevision != attempt.patch.baseRevision + 1)) return CompletionUpdate.INVALID_RECEIPT
        entry.state = when (result) {
            is CommitResult.Accepted -> SubmissionState.Confirmed(result.receipt)
            is CommitResult.Rejected -> SubmissionState.Blocked(result.reason)
        }
        entry.attempt = null
        return CompletionUpdate.UPDATED
    }

    /** The commit outcome is unknown. Keep exact payload/patch; do not claim failure rolled it back. */
    fun transportFailed(attempt: SubmissionAttempt, detail: String): CompletionUpdate {
        require(detail.isNotBlank() && detail.length <= 256)
        val entry = active(attempt) ?: return CompletionUpdate.STALE_ATTEMPT
        entry.state = SubmissionState.RetryRequired(detail)
        entry.attempt = null
        return CompletionUpdate.UPDATED
    }

    fun retry(id: CommandId): Boolean {
        val entry = entries[id] ?: return false
        if (entry.state !is SubmissionState.RetryRequired) return false
        entry.state = SubmissionState.Queued
        return true
    }

    /** Transfer accepted payload + receipt back to the host; never removes pending/failed work. */
    fun collectConfirmed(id: CommandId): Pair<StrokeSubmission, CommitReceipt>? {
        val entry = entries[id] ?: return null
        val state = entry.state as? SubmissionState.Confirmed ?: return null
        entries.remove(id)
        chargedBytes -= entry.submission.chargedBytes
        return entry.submission to state.receipt
    }

    /** Explicit recovery handoff for a definitive rejection; ambiguous/in-flight work cannot transfer. */
    fun collectBlocked(id: CommandId): Pair<StrokeSubmission, Rejection>? {
        val entry = entries[id] ?: return null
        val state = entry.state as? SubmissionState.Blocked ?: return null
        entries.remove(id)
        chargedBytes -= entry.submission.chargedBytes
        return entry.submission to state.reason
    }

    /** Stop new admissions, continue draining previously owned work; deliberately no discard/clear. */
    fun stopAccepting() { accepting = false }
}
