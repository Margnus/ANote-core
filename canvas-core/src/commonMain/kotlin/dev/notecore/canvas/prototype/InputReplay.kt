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

    fun consume(batch: InputBatch, targetAtDown: StrokeTarget): InputResult {
        fun reject(reason: InputRejection) = InputResult.Rejected(reason)
        if (closed) return reject(InputRejection.CLOSED)
        if (batch.sequence <= sequence || batch.timeMicros < eventTime) return reject(InputRejection.EVENT_ORDER)
        if (batch.tool == PointerTool.FINGER) {
            sequence = batch.sequence; eventTime = batch.timeMicros
            return InputResult.IgnoredNavigation
        }
        val previous = active
        if (batch.phase == PointerPhase.DOWN && previous != null) return reject(InputRejection.POINTER_BUSY)
        if (batch.phase != PointerPhase.DOWN && (previous == null || previous.pointerId != batch.pointerId))
            return reject(InputRejection.NO_ACTIVE_POINTER)
        val last = previous?.samples?.lastTimeMicros ?: -1L
        var time = last
        for (point in batch.real) {
            if (point.timeMicros < time || point.timeMicros > batch.timeMicros) return reject(InputRejection.INVALID_BATCH)
            time = point.timeMicros
        }
        time = batch.timeMicros
        for (point in batch.predicted) {
            if (point.timeMicros <= time) return reject(InputRejection.INVALID_BATCH)
            time = point.timeMicros
        }
        if (batch.phase == PointerPhase.DOWN && batch.real.isEmpty()) return reject(InputRejection.INVALID_BATCH)
        if (batch.phase == PointerPhase.MOVE && batch.real.isEmpty() && batch.predicted.isEmpty())
            return reject(InputRejection.INVALID_BATCH)
        if (batch.phase != PointerPhase.CANCEL && batch.real.size > maxRealSamples - (previous?.samples?.size ?: 0))
            return reject(InputRejection.CAPACITY)
        sequence = batch.sequence; eventTime = batch.timeMicros
        if (batch.phase == PointerPhase.CANCEL) { previous?.samples?.discard(); active = null; return InputResult.Cancelled }
        val stroke = previous ?: Active(batch.pointerId, targetAtDown, StrokeSamples.Builder(maxRealSamples))
        stroke.samples.append(batch.real)
        active = stroke
        if (batch.phase != PointerPhase.UP) return InputResult.Preview(stroke.samples.size, batch.predicted.toList())
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
