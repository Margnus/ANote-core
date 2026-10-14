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
}
