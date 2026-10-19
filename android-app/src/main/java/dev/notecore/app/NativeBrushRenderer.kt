package dev.notecore.app

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import dev.notecore.canvas.prototype.*

/** Standalone candidate-2 backend. The current algorithm-1 App/codec do not call this yet. */
class NativeBrushRenderer private constructor(private val path: Path, private val paint: Paint) {
    /** The plan is already in current Canvas-local target pixels; do not reapply its object/viewport matrix. */
    fun draw(canvas: Canvas) { canvas.drawPath(path, paint) }

    companion object {
        fun prepare(plan: BrushPathPlan): NativeBrushRenderer {
            require(plan.precision == BrushPathPrecision.FLOAT32)
            require(plan.fillRule == BrushFillRule.NON_ZERO && plan.blendMode == BrushBlendMode.SOURCE_OVER)
            val path = Path().apply { fillType = Path.FillType.WINDING }
            for (contour in 0 until plan.contourCount) {
                val start = plan.contourStart(contour); val end = plan.contourEnd(contour)
                path.moveTo(plan.x(start).toFloat(), plan.y(start).toFloat())
                for (vertex in start + 1 until end) path.lineTo(plan.x(vertex).toFloat(), plan.y(vertex).toFloat())
                path.close()
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL; color = plan.argb
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
            }
            return NativeBrushRenderer(path, paint)
        }
    }
}
