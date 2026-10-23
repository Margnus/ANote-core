package dev.notecore.canvas.prototype

import kotlin.math.max
import kotlin.math.min

data class SceneId(val value: String) { init { require(value.isNotBlank() && value.length <= 128) } }
data class LayerId(val value: String) { init { require(value.isNotBlank() && value.length <= 128) } }
data class ObjectId(val value: String) { init { require(value.isNotBlank() && value.length <= 128) } }
data class CommandId(val value: String) { init { require(value.isNotBlank() && value.length <= 128) } }

enum class BrushKind { MONOLINE, PRESSURE, HIGHLIGHTER }
data class BrushSpec(val kind: BrushKind, val algorithmVersion: Int, val width: Double, val argb: Int) {
    init { require(algorithmVersion > 0 && width.isFinite() && width > 0.0 && width <= 128.0) }
}

/** Owns copies of real sample arrays; prediction and platform events have no storage slot. */
class StrokeSamples private constructor(
    private val xs: DoubleArray, private val ys: DoubleArray,
    private val pressures: FloatArray, private val timesMicros: LongArray,
) {
    val size: Int get() = xs.size
    /** Declared primitive payload plus sample header; not a measured heap allocation. */
    val logicalByteCharge: Long get() = 128L + size * 28L
    fun x(index: Int): Double = xs[index]
    fun y(index: Int): Double = ys[index]
    fun pressure(index: Int): Float = pressures[index]
    fun timeMicros(index: Int): Long = timesMicros[index]

    fun bounds(radius: Double): BoundsD {
        var left = xs[0]; var right = left; var top = ys[0]; var bottom = top
        for (i in 1 until size) {
            left = min(left, xs[i]); right = max(right, xs[i])
            top = min(top, ys[i]); bottom = max(bottom, ys[i])
        }
        return BoundsD(left - radius, top - radius, right + radius, bottom + radius)
    }

    override fun equals(other: Any?): Boolean = other is StrokeSamples &&
        xs.contentEquals(other.xs) && ys.contentEquals(other.ys) &&
        pressures.contentEquals(other.pressures) && timesMicros.contentEquals(other.timesMicros)

    override fun hashCode(): Int {
        var result = xs.contentHashCode()
        result = 31 * result + ys.contentHashCode()
        result = 31 * result + pressures.contentHashCode()
        return 31 * result + timesMicros.contentHashCode()
    }

    /** Sole mutable owner until finish; no primitive array escapes this builder. */
    internal class Builder(private val capacity: Int) {
        private class Chunk(val capacity: Int) {
            val xs = DoubleArray(capacity); val ys = DoubleArray(capacity)
            val pressures = FloatArray(capacity); val times = LongArray(capacity)
        }
        private val chunks = mutableListOf<Chunk>()
        private var open = true
        private var allocated = 0
        var size: Int = 0; private set
        val allocatedPointCapacity: Int get() = allocated
        val logicalByteCharge: Long get() = if (open) 256L + chunks.size * 128L + allocated * 28L else 0L
        val lastTimeMicros: Long get() = if (size == 0) -1L else chunks[(size - 1) / CHUNK_POINTS].times[(size - 1) % CHUNK_POINTS]
        init { require(capacity in 1..1_000_000) }

        fun append(points: List<InputSample>) = append(InputListSamples(points))
        fun append(points: InputRealSamples) {
            check(open)
            require(points.size >= 0 && points.size <= capacity - size)
            var time = lastTimeMicros
            for (i in 0 until points.size) {
                require(points.x(i).isFinite() && points.y(i).isFinite() && kotlin.math.abs(points.x(i)) <= 1e9 && kotlin.math.abs(points.y(i)) <= 1e9)
                require(points.pressure(i).isFinite() && points.pressure(i) in 0f..1f && points.time(i) >= time && points.time(i) >= 0)
                time = points.time(i)
            }
            val needed = size + points.size
            while (allocated < needed) {
                val count = minOf(CHUNK_POINTS, capacity - allocated)
                chunks.add(Chunk(count)); allocated += count
            }
            for (i in 0 until points.size) {
                val chunk = chunks[size / CHUNK_POINTS]; val offset = size % CHUNK_POINTS
                chunk.xs[offset] = points.x(i); chunk.ys[offset] = points.y(i)
                chunk.pressures[offset] = points.pressure(i); chunk.times[offset] = points.time(i)
                size++
            }
        }

        fun finish(): StrokeSamples {
            check(open && size > 0)
            val xs = DoubleArray(size); val ys = DoubleArray(size)
            val pressures = FloatArray(size); val times = LongArray(size)
            var copied = 0
            for (chunk in chunks) {
                val count = minOf(chunk.capacity, size - copied)
                chunk.xs.copyInto(xs, copied, 0, count); chunk.ys.copyInto(ys, copied, 0, count)
                chunk.pressures.copyInto(pressures, copied, 0, count); chunk.times.copyInto(times, copied, 0, count)
                copied += count
            }
            check(copied == size)
            // Privately created arrays transfer here without copyOf's second full copy.
            return StrokeSamples(xs, ys, pressures, times).also { discard() }
        }

        fun discard() { chunks.clear(); size = 0; allocated = 0; open = false }

        companion object {
            const val CHUNK_POINTS = 256
            fun reservationBytes(maxSamples: Int): Long {
                require(maxSamples in 1..1_000_000)
                val chunks = (maxSamples.toLong() + CHUNK_POINTS - 1) / CHUNK_POINTS
                return 512L + chunks * 128L + maxSamples * 56L
            }
        }
    }

    companion object {
        fun copyOf(xs: DoubleArray, ys: DoubleArray, pressures: FloatArray, timesMicros: LongArray): StrokeSamples {
            require(xs.isNotEmpty() && xs.size <= 1_000_000)
            require(xs.size == ys.size && xs.size == pressures.size && xs.size == timesMicros.size)
            // This is the prototype admission limit, not the final format's coordinate range.
            for (i in xs.indices) {
                require(xs[i].isFinite() && ys[i].isFinite())
                require(kotlin.math.abs(xs[i]) <= 1e9 && kotlin.math.abs(ys[i]) <= 1e9)
                require(pressures[i].isFinite() && pressures[i] in 0f..1f)
                require(timesMicros[i] >= 0 && (i == 0 || timesMicros[i] >= timesMicros[i - 1]))
            }
            return StrokeSamples(xs.copyOf(), ys.copyOf(), pressures.copyOf(), timesMicros.copyOf())
        }
    }
}

/** Capture at stroke admission, never look up the current layer/tool during IO completion. */
data class StrokeTarget(val sceneId: SceneId, val layerId: LayerId, val brush: BrushSpec)

data class StrokeObject(
    val id: ObjectId, val objectRevision: Long, val layerId: LayerId,
    val brush: BrushSpec, val samples: StrokeSamples, val transform: Affine2D = Affine2D(),
) {
    init { require(objectRevision > 0) }
    fun bounds(): BoundsD = transform.mapBounds(samples.bounds(brush.width / 2.0))
}
