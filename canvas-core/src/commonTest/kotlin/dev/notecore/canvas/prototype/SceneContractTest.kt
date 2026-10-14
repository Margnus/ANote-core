package dev.notecore.canvas.prototype

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class SceneContractTest {
    private val sceneId = SceneId("page-stable-id")
    private val layer = LayerId("layer-at-down")
    private val brush = BrushSpec(BrushKind.MONOLINE, 1, 2.0, -1)
    private fun samples(x: Double = 0.0) = StrokeSamples.copyOf(
        doubleArrayOf(x, x + 2), doubleArrayOf(0.0, 2.0), floatArrayOf(0.3f, 0.6f), longArrayOf(0, 1_000)
    )
    private fun add(id: String, base: Long, commandId: String = "add-$id") = CanvasCommand.AddStroke(
        CommandId(commandId), base, StrokeTarget(sceneId, layer, brush), ObjectId(id), samples()
    )
    private fun patch(scene: SceneSnapshot, command: CanvasCommand) = assertIs<PrepareResult.Prepared>(SceneReducer.prepare(scene, command)).patch

    @Test fun realSampleArraysAreDefensivelyOwned() {
        val x = doubleArrayOf(1.0); val pressure = floatArrayOf(0.5f); val time = longArrayOf(10)
        val accepted = StrokeSamples.copyOf(x, doubleArrayOf(2.0), pressure, time)
        x[0] = 999.0; pressure[0] = 0f; time[0] = 20
        assertEquals(1.0, accepted.x(0)); assertEquals(0.5f, accepted.pressure(0)); assertEquals(10L, accepted.timeMicros(0))
    }

    @Test fun rejectsMalformedSamples() {
        assertFailsWith<IllegalArgumentException> { StrokeSamples.copyOf(doubleArrayOf(), doubleArrayOf(), floatArrayOf(), longArrayOf()) }
        assertFailsWith<IllegalArgumentException> { StrokeSamples.copyOf(doubleArrayOf(0.0), doubleArrayOf(0.0), floatArrayOf(2f), longArrayOf(0)) }
        assertFailsWith<IllegalArgumentException> { StrokeSamples.copyOf(doubleArrayOf(0.0, 1.0), doubleArrayOf(0.0, 1.0), floatArrayOf(0f, 0f), longArrayOf(1, 0)) }
    }

    @Test fun capturedLayerSurvivesLaterUiSelectionChange() {
        val memory = MemorySceneCoordinator(SceneSnapshot.empty(sceneId))
        val acceptedAtUp = patch(memory.snapshot(), add("a", 0))
        val uiLayerAfterUp = LayerId("new-current-layer")
        assertIs<CommitResult.Accepted>(memory.commit(acceptedAtUp))
        assertEquals(layer, memory.snapshot().find(ObjectId("a"))!!.layerId)
        assertNotEquals(uiLayerAfterUp, memory.snapshot().find(ObjectId("a"))!!.layerId)
    }

    @Test fun sameBaseConcurrentProposalsCannotBothCommit() {
        val memory = MemorySceneCoordinator(SceneSnapshot.empty(sceneId))
        val a = patch(memory.snapshot(), add("a", 0)); val b = patch(memory.snapshot(), add("b", 0))
        assertIs<CommitResult.Accepted>(memory.commit(a))
        assertEquals(Rejection.STALE_SCENE, assertIs<CommitResult.Rejected>(memory.commit(b)).reason)
        assertEquals(1, memory.snapshot().objectCount)
    }

    @Test fun replayIsIdempotentButReusedIdWithDifferentPayloadIsRejected() {
        val initial = SceneSnapshot.empty(sceneId); val memory = MemorySceneCoordinator(initial)
        val a = patch(initial, add("a", 0, "op")); val b = patch(initial, add("b", 0, "op"))
        val first = assertIs<CommitResult.Accepted>(memory.commit(a))
        assertEquals(first, memory.commit(a))
        assertEquals(1L, memory.snapshot().revision)
        assertEquals(Rejection.REUSED_COMMAND_ID, assertIs<CommitResult.Rejected>(memory.commit(b)).reason)
        assertEquals(Durability.MEMORY_ONLY, first.receipt.durability)
    }

    @Test fun intermediateObjectUpdateChangesRevisionWithoutChangingFirstLastOrCount() {
        val memory = MemorySceneCoordinator(SceneSnapshot.empty(sceneId))
        listOf("a", "b", "c").forEach { memory.commit(patch(memory.snapshot(), add(it, memory.snapshot().revision))) }
        val before = memory.snapshot()
        val transform = CanvasCommand.TransformStroke(CommandId("move-middle"), sceneId, before.revision, ObjectId("b"), 1, Affine2D(tx = 100.0))
        val prepared = patch(before, transform)
        assertEquals(BoundsD(-1.0, -1.0, 103.0, 3.0), prepared.dirtyBounds)
        memory.commit(prepared)
        val after = memory.snapshot()
        assertEquals(before.objectCount, after.objectCount)
        assertEquals(before.find(ObjectId("a")), after.find(ObjectId("a")))
        assertEquals(before.find(ObjectId("c")), after.find(ObjectId("c")))
        assertNotEquals(before.revision, after.revision)
        assertNotEquals(before.find(ObjectId("b")), after.find(ObjectId("b")))
        assertEquals(1L, before.find(ObjectId("b"))!!.objectRevision)
    }

    @Test fun staleObjectAndSingularTransformLeaveSceneUnmodified() {
        val memory = MemorySceneCoordinator(SceneSnapshot.empty(sceneId))
        memory.commit(patch(memory.snapshot(), add("a", 0)))
        val scene = memory.snapshot()
        val stale = CanvasCommand.TransformStroke(CommandId("stale"), sceneId, scene.revision, ObjectId("a"), 99, Affine2D())
        assertEquals(Rejection.STALE_OBJECT, assertIs<PrepareResult.Rejected>(SceneReducer.prepare(scene, stale)).reason)
        val singular = stale.copy(id = CommandId("singular"), expectedObjectRevision = 1, transform = Affine2D(a = 0.0, d = 0.0))
        assertEquals(Rejection.INVALID_GEOMETRY, assertIs<PrepareResult.Rejected>(SceneReducer.prepare(scene, singular)).reason)
        assertEquals(1L, memory.snapshot().revision)
    }

    @Test fun boundedJournalRejectsWithoutLosingAcceptedCommand() {
        val memory = MemorySceneCoordinator(SceneSnapshot.empty(sceneId), journalCapacity = 1)
        val a = patch(memory.snapshot(), add("a", 0)); memory.commit(a)
        val b = patch(memory.snapshot(), add("b", 1))
        assertEquals(Rejection.JOURNAL_FULL, assertIs<CommitResult.Rejected>(memory.commit(b)).reason)
        assertIs<CommitResult.Accepted>(memory.commit(a))
        assertEquals(1, memory.snapshot().objectCount)
    }

    @Test fun wrongSceneCannotEnterAnotherDocument() {
        val scene = SceneSnapshot.empty(sceneId)
        val wrongScene = add("a", 0).copy(target = StrokeTarget(SceneId("different-page"), layer, brush))
        assertEquals(Rejection.WRONG_SCENE, assertIs<PrepareResult.Rejected>(SceneReducer.prepare(scene, wrongScene)).reason)
    }
}
