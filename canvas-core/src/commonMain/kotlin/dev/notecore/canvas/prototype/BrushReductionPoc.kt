package dev.notecore.canvas.prototype

internal sealed interface BrushReductionBuild {
    data class Ready(val plan: BrushReductionPoc) : BrushReductionBuild
    data class Rejected(val reason: String) : BrushReductionBuild
    data object Cancelled : BrushReductionBuild
}

/** Derived-only exact straight-run reduction. Never mutates or replaces canonical samples.
 * Only a binary lattice with bounded integer products is admitted to collinearity tests;
 * arbitrary/near-collinear coordinates are retained, never guessed using an epsilon.
 */
internal class BrushReductionPoc private constructor(
    private val source: StrokeSamples, private val kind: BrushKind,
    private val indices: IntArray, val size: Int,
) {
    val logicalBytes: Long get() = indices.size * 4L
    fun sourceIndex(index: Int): Int { require(index in 0 until size); return indices[index] }
    fun matches(samples: StrokeSamples, brush: BrushSpec) = source === samples && kind == brush.kind && brush.algorithmVersion == 1

    companion object {
        fun build(samples: StrokeSamples, brush: BrushSpec, maxPlanBytes: Long = 1024 * 1024, cancelled: () -> Boolean = { false }): BrushReductionBuild {
            require(maxPlanBytes > 0)
            if (cancelled()) return BrushReductionBuild.Cancelled
            if (brush.algorithmVersion != 1) return BrushReductionBuild.Rejected("unsupported-brush-version")
            if (samples.size > 200000 || samples.size * 4L > maxPlanBytes) return BrushReductionBuild.Rejected("plan-budget")
            val indices = IntArray(samples.size)
            fun sameDisc(a: Int, b: Int) = samples.x(a) == samples.x(b) && samples.y(a) == samples.y(b) &&
                (brush.kind != BrushKind.PRESSURE || samples.pressure(a) == samples.pressure(b))
            fun coordinate(value: Double) = value >= -1_000_000_000 && value <= 1_000_000_000 && value == value.toLong().toDouble()
            fun redundant(a: Int, b: Int, c: Int): Boolean {
                if (sameDisc(a, b) || sameDisc(b, c)) return true
                val ax = samples.x(a) * 1024; val ay = samples.y(a) * 1024
                val bx = samples.x(b) * 1024; val by = samples.y(b) * 1024
                val cx = samples.x(c) * 1024; val cy = samples.y(c) * 1024
                if (!coordinate(ax) || !coordinate(ay) || !coordinate(bx) || !coordinate(by) || !coordinate(cx) || !coordinate(cy)) return false
                val abx = bx.toLong() - ax.toLong(); val aby = by.toLong() - ay.toLong()
                val bcx = cx.toLong() - bx.toLong(); val bcy = cy.toLong() - by.toLong()
                // Differences <=2e9: each product <=4e18, sum/difference <=8e18 < Long.MAX.
                if (abx * bcy - aby * bcx != 0L || abx * bcx + aby * bcy <= 0L) return false
                if (brush.kind != BrushKind.PRESSURE) return true
                val ap = samples.pressure(a).toDouble() * 65536
                val bp = samples.pressure(b).toDouble() * 65536
                val cp = samples.pressure(c).toDouble() * 65536
                if (ap != ap.toLong().toDouble() || bp != bp.toLong().toDouble() || cp != cp.toLong().toDouble()) return false
                val ab = if (abx != 0L) abx else aby; val bc = if (abx != 0L) bcx else bcy
                // Linear pressure is necessary: keeping a dip/peak avoids changing the envelope.
                return (bp.toLong() - ap.toLong()) * bc == (cp.toLong() - bp.toLong()) * ab
            }
            var size = 0
            for (i in 0 until samples.size) {
                if (i % 256 == 0 && cancelled()) return BrushReductionBuild.Cancelled
                indices[size++] = i
                while (size >= 3 && redundant(indices[size - 3], indices[size - 2], indices[size - 1])) {
                    indices[size - 2] = indices[size - 1]; size--
                }
            }
            if (cancelled()) return BrushReductionBuild.Cancelled
            return BrushReductionBuild.Ready(BrushReductionPoc(samples, brush.kind, indices, size))
        }
    }
}

internal sealed interface ReducedBrushPathBuild {
    data class Ready(val path: BrushPathPoc, val sourceSamples: Int, val derivedSamples: Int, val workingLogicalBytes: Long) : ReducedBrushPathBuild
    data class Rejected(val reason: String) : ReducedBrushPathBuild
    data object Cancelled : ReducedBrushPathBuild
}

/** Optional experimental producer; the DEV-028 baseline and native fixture stay unchanged.
 * Short-lived projected StrokeSamples are geometry input only, never a Scene/store/authority.
 * Working payload accounts for the plan, both projection copies and final output, not heap.
 */
internal object ReducedBrushPathPoc {
    fun build(samples: StrokeSamples, brush: BrushSpec, transform: Affine2D = Affine2D(),
        sceneTolerance: Double = 0.05, maxVertices: Int = 500000, maxWorkingBytes: Long = 16L * 1024 * 1024,
        cancelled: () -> Boolean = { false },
    ): ReducedBrushPathBuild {
        require(maxWorkingBytes > 0)
        val plan = when (val result = BrushReductionPoc.build(samples, brush, minOf(1024 * 1024L, maxWorkingBytes), cancelled)) {
            is BrushReductionBuild.Ready -> result.plan
            is BrushReductionBuild.Rejected -> return ReducedBrushPathBuild.Rejected(result.reason)
            BrushReductionBuild.Cancelled -> return ReducedBrushPathBuild.Cancelled
        }
        check(plan.matches(samples, brush))
        val projectionBytes = if (plan.size == samples.size) 0L else plan.size * 56L
        val available = maxWorkingBytes - plan.logicalBytes - projectionBytes
        if (available <= 0) return ReducedBrushPathBuild.Rejected("working-budget")
        if (cancelled()) return ReducedBrushPathBuild.Cancelled
        val projected = if (plan.size == samples.size) samples else {
            val xs = DoubleArray(plan.size); val ys = DoubleArray(plan.size)
            val pressure = FloatArray(plan.size); val time = LongArray(plan.size)
            for (i in 0 until plan.size) {
                if (i % 256 == 0 && cancelled()) return ReducedBrushPathBuild.Cancelled
                val index = plan.sourceIndex(i)
                xs[i] = samples.x(index); ys[i] = samples.y(index); pressure[i] = samples.pressure(index); time[i] = samples.timeMicros(index)
            }
            StrokeSamples.copyOf(xs, ys, pressure, time)
        }
        return when (val result = BrushPathPoc.build(projected, brush, transform, sceneTolerance, maxVertices, available, cancelled)) {
            is BrushPathBuild.Ready -> ReducedBrushPathBuild.Ready(result.path, samples.size, plan.size,
                plan.logicalBytes + projectionBytes + result.path.logicalBytes)
            is BrushPathBuild.Rejected -> ReducedBrushPathBuild.Rejected(result.reason)
            BrushPathBuild.Cancelled -> ReducedBrushPathBuild.Cancelled
        }
    }
}
