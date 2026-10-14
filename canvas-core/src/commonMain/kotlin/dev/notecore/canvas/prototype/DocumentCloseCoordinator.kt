package dev.notecore.canvas.prototype

/** Session identity does not retain its document owner. All operations run on one logical owner. */
class DocumentSession internal constructor(internal val owner: Any, val number: Long)
enum class SessionOpenFailure { CLOSING, WRONG_SCENE, STALE_FRAME, SESSION_LIMIT, INPUT_BUDGET, ID_EXHAUSTED }
sealed interface SessionOpenResult {
    data class Opened(val session: DocumentSession) : SessionOpenResult
    data class Rejected(val reason: SessionOpenFailure) : SessionOpenResult
}
enum class SessionInputFailure { CLOSING, CLOSED_OR_FOREIGN, COMPLETED_PENDING, WRONG_SCENE }
sealed interface SessionInputResult {
    data class Consumed(val result: InputResult) : SessionInputResult
    data class Rejected(val reason: SessionInputFailure) : SessionInputResult
}
data class SessionStop(val session: DocumentSession, val cancelledRealSamples: Int, val completedPending: Boolean)
enum class DocumentClosePhase { OPEN, CLOSING, CLOSED }
data class DocumentCloseStatus(
    val phase: DocumentClosePhase, val pendingSessions: Int, val inputReservedBytes: Long,
    val submissions: List<SubmissionSummary>, val submissionChargedBytes: Long, val resources: ResourceDrainStatus,
    val observedSceneRevision: Long,
)
sealed interface RecoveryPayload {
    data class Pending(val session: DocumentSession, val completed: CompletedInput, val proposal: StrokeSubmission?,
                       val lastAdmissionFailure: AdmissionRejection?) : RecoveryPayload
    data class Confirmed(val submission: StrokeSubmission, val receipt: CommitReceipt) : RecoveryPayload
    data class Blocked(val submission: StrokeSubmission, val reason: Rejection) : RecoveryPayload
}
/** Offer is read-only. A receiver must retain the payload BEFORE acknowledging ownership. */
class RecoveryOffer internal constructor(internal val owner: Any, payload: RecoveryPayload) {
    private var held: RecoveryPayload? = payload
    val payload: RecoveryPayload get() = checkNotNull(held) { "Ownership has already transferred" }
    internal fun invalidate() { held = null }
}

/** D3 reference closure, not a thread-safe executor, saved-to-disk receipt or platform GPU backend.
 * Owns all sessions, one bounded submission queue and one resource pool until explicit obligations drain.
 */
