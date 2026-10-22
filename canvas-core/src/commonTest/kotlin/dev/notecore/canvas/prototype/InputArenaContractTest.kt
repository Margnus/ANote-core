package dev.notecore.canvas.prototype

import kotlin.test.*

class InputArenaContractTest {
    private val target = StrokeTarget(SceneId("arena"), LayerId("ink"), BrushSpec(BrushKind.MONOLINE, 1, 2.0, -1))
    private fun point(i: Int) = InputSample(i.toDouble(), -i.toDouble(), (i % 10) / 10f, i.toLong())

    @Test fun blockBoundaryFlattensOnceAndTheRetiredBuilderCannotMutateCanonicalSamples() {
        val builder = StrokeSamples.Builder(600)
        val source = MutableList(257) { point(it) }; builder.append(source)
        assertEquals(512, builder.allocatedPointCapacity)
        assertEquals(256L + 2 * 128 + 512 * 28, builder.logicalByteCharge)
        val samples = builder.finish(); source.clear()
        assertEquals(257, samples.size); assertEquals(0L, builder.logicalByteCharge)
        assertFailsWith<IllegalStateException> { builder.append(listOf(point(300))) }
        assertFailsWith<IllegalStateException> { builder.finish() }
        for (i in 0 until samples.size) {
            assertEquals(i.toDouble(), samples.x(i)); assertEquals(-i.toDouble(), samples.y(i))
            assertEquals((i % 10) / 10f, samples.pressure(i)); assertEquals(i.toLong(), samples.timeMicros(i))
        }
    }

    @Test fun lastChunkNeverAllocatesBeyondTheConfiguredPointCapacityAndRefusalIsAtomic() {
        val builder = StrokeSamples.Builder(270); builder.append(List(256) { point(it) })
        assertEquals(256, builder.allocatedPointCapacity)
        assertFailsWith<IllegalArgumentException> { builder.append(List(15) { point(256 + it) }) }
        assertEquals(256, builder.size); assertEquals(256, builder.allocatedPointCapacity)
        builder.append(List(14) { point(256 + it) })
        assertEquals(270, builder.allocatedPointCapacity); assertEquals(270, builder.finish().size)
    }

    @Test fun rejectedTimeOrderDoesNotAllocateAnotherChunkOrAlterAnAcceptedPrefix() {
        val builder = StrokeSamples.Builder(600); builder.append(List(256) { point(it) })
        val charge = builder.logicalByteCharge
        assertFailsWith<IllegalArgumentException> { builder.append(listOf(point(256), point(254))) }
        assertEquals(256, builder.size); assertEquals(charge, builder.logicalByteCharge)
        builder.append(listOf(point(256)))
        val samples = builder.finish(); assertEquals(257, samples.size); assertEquals(256L, samples.timeMicros(256))
    }

    @Test fun originalSessionRetainsAllBatchedPointsAcrossChunksAndDoesNotAliasTheNextStroke() {
        val session = InputReplaySession(800)
        fun batch(sequence: Long, phase: PointerPhase, start: Int, end: Int) = InputBatch(sequence, (end - 1).toLong(),
            1, PointerTool.STYLUS, phase, (start until end).map(::point))
        session.consume(batch(0, PointerPhase.DOWN, 0, 3), target)
        session.consume(batch(1, PointerPhase.MOVE, 3, 303), target)
        val output = assertIs<InputResult.Completed>(session.consume(batch(2, PointerPhase.UP, 303, 800), target)).stroke.samples
        assertEquals(0L, session.activePointByteCharge); assertEquals(0, session.activeRealSamples)
        session.consume(batch(3, PointerPhase.DOWN, 800, 801), target)
        assertTrue(session.activePointByteCharge > 0); session.close(); assertEquals(0L, session.activePointByteCharge)
        assertEquals(800, output.size)
        for (i in 0 until 800) {
            assertEquals(point(i).x, output.x(i)); assertEquals(point(i).y, output.y(i))
            assertEquals(point(i).pressure, output.pressure(i)); assertEquals(point(i).timeMicros, output.timeMicros(i))
        }
    }

    @Test fun predictionDoesNotGrowTheRealArenaAndCancellationReleasesOnlyUnfinishedInput() {
        val session = InputReplaySession(1000)
        session.consume(InputBatch(0, 0, 1, PointerTool.STYLUS, PointerPhase.DOWN, listOf(point(0))), target)
        val charge = session.activePointByteCharge
        val predicted = (2..300).map(::point)
        val preview = assertIs<InputResult.Preview>(session.consume(
            InputBatch(1, 1, 1, PointerTool.STYLUS, PointerPhase.MOVE, emptyList(), predicted), target))
        assertEquals(predicted, preview.prediction); assertEquals(charge, session.activePointByteCharge)
        assertEquals(1, session.activeRealSamples)
        assertIs<InputResult.Cancelled>(session.consume(InputBatch(2, 301, 1, PointerTool.STYLUS, PointerPhase.CANCEL, emptyList()), target))
        assertEquals(0L, session.activePointByteCharge); assertEquals(0, session.activeRealSamples)
    }

    @Test fun reservationCoversOwnedChunksAndImmutableCompletionPayloadBeforeAdmission() {
        assertEquals(1200L, InputReplaySession.reservationBytes(10))
        assertEquals(1130624L, InputReplaySession.reservationBytes(20_000))
        for (capacity in listOf(1, 256, 257, 1_000_000)) {
            val builder = StrokeSamples.Builder(capacity); builder.append(List(minOf(5, capacity)) { point(it) })
            val arena = builder.logicalByteCharge; val samples = builder.finish()
            assertTrue(arena + samples.logicalByteCharge <= InputReplaySession.reservationBytes(capacity))
            assertEquals(0L, builder.logicalByteCharge)
        }
    }
}
