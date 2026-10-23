package dev.notecore.canvas.prototype

import kotlin.test.*

class InputScalarReplayTest {
    private val target = StrokeTarget(SceneId("scalar"), LayerId("layer"), BrushSpec(BrushKind.MONOLINE, 1, 2.0, -1))
    private fun raw(s: InputReplaySession, seq: Long, phase: PointerPhase, times: LongArray = longArrayOf(seq),
        pointer: Long = 1, tool: PointerTool = PointerTool.STYLUS, event: Long = times.lastOrNull() ?: seq) =
        s.consumeReal(seq, event, pointer, tool, phase, DoubleArray(times.size) { times[it].toDouble() },
            DoubleArray(times.size) { 2.0 }, FloatArray(times.size) { .5f }, times, target)

    @Test fun scalarAndLegacyPathsMatchAcrossChunksAndCallerArraysNeverEscape() {
        val direct = InputReplaySession(300); val legacy = InputReplaySession(300)
        val xs = DoubleArray(257) { if (it == 0) -0.0 else it + .25 }
        val ys = DoubleArray(257) { it - .75 }; val ps = FloatArray(257) { if (it == 0) -0.0f else (it % 10) / 10f }
        val times = LongArray(257) { it / 2L }
        val points = List(257) { InputSample(xs[it], ys[it], ps[it], times[it]) }
        assertIs<InputResult.Preview>(direct.consumeReal(0, 128, 1, PointerTool.STYLUS, PointerPhase.DOWN, xs, ys, ps, times, target))
        assertIs<InputResult.Preview>(legacy.consume(InputBatch(0, 128, 1, PointerTool.STYLUS, PointerPhase.DOWN, points), target))
        xs.fill(Double.NaN); ys.fill(99.0); ps.fill(1f); times.fill(999)
        val changedTarget = target.copy(layerId = LayerId("later"))
        val a = assertIs<InputResult.Completed>(direct.consumeReal(1, 129, 1, PointerTool.STYLUS, PointerPhase.UP,
            doubleArrayOf(9.0), doubleArrayOf(8.0), floatArrayOf(.3f), longArrayOf(129), changedTarget)).stroke
        val b = assertIs<InputResult.Completed>(legacy.consume(InputBatch(1, 129, 1, PointerTool.STYLUS, PointerPhase.UP,
            listOf(InputSample(9.0, 8.0, .3f, 129))), changedTarget)).stroke
        assertEquals(b, a); assertEquals(target, a.target); assertEquals(258, a.samples.size)
        assertEquals((-0.0).toBits(), a.samples.x(0).toBits()); assertEquals((-0.0f).toBits(), a.samples.pressure(0).toBits())
        direct.close(); legacy.close(); assertEquals(b.samples, a.samples)
    }
    @Test fun invalidSuffixAndRegressingTimeRejectWholeBatchWithoutAdvancingPrefixOrSequence() {
        val s = InputReplaySession(3); assertIs<InputResult.Preview>(raw(s, 0, PointerPhase.DOWN, longArrayOf(1)))
        val charge = s.activePointByteCharge
        val bad = s.consumeReal(1, 3, 1, PointerTool.STYLUS, PointerPhase.MOVE,
            doubleArrayOf(2.0, Double.NaN), doubleArrayOf(2.0, 2.0), floatArrayOf(.5f, .5f), longArrayOf(2, 3), target)
        assertEquals(InputRejection.INVALID_BATCH, assertIs<InputResult.Rejected>(bad).reason)
        assertEquals(1, s.activeRealSamples); assertEquals(charge, s.activePointByteCharge)
        assertEquals(InputRejection.INVALID_BATCH, assertIs<InputResult.Rejected>(raw(s, 1, PointerPhase.MOVE, longArrayOf(0), event = 3)).reason)
        assertIs<InputResult.Preview>(raw(s, 1, PointerPhase.MOVE, longArrayOf(2)))
        val done = assertIs<InputResult.Completed>(raw(s, 2, PointerPhase.UP, longArrayOf(3))).stroke
        assertEquals(listOf(1.0, 2.0, 3.0), List(done.samples.size) { done.samples.x(it) })
    }
    @Test fun invalidEnvelopeAndShapeRefuseBeforeAnyPointAllocationAndClosedIsTerminal() {
        val s = InputReplaySession(4)
        for (result in listOf(raw(s, -1, PointerPhase.DOWN), raw(s, 0, PointerPhase.DOWN, event = -1),
            raw(s, 0, PointerPhase.DOWN, pointer = -1),
            s.consumeReal(0, 1, 1, PointerTool.STYLUS, PointerPhase.DOWN,
                doubleArrayOf(1.0, 2.0), doubleArrayOf(1.0), floatArrayOf(.5f), longArrayOf(1), target)))
            assertEquals(InputRejection.INVALID_BATCH, assertIs<InputResult.Rejected>(result).reason)
        assertEquals(0, s.activeRealSamples); assertEquals(0L, s.activePointByteCharge)
        assertIs<InputResult.Preview>(raw(s, 0, PointerPhase.DOWN))
        s.close(); assertEquals(0L, s.activePointByteCharge)
        assertEquals(InputRejection.CLOSED, assertIs<InputResult.Rejected>(raw(s, 1, PointerPhase.DOWN)).reason)
    }
    @Test fun capacityRefusesSuffixThenEmptyUpAndCancellationKeepOriginalOwnershipRules() {
        val s = InputReplaySession(3)
        assertIs<InputResult.Preview>(raw(s, 0, PointerPhase.DOWN, longArrayOf(0, 1)))
        assertEquals(InputRejection.CAPACITY, assertIs<InputResult.Rejected>(raw(s, 1, PointerPhase.MOVE, longArrayOf(2, 3))).reason)
        assertEquals(2, s.activeRealSamples)
        val done = assertIs<InputResult.Completed>(raw(s, 1, PointerPhase.UP, longArrayOf(), event = 1)).stroke
        assertEquals(2, done.samples.size)
        assertIs<InputResult.Preview>(raw(s, 2, PointerPhase.DOWN))
        assertIs<InputResult.Cancelled>(raw(s, 3, PointerPhase.CANCEL, longArrayOf()))
        assertEquals(0, s.activeRealSamples); assertEquals(0L, s.activePointByteCharge)
        assertIs<InputResult.Preview>(raw(s, 4, PointerPhase.DOWN)); s.close()
        assertEquals(2, done.samples.size)
    }
    @Test fun mixedPredictionNavigationAndPointerRefusalUseTheSameStateMachine() {
        val s = InputReplaySession(3)
        assertIs<InputResult.Preview>(raw(s, 0, PointerPhase.DOWN))
        val preview = assertIs<InputResult.Preview>(s.consume(InputBatch(1, 1, 1, PointerTool.STYLUS,
            PointerPhase.MOVE, emptyList(), listOf(InputSample(2.0, 3.0, .5f, 2))), target))
        assertEquals(1, preview.prediction.size)
        assertIs<InputResult.IgnoredNavigation>(raw(s, 2, PointerPhase.MOVE, longArrayOf(), pointer = 2, tool = PointerTool.FINGER))
        assertEquals(InputRejection.NO_ACTIVE_POINTER, assertIs<InputResult.Rejected>(raw(s, 3, PointerPhase.UP, pointer = 2)).reason)
        val done = assertIs<InputResult.Completed>(raw(s, 3, PointerPhase.UP, longArrayOf())).stroke
        assertEquals(1, done.samples.size); assertEquals(0.0, done.samples.x(0)); assertEquals(target, done.target)
    }
}
