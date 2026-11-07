package dev.notecore.canvas.prototype

import kotlin.math.abs
import kotlin.math.ceil

/** Test-only presentation transforms in source image pixel units. */
internal object BrushTileTransformFixture {
    data class Case(val name: String, val matrix: Affine2D, val antialias: Boolean) {
        val axisAligned get() = matrix.b == 0.0 && matrix.c == 0.0
        fun deviceClip(left: Int, width: Int, height: Int): BoundsD {
            check(axisAligned)
            fun edge(value: Double) = ceil(value - 0.5)
            return BoundsD(edge(matrix.tx + left * matrix.a), edge(matrix.ty),
                edge(matrix.tx + (left + width) * matrix.a), edge(matrix.ty + height * matrix.d))
        }
    }

    const val width = 112
    const val height = 96
    val cases = listOf(
        Case("uniform_minify", Affine2D(a = 0.75, d = 0.75, tx = 8.25, ty = 9.25), false),
        Case("anisotropic_minify", Affine2D(a = 0.5, d = 1.5, tx = 8.25, ty = 9.25), false),
        Case("rotate_minify_aa_off", Affine2D(a = 0.6, b = 0.45, c = -0.45, d = 0.6, tx = 28.25, ty = 9.25), false),
        Case("rotate_minify_aa_on", Affine2D(a = 0.6, b = 0.45, c = -0.45, d = 0.6, tx = 28.25, ty = 9.25), true),
    )

    private data class Delta(val maximum: Int, val pixels: Int)

    private fun compare(whole: ByteArray, split: ByteArray): Delta {
        check(whole.size == width * height * 4 && whole.size == split.size)
        var maximum = 0
        var pixels = 0
        for (i in whole.indices step 4) {
            var delta = 0
            for (channel in 0..3) {
                val offset = i + channel
                delta = maxOf(delta, abs((whole[offset].toInt() and 255) - (split[offset].toInt() and 255)))
            }
            maximum = maxOf(maximum, delta)
            if (delta != 0) pixels++
        }
        return Delta(maximum, pixels)
    }

    fun observe(case: Case, kind: BrushKind, draw: (split: Boolean, gutter: Int) -> ByteArray): String {
        // Use the same guard width for each whole/split pair so outer clip and
        // image edge treatment have a consistent reference on each backend.
        val whole1 = draw(false, 1)
        val whole2 = draw(false, 2)
        val pixels = (3 until whole1.size step 4).count { whole1[it].toInt() and 255 != 0 }
        check(pixels > 30)
        val unpadded = compare(whole1, draw(true, 0))
        val one = compare(whole1, draw(true, 1))
        val two = compare(whole2, draw(true, 2))
        val outerReference = compare(whole1, whole2)
        return """{"case":"${case.name}","brush":"${kind.name}","visible_pixels":$pixels,"gutter_0_max":${unpadded.maximum},"gutter_0_pixels":${unpadded.pixels},"gutter_1_max":${one.maximum},"gutter_1_pixels":${one.pixels},"gutter_2_max":${two.maximum},"gutter_2_pixels":${two.pixels},"whole_reference_1_vs_2_max":${outerReference.maximum}}"""
    }
}
