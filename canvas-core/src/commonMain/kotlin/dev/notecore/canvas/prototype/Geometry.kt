package dev.notecore.canvas.prototype

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** C1 reference geometry, not a frozen public API or a rendering implementation. */
data class PointD(val x: Double, val y: Double) {
    init { require(x.isFinite() && y.isFinite()) { "Non-finite point" } }
}

data class BoundsD(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() })
        require(left <= right && top <= bottom)
    }
    fun union(other: BoundsD) = BoundsD(
        min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom)
    )
}

/** x'=a*x+c*y+tx, y'=b*x+d*y+ty; then() applies this transform first. */
data class Affine2D(
    val a: Double = 1.0, val b: Double = 0.0,
    val c: Double = 0.0, val d: Double = 1.0,
    val tx: Double = 0.0, val ty: Double = 0.0,
) {
    init { require(listOf(a, b, c, d, tx, ty).all { it.isFinite() }) }

    fun map(point: PointD) = PointD(a * point.x + c * point.y + tx, b * point.x + d * point.y + ty)

    fun then(next: Affine2D) = Affine2D(
        next.a * a + next.c * b, next.b * a + next.d * b,
        next.a * c + next.c * d, next.b * c + next.d * d,
        next.a * tx + next.c * ty + next.tx, next.b * tx + next.d * ty + next.ty,
    )

    fun inverseOrNull(): Affine2D? = withInverse { ia, ib, ic, id, itx, ity -> Affine2D(ia, ib, ic, id, itx, ity) }

    /** Same supported inverse as inverseOrNull, without allocating a derived matrix. Unfrozen. */
    fun hasInverse(): Boolean = withInverse { _, _, _, _, _, _ -> true } == true

    private inline fun <T> withInverse(result: (Double, Double, Double, Double, Double, Double) -> T): T? {
        val scale = max(max(abs(a), abs(b)), max(abs(c), abs(d)))
        if (scale == 0.0) return null
        val determinant = (a / scale) * (d / scale) - (b / scale) * (c / scale)
        if (abs(determinant) <= 1e-12) return null
        val ia = (d / scale) / determinant / scale
        val ib = -(b / scale) / determinant / scale
        val ic = -(c / scale) / determinant / scale
        val id = (a / scale) / determinant / scale
        val itx = -(ia * tx + ic * ty)
        val ity = -(ib * tx + id * ty)
        if (!ia.isFinite() || !ib.isFinite() || !ic.isFinite() || !id.isFinite() || !itx.isFinite() || !ity.isFinite()) return null
        return result(ia, ib, ic, id, itx, ity)
    }

    fun mapBounds(bounds: BoundsD): BoundsD {
        val points = listOf(
            map(PointD(bounds.left, bounds.top)), map(PointD(bounds.right, bounds.top)),
            map(PointD(bounds.right, bounds.bottom)), map(PointD(bounds.left, bounds.bottom)),
        )
        return BoundsD(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
    }
}
