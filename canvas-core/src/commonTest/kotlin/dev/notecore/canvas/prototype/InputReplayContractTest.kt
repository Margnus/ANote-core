package dev.notecore.canvas.prototype

import kotlin.test.*

class InputReplayContractTest {
    private val target = StrokeTarget(SceneId("page"), LayerId("captured"), BrushSpec(BrushKind.MONOLINE, 1, 2.0, -1))
    private fun point(t: Long) = InputSample(t.toDouble(), 0.0, 0.5f, t)
    private fun batch(seq: Long, phase: PointerPhase, vararg times: Long, pointer: Long = 1,
                      predicted: List<InputSample> = emptyList(), tool: PointerTool = PointerTool.STYLUS) =
        InputBatch(seq, times.lastOrNull() ?: seq, pointer, tool, phase, times.map(::point), predicted)

    @Test fun historyIsRetainedAndPredictionNeverEntersCompletedSamples() {
        val s = InputReplaySession()
        s.consume(batch(0, PointerPhase.DOWN, 0), target)
        assertEquals(4, assertIs<InputResult.Preview>(s.consume(batch(1, PointerPhase.MOVE, 1, 2, 3,
            predicted = listOf(point(4), point(5))), target)).realCount)
        val result = assertIs<InputResult.Completed>(s.consume(batch(2, PointerPhase.UP, 4), target)).stroke
        assertEquals(5, result.samples.size)
        for (i in 0 until 5) assertEquals(i.toLong(), result.samples.timeMicros(i))
    }

    @Test fun targetIsCapturedAtDownAndCompletedOutputSurvivesClose() {
        val s = InputReplaySession(); s.consume(batch(0, PointerPhase.DOWN, 0), target)
        val result = assertIs<InputResult.Completed>(s.consume(batch(1, PointerPhase.UP, 1), target.copy(layerId = LayerId("later")))).stroke
        s.close()
        assertEquals(target, result.target)
        assertEquals(InputRejection.CLOSED, assertIs<InputResult.Rejected>(s.consume(batch(2, PointerPhase.DOWN, 2), target)).reason)
    }

    @Test fun cancelDiscardsOnlyUnfinishedStrokeAndAllowsNewStroke() {
        val s = InputReplaySession(); s.consume(batch(0, PointerPhase.DOWN, 0), target)
        assertIs<InputResult.Cancelled>(s.consume(batch(1, PointerPhase.CANCEL, 1), target))
        assertIs<InputResult.Rejected>(s.consume(batch(2, PointerPhase.UP, 2), target))
        s.consume(batch(2, PointerPhase.DOWN, 2), target)
        assertEquals(2, assertIs<InputResult.Completed>(s.consume(batch(3, PointerPhase.UP, 3), target)).stroke.samples.size)
    }

    @Test fun secondStylusIsExplicitlyRejectedAndFingerCannotEndInk() {
        val s = InputReplaySession(); s.consume(batch(0, PointerPhase.DOWN, 0), target)
        assertEquals(InputRejection.POINTER_BUSY, assertIs<InputResult.Rejected>(s.consume(batch(1, PointerPhase.DOWN, 1, pointer = 2), target)).reason)
        assertIs<InputResult.IgnoredNavigation>(s.consume(batch(1, PointerPhase.UP, 1, pointer = 2, tool = PointerTool.FINGER), target))
        assertEquals(2, assertIs<InputResult.Completed>(s.consume(batch(2, PointerPhase.UP, 2), target)).stroke.samples.size)
    }

    @Test fun invalidBatchDoesNotAppendPartialHistoryOrAdvanceSequence() {
        val s = InputReplaySession(); s.consume(batch(0, PointerPhase.DOWN, 10), target)
        val bad = InputBatch(1, 20, 1, PointerTool.STYLUS, PointerPhase.MOVE, listOf(point(11), point(9)))
        assertEquals(InputRejection.INVALID_BATCH, assertIs<InputResult.Rejected>(s.consume(bad, target)).reason)
        assertEquals(2, assertIs<InputResult.Completed>(s.consume(batch(1, PointerPhase.UP, 20), target)).stroke.samples.size)
    }

    @Test fun overflowPreservesStrokeAndReportsCapacity() {
        val s = InputReplaySession(3); s.consume(batch(0, PointerPhase.DOWN, 0), target)
        assertEquals(InputRejection.CAPACITY, assertIs<InputResult.Rejected>(s.consume(batch(1, PointerPhase.MOVE, 1, 2, 3), target)).reason)
        assertEquals(3, assertIs<InputResult.Completed>(s.consume(batch(1, PointerPhase.UP, 1, 2), target)).stroke.samples.size)
    }

    @Test fun duplicateSequenceAndRegressingEventTimeAreRejected() {
        val s = InputReplaySession(); s.consume(batch(0, PointerPhase.DOWN, 10), target)
        assertEquals(InputRejection.EVENT_ORDER, assertIs<InputResult.Rejected>(s.consume(batch(0, PointerPhase.MOVE, 11), target)).reason)
        assertEquals(InputRejection.EVENT_ORDER, assertIs<InputResult.Rejected>(s.consume(batch(1, PointerPhase.MOVE, 9), target)).reason)
    }

    @Test fun ownedBatchSurvivesCallerListMutation() {
        val points = mutableListOf(point(0)); val event = InputBatch(0, 0, 1, PointerTool.STYLUS, PointerPhase.DOWN, points)
        points.clear()
        assertEquals(1, assertIs<InputResult.Preview>(InputReplaySession().consume(event, target)).realCount)
    }

    @Test fun emptyUpAndPredictionOnlyMoveDoNotDuplicateRealPoints() {
        val s = InputReplaySession(); s.consume(batch(0, PointerPhase.DOWN, 0), target)
        val preview = InputBatch(1, 1, 1, PointerTool.STYLUS, PointerPhase.MOVE, emptyList(), listOf(point(2)))
        assertEquals(1, assertIs<InputResult.Preview>(s.consume(preview, target)).realCount)
        val up = InputBatch(2, 2, 1, PointerTool.STYLUS, PointerPhase.UP, emptyList())
        assertEquals(1, assertIs<InputResult.Completed>(s.consume(up, target)).stroke.samples.size)
    }

    @Test fun nonFuturePredictionAndWrongPointerDoNotChangeActiveStroke() {
        val s = InputReplaySession(); s.consume(batch(0, PointerPhase.DOWN, 0), target)
        assertEquals(InputRejection.INVALID_BATCH, assertIs<InputResult.Rejected>(s.consume(
            batch(1, PointerPhase.MOVE, 1, predicted = listOf(point(1))), target)).reason)
        assertEquals(InputRejection.NO_ACTIVE_POINTER, assertIs<InputResult.Rejected>(s.consume(
            batch(1, PointerPhase.UP, 1, pointer = 2), target)).reason)
        assertEquals(2, assertIs<InputResult.Completed>(s.consume(batch(1, PointerPhase.UP, 1), target)).stroke.samples.size)
    }

    @Test fun longStrokeRetainsEveryRealPoint() {
        val s = InputReplaySession(20_000); s.consume(batch(0, PointerPhase.DOWN, 0), target)
        for (i in 1L..9_998L) s.consume(batch(i, PointerPhase.MOVE, i), target)
        val completed = assertIs<InputResult.Completed>(s.consume(batch(9_999, PointerPhase.UP, 9_999), target)).stroke
        assertEquals(10_000, completed.samples.size)
        for (i in 0 until 10_000) assertEquals(i.toLong(), completed.samples.timeMicros(i))
    }
}
