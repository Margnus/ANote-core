package dev.notecore.canvas.prototype

/** C1 command-line demonstration, not a native canvas or persistent notebook. */
fun main() {
    val sceneId = SceneId("demo-page")
    val memory = MemorySceneCoordinator(SceneSnapshot.empty(sceneId))
    val target = StrokeTarget(sceneId, LayerId("captured-layer"), BrushSpec(BrushKind.MONOLINE, 1, 2.0, -1))
    val samples = StrokeSamples.copyOf(doubleArrayOf(10.0, 20.0), doubleArrayOf(10.0, 30.0), floatArrayOf(0.4f, 0.6f), longArrayOf(0, 1_000))
    val submissions = StrokeSubmissionQueue(sceneId)
    val submission = StrokeSubmission(CommandId("demo-add"), ObjectId("stroke-1"), CompletedInput(target, samples))
    check(submissions.enqueue(submission) == AdmissionResult.Admitted)
    val first = (submissions.dispatch(memory.snapshot()) as DispatchResult.Ready).attempt
    check(memory.commit(first.patch) is CommitResult.Accepted) // model a commit whose acknowledgement is lost
    submissions.transportFailed(first, "simulated lost acknowledgement")
    check(submissions.retry(submission.id))
    val retry = (submissions.dispatch(memory.snapshot()) as DispatchResult.Ready).attempt
    check(submissions.complete(retry, memory.commit(retry.patch)) == CompletionUpdate.UPDATED)
    val receipt = submissions.collectConfirmed(submission.id)!!.second
    println("scene=${sceneId.value} revision=${receipt.sceneRevision} layer=${memory.snapshot().find(ObjectId("stroke-1"))!!.layerId.value}")
    println("durability=${receipt.durability}; disk persistence and native rendering are not implemented")
    println("submission_retry_objects=${memory.snapshot().objectCount} queue_charged_bytes=${submissions.chargedBytes}")
    val oldFrame = FrameToken(sceneId, 0, 0, 1, 1, 0)
    val gate = FramePublicationGate(oldFrame)
    gate.expect(oldFrame.copy(sceneRevision = receipt.sceneRevision, requestSequence = 1))
    println("old_frame_publish=${gate.canPublish(oldFrame)}")
    val cache = WeightedLruCache<String, Long>(8) { it }
    cache.put("a", 4); cache.put("a", 4); cache.put("b", 4); cache.put("c", 4)
    println("cache_entries=${cache.size} resident_bytes=${cache.residentBytes} budget=${cache.maxBytes}")
    val resources = ResourceLeasePool<String, ByteArray>(4, 8)
    val reserved = (resources.reserve(4) as ReservationResult.Reserved).reservation
    check(resources.publish(reserved, "tile", ByteArray(4)) is ResourcePublication.Resident)
    val reader = (resources.borrow("tile") as ResourceBorrow.Borrowed<ByteArray>).lease
    resources.close()
    check(resources.nextRelease(ResourceLane.GPU) is ResourceReleaseDispatch.Idle)
    println("resource_model_closed_charged=${resources.chargedBytes} borrowed=${resources.borrowedBytes}")
    resources.returnLease(reader)
    val release = (resources.nextRelease(ResourceLane.GPU) as ResourceReleaseDispatch.Ready<ByteArray>).ticket
    check(release.value.size == 4) // simulated backend acknowledgement; no native destructor here
    resources.completeRelease(release, ResourceReleaseOutcome.RELEASED)
    println("resource_model_drained=${resources.isDrained} charged=${resources.chargedBytes}; native_gpu_verified=false")
    val document = DocumentCloseCoordinator<String, ByteArray>(sceneId)
    val session = (document.openSession(oldFrame, 10) as SessionOpenResult.Opened).session
    document.consume(session, InputBatch(0, 0, 1, PointerTool.STYLUS, PointerPhase.DOWN, listOf(InputSample(1.0, 1.0, 0.5f, 0))), target)
    document.consume(session, InputBatch(1, 1, 1, PointerTool.STYLUS, PointerPhase.UP, listOf(InputSample(2.0, 2.0, 0.5f, 1))), target)
    check(document.submitPending(session, CommandId("close-add"), ObjectId("close-stroke")) == AdmissionResult.Admitted)
    val closeAttempt = (document.dispatch(memory.snapshot()) as DispatchResult.Ready).attempt
    document.beginClose()
    println("document_close_before_ack=${document.status().phase} late_frame_publish=${document.canPublish(session, oldFrame)}")
    document.complete(closeAttempt, memory.commit(closeAttempt.patch))
    val offer = document.offerSubmission(closeAttempt.id)!!
    val ownedByReceiver = offer.payload as RecoveryPayload.Confirmed
    println("document_close_before_handoff=${document.status().phase} receipt=${ownedByReceiver.receipt.durability}")
    check(document.acknowledge(offer))
    println("document_close_after_handoff=${document.status().phase} retained_submissions=${document.status().submissions.size}; durable_recovery_verified=false")
    val executor = BoundedOwnerExecutor(2, 16, "NoteCore-demo-worker")
    try {
        var received = 0
        repeat(2) {
            check(executor.submit(4, { executor.checkWorkerThread(); Thread.currentThread().id }) { result ->
                executor.checkOwnerThread()
                check((result as WorkResult.Succeeded<Long>).value != Thread.currentThread().id)
                received++; ResultDisposition.ACKNOWLEDGED
            } is WorkAdmission.Accepted)
        }
        executor.stopAccepting(); check(executor.awaitWorkerStop(3000))
        println("real_worker_stopped=${executor.status().workerStopped} before_ack_charged=${executor.status().chargedBytes} drained=${executor.status().isDrained}")
        executor.drainResults()
        println("real_owner_received=$received after_ack_charged=${executor.status().chargedBytes} drained=${executor.status().isDrained}; apple_executor_verified=false")
    } finally { executor.stopAccepting(); check(executor.awaitWorkerStop(3000)) }
}