class DocumentCloseCoordinator<K : Any, V : Any>(
    val sceneId: SceneId, maxSubmissions: Int = 64, submissionBudget: Long = 8L * 1024 * 1024,
    residentBudget: Long = 4L * 1024 * 1024, resourceBudget: Long = 8L * 1024 * 1024,
    private val maxSessions: Int = 8, private val inputBudget: Long = 8L * 1024 * 1024,
    maxResources: Int = 256, maxLeases: Int = 1024, releaseLane: ResourceLane = ResourceLane.GPU,
) {
    private val identity = Any()
    private class Session(val input: InputReplaySession, val gate: FramePublicationGate, val reservation: Long) {
        var closed = false
        var pending: CompletedInput? = null
        var proposal: StrokeSubmission? = null
        var admissionFailure: AdmissionRejection? = null
        var offer: RecoveryOffer? = null
    }
    private val sessions = linkedMapOf<DocumentSession, Session>()
    private val queue = StrokeSubmissionQueue(sceneId, maxSubmissions, submissionBudget)
    private val resources = ResourceLeasePool<K, V>(residentBudget, resourceBudget, maxResources, maxLeases, releaseLane)
    private val submissionOffers = mutableMapOf<CommandId, RecoveryOffer>()
    private var closing = false
    private var nextSession = 0L
    private var inputReserved = 0L
    private var observedRevision = 0L
    init { require(maxSessions > 0 && inputBudget >= 0) }

    fun openSession(initialFrame: FrameToken, maxRealSamples: Int = 100_000): SessionOpenResult {
        require(maxRealSamples in 1..1_000_000)
        val charge = 256L + maxRealSamples.toLong() * 28
        val failure = when {
            closing -> SessionOpenFailure.CLOSING
            initialFrame.sceneId != sceneId -> SessionOpenFailure.WRONG_SCENE
            initialFrame.sceneRevision < observedRevision -> SessionOpenFailure.STALE_FRAME
            sessions.size >= maxSessions -> SessionOpenFailure.SESSION_LIMIT
            charge > inputBudget - inputReserved -> SessionOpenFailure.INPUT_BUDGET
            nextSession == Long.MAX_VALUE -> SessionOpenFailure.ID_EXHAUSTED
            else -> null
        }
        if (failure != null) return SessionOpenResult.Rejected(failure)
        val token = DocumentSession(identity, ++nextSession)
        sessions[token] = Session(InputReplaySession(maxRealSamples), FramePublicationGate(initialFrame), charge)
        inputReserved += charge
        return SessionOpenResult.Opened(token)
    }
    fun consume(session: DocumentSession, batch: InputBatch, targetAtDown: StrokeTarget): SessionInputResult {
        if (closing) return SessionInputResult.Rejected(SessionInputFailure.CLOSING)
        val state = sessions[session]?.takeIf { !it.closed } ?: return SessionInputResult.Rejected(SessionInputFailure.CLOSED_OR_FOREIGN)
        if (state.pending != null) return SessionInputResult.Rejected(SessionInputFailure.COMPLETED_PENDING)
        if (batch.phase == PointerPhase.DOWN && targetAtDown.sceneId != sceneId) return SessionInputResult.Rejected(SessionInputFailure.WRONG_SCENE)
        val result = state.input.consume(batch, targetAtDown)
        if (result is InputResult.Completed) state.pending = result.stroke
        return SessionInputResult.Consumed(result)
    }
    fun pending(session: DocumentSession): CompletedInput? = sessions[session]?.pending
    /** First assignment is stable across capacity rejection; a later call may not replace IDs. */
    fun submitPending(session: DocumentSession, id: CommandId, objectId: ObjectId): AdmissionResult {
        val state = sessions[session] ?: return AdmissionResult.Rejected(AdmissionRejection.STOPPED)
        val completed = state.pending ?: return AdmissionResult.Rejected(AdmissionRejection.STOPPED)
        if (state.offer != null || closing) return AdmissionResult.Rejected(AdmissionRejection.STOPPED)
        val proposal = state.proposal ?: StrokeSubmission(id, objectId, completed).also { state.proposal = it }
        if (proposal.id != id || proposal.objectId != objectId) return AdmissionResult.Rejected(AdmissionRejection.REUSED_ID)
        val result = queue.enqueue(proposal)
        if (result is AdmissionResult.Admitted || result is AdmissionResult.Existing) {
            state.pending = null; state.proposal = null; state.admissionFailure = null
            if (state.closed) removeSession(session)
        } else state.admissionFailure = (result as AdmissionResult.Rejected).reason
        return result
    }
    fun expectFrame(session: DocumentSession, frame: FrameToken): Boolean =
        !closing && frame.sceneId == sceneId && frame.sceneRevision >= observedRevision && sessions[session]?.takeIf { !it.closed }?.gate?.expect(frame) == true
    fun canPublish(session: DocumentSession, frame: FrameToken): Boolean =
        !closing && frame.sceneRevision >= observedRevision && sessions[session]?.takeIf { !it.closed }?.gate?.canPublish(frame) == true

    /** Only unfinished real input is cancelled, with its sample count reported to the host. */
    fun closeSession(session: DocumentSession): SessionStop? {
        val state = sessions[session]?.takeIf { !it.closed } ?: return null
        val count = state.input.activeRealSamples
        state.input.close(); state.gate.close(); state.closed = true
        val stop = SessionStop(session, count, state.pending != null)
        if (state.pending == null) removeSession(session)
        return stop
    }
    private fun removeSession(session: DocumentSession) { sessions.remove(session)?.let { inputReserved -= it.reservation } }
    fun beginClose(): List<SessionStop> {
        if (closing) return emptyList()
        closing = true
        val stopped = sessions.keys.toList().mapNotNull(::closeSession)
        queue.stopAccepting(); resources.close()
        return stopped
    }
    fun status(): DocumentCloseStatus {
        val state = resources.drainStatus()
        val phase = if (!closing) DocumentClosePhase.OPEN else if (sessions.isEmpty() && queue.retainedEntries == 0 && state.isDrained)
            DocumentClosePhase.CLOSED else DocumentClosePhase.CLOSING
        return DocumentCloseStatus(phase, sessions.values.count { it.pending != null }, inputReserved, queue.snapshotStates(), queue.chargedBytes, state, observedRevision)
    }
    /** Host also calls this after external authority changes; snapshots never replace the authority. */
    fun observeScene(scene: SceneSnapshot): Boolean {
        if (scene.id != sceneId || scene.revision < observedRevision) return false
        observedRevision = scene.revision
        return true
    }
    fun dispatch(scene: SceneSnapshot): DispatchResult {
        observeScene(scene)
        return queue.dispatch(scene)
    }
    fun complete(attempt: SubmissionAttempt, result: CommitResult): CompletionUpdate {
        val update = queue.complete(attempt, result)
        if (update == CompletionUpdate.UPDATED && result is CommitResult.Accepted)
            observedRevision = maxOf(observedRevision, result.receipt.sceneRevision)
        return update
    }
    fun transportFailed(attempt: SubmissionAttempt, detail: String) = queue.transportFailed(attempt, detail)
    fun retry(id: CommandId) = queue.retry(id)

    fun offerPending(session: DocumentSession): RecoveryOffer? {
        val state = sessions[session]?.takeIf { it.closed } ?: return null
        val completed = state.pending ?: return null
        return state.offer ?: RecoveryOffer(identity, RecoveryPayload.Pending(session, completed, state.proposal, state.admissionFailure))
            .also { state.offer = it }
    }
    /** In-flight/unknown outcomes are deliberately not transferable as definitive failures. */
    fun offerSubmission(id: CommandId): RecoveryOffer? {
        submissionOffers[id]?.let { return it }
        val submission = queue.submission(id) ?: return null
        val payload = when (val state = queue.state(id)) {
            is SubmissionState.Confirmed -> RecoveryPayload.Confirmed(submission, state.receipt)
            is SubmissionState.Blocked -> RecoveryPayload.Blocked(submission, state.reason)
            else -> return null
        }
        return RecoveryOffer(identity, payload).also { submissionOffers[id] = it }
    }
    /** Explicit receiver acknowledgment transfers responsibility; an offer alone never frees charge. */
    fun acknowledge(offer: RecoveryOffer): Boolean {
        if (offer.owner !== identity) return false
        val session = sessions.entries.firstOrNull { it.value.offer === offer }?.key
        if (session != null) {
            removeSession(session); offer.invalidate(); return true
        }
        val id = submissionOffers.entries.firstOrNull { it.value === offer }?.key ?: return false
        val collected = when (offer.payload) {
            is RecoveryPayload.Confirmed -> queue.collectConfirmed(id) != null
            is RecoveryPayload.Blocked -> queue.collectBlocked(id) != null
            else -> false
        }
        if (!collected) return false
        submissionOffers.remove(id); offer.invalidate(); return true
    }
    fun reserveResource(bytes: Long) = resources.reserve(bytes)
    fun publishResource(reservation: ResourceReservation, key: K, value: V) = resources.publish(reservation, key, value)
    fun cancelUnallocated(reservation: ResourceReservation) = resources.cancelUnallocated(reservation)
    fun borrowResource(key: K) = resources.borrow(key)
    fun returnLease(lease: ResourceLease<V>) = resources.returnLease(lease)
    fun nextRelease(lane: ResourceLane) = resources.nextRelease(lane)
    fun completeRelease(ticket: ResourceReleaseTicket<V>, outcome: ResourceReleaseOutcome) = resources.completeRelease(ticket, outcome)
    fun resolveUnknown(handle: ResourceHandle, outcome: ResourceReleaseOutcome) = resources.resolveUnknown(handle, outcome)
}
