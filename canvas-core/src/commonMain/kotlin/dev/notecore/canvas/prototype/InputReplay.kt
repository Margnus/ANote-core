package dev.notecore.canvas.prototype

/** Scene coordinates and monotonic microseconds, already normalized by a platform adapter. */
data class InputSample(val x: Double, val y: Double, val pressure: Float, val timeMicros: Long) {
    init {
        require(x.isFinite() && y.isFinite() && kotlin.math.abs(x) <= 1e9 && kotlin.math.abs(y) <= 1e9)
        require(pressure.isFinite() && pressure in 0f..1f && timeMicros >= 0)
    }
}

enum class PointerTool { STYLUS, FINGER }
enum class PointerPhase { DOWN, MOVE, UP, CANCEL }

/** Owns batch lists. History/coalesced real points arrive in time order; prediction is separate. */
class InputBatch(
    val sequence: Long, val timeMicros: Long, val pointerId: Long,
    val tool: PointerTool, val phase: PointerPhase,
    real: List<InputSample>, predicted: List<InputSample> = emptyList(),
) {
    val real: List<InputSample> = real.toList()
    val predicted: List<InputSample> = predicted.toList()
    init { require(sequence >= 0 && timeMicros >= 0 && pointerId >= 0) }
}

/** Synchronous scalar view only. Neither sessions nor builders retain a source or caller array. */
internal interface InputRealSamples {
    val size: Int
    fun x(index: Int): Double
    fun y(index: Int): Double
    fun pressure(index: Int): Float
    fun time(index: Int): Long
}
internal class InputListSamples(private val points: List<InputSample>) : InputRealSamples {
    override val size: Int get() = points.size
    override fun x(index: Int) = points[index].x
    override fun y(index: Int) = points[index].y
    override fun pressure(index: Int) = points[index].pressure
    override fun time(index: Int) = points[index].timeMicros
}
private class InputScalarSamples(private val xs: DoubleArray, private val ys: DoubleArray,
    private val pressures: FloatArray, private val times: LongArray) : InputRealSamples {
    override val size: Int get() = xs.size
    override fun x(index: Int) = xs[index]
    override fun y(index: Int) = ys[index]
    override fun pressure(index: Int) = pressures[index]
    override fun time(index: Int) = times[index]
}

data class CompletedInput(val target: StrokeTarget, val samples: StrokeSamples) {
    /** Only the document authority selects the current base revision at submission. */
    fun command(id: CommandId, objectId: ObjectId, baseRevision: Long) =
        CanvasCommand.AddStroke(id, baseRevision, target, objectId, samples)
}

enum class InputRejection { CLOSED, EVENT_ORDER, POINTER_BUSY, NO_ACTIVE_POINTER, INVALID_BATCH, CAPACITY }
sealed interface InputResult {
    data class Preview(val realCount: Int, val prediction: List<InputSample>) : InputResult
    data class Completed(val stroke: CompletedInput) : InputResult
    data object Cancelled : InputResult
    data object IgnoredNavigation : InputResult
    data class Rejected(val reason: InputRejection) : InputResult
}

/** Single-owner reference accumulator with bounded primitive chunks. Not a thread-safe queue or heap cap.
 * Rejected batches leave sequence, active stroke and points unchanged; caller must surface/retry them.
 * A second stylus is rejected explicitly. Finger navigation never alters the admitted ink pointer.
 */
class InputReplaySession(private val maxRealSamples: Int = 100_000) {
    private data class Active(val pointerId: Long, val target: StrokeTarget, val samples: StrokeSamples.Builder)
    private var active: Active? = null
    private var sequence = -1L
    private var eventTime = -1L
    private var closed = false
    val activeRealSamples: Int get() = active?.samples?.size ?: 0
    val reservedByteCharge: Long get() = reservationBytes(maxRealSamples)
    val activePointByteCharge: Long get() = active?.samples?.logicalByteCharge ?: 0L
    init { require(maxRealSamples in 1..1_000_000) }

    fun consume(batch: InputBatch, targetAtDown: StrokeTarget): InputResult = consume(
        batch.sequence, batch.timeMicros, batch.pointerId, batch.tool, batch.phase,
        InputListSamples(batch.real), batch.predicted, targetAtDown)

