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