    /** Real-only producer path. Raw arrays are read synchronously and never retained.
     * Caller stays on the single owner and must not mutate buffers during this call.
     * Invalid envelopes/scalars refuse the entire batch without allocating point objects or advancing state.
     */
    fun consumeReal(sequence: Long, timeMicros: Long, pointerId: Long, tool: PointerTool, phase: PointerPhase,
        xs: DoubleArray, ys: DoubleArray, pressures: FloatArray, times: LongArray, targetAtDown: StrokeTarget): InputResult {
        if (closed) return InputResult.Rejected(InputRejection.CLOSED)
        if (sequence < 0 || timeMicros < 0 || pointerId < 0 || xs.size != ys.size || xs.size != pressures.size || xs.size != times.size)
            return InputResult.Rejected(InputRejection.INVALID_BATCH)
        return consume(sequence, timeMicros, pointerId, tool, phase, InputScalarSamples(xs, ys, pressures, times), emptyList(), targetAtDown)
    }
    private fun consume(batchSequence: Long, batchTimeMicros: Long, pointerId: Long, tool: PointerTool, phase: PointerPhase,
        real: InputRealSamples, predicted: List<InputSample>, targetAtDown: StrokeTarget): InputResult {
        fun reject(reason: InputRejection) = InputResult.Rejected(reason)
        if (closed) return reject(InputRejection.CLOSED)
        if (batchSequence <= sequence || batchTimeMicros < eventTime) return reject(InputRejection.EVENT_ORDER)
        for (i in 0 until real.size) {
            val x = real.x(i); val y = real.y(i); val pressure = real.pressure(i)
            if (!x.isFinite() || !y.isFinite() || kotlin.math.abs(x) > 1e9 || kotlin.math.abs(y) > 1e9 ||
                !pressure.isFinite() || pressure !in 0f..1f || real.time(i) < 0) return reject(InputRejection.INVALID_BATCH)
        }
        if (tool == PointerTool.FINGER) {
            sequence = batchSequence; eventTime = batchTimeMicros
            return InputResult.IgnoredNavigation
        }
        val previous = active
        if (phase == PointerPhase.DOWN && previous != null) return reject(InputRejection.POINTER_BUSY)
        if (phase != PointerPhase.DOWN && (previous == null || previous.pointerId != pointerId))
            return reject(InputRejection.NO_ACTIVE_POINTER)
        var time = previous?.samples?.lastTimeMicros ?: -1L
        for (i in 0 until real.size) {
            val next = real.time(i)
            if (next < time || next > batchTimeMicros) return reject(InputRejection.INVALID_BATCH)
            time = next
        }
        time = batchTimeMicros
        for (point in predicted) {
            if (point.timeMicros <= time) return reject(InputRejection.INVALID_BATCH)
            time = point.timeMicros
        }
        if (phase == PointerPhase.DOWN && real.size == 0) return reject(InputRejection.INVALID_BATCH)
        if (phase == PointerPhase.MOVE && real.size == 0 && predicted.isEmpty()) return reject(InputRejection.INVALID_BATCH)
        if (phase != PointerPhase.CANCEL && real.size > maxRealSamples - (previous?.samples?.size ?: 0))
            return reject(InputRejection.CAPACITY)
        sequence = batchSequence; eventTime = batchTimeMicros
        if (phase == PointerPhase.CANCEL) { previous?.samples?.discard(); active = null; return InputResult.Cancelled }
        val stroke = previous ?: Active(pointerId, targetAtDown, StrokeSamples.Builder(maxRealSamples))
        stroke.samples.append(real)
        active = stroke
        if (phase != PointerPhase.UP) return InputResult.Preview(stroke.samples.size, predicted.toList())
        val completed = CompletedInput(stroke.target, stroke.samples.finish())
        active = null
        return InputResult.Completed(completed)
    }

    /** Discards only an unfinished stroke. Completed outputs belong to the caller and survive closure. */
    fun close() { active?.samples?.discard(); active = null; closed = true }

    companion object {
        /** Arena plus immutable completion payload and declared overhead, not measured heap. */
        fun reservationBytes(maxRealSamples: Int): Long = StrokeSamples.Builder.reservationBytes(maxRealSamples)
    }
}
