package dev.notecore.canvas.preview

import dev.notecore.canvas.prototype.PointD
import kotlin.math.min

/** UI-owner presentation state. Never mutates document samples, revision or saved content. */
class PreviewViewport {
    var pageWidth: Double = 1000.0; private set
    var pageHeight: Double = 1400.0; private set
    var width: Double = 0.0; private set
    var height: Double = 0.0; private set
    var zoom: Double = 1.0; private set
    var offsetX: Double = 0.0; private set
    var offsetY: Double = 0.0; private set
    private var suspendedCenter: PointD? = null
    val scale: Double get() = if (width > 0 && height > 0) min(width / pageWidth, height / pageHeight) * zoom else 0.0

    fun configurePage(width: Double, height: Double) {
        require(width.isFinite() && height.isFinite() && width in 1.0..14400.0 && height in 1.0..14400.0)
        if (width == pageWidth && height == pageHeight) return
        pageWidth = width; pageHeight = height; reset()
    }

    fun resize(width: Double, height: Double) {
        require(width.isFinite() && height.isFinite() && width in 0.0..1e7 && height in 0.0..1e7)
        if (width == this.width && height == this.height) return
        val center = if (scale > 0) toScene(this.width / 2, this.height / 2)
            else suspendedCenter ?: PointD(pageWidth / 2, pageHeight / 2)
        this.width = width; this.height = height
        if (scale <= 0) {
            suspendedCenter = center
            offsetX = 0.0; offsetY = 0.0
            return
        }
        suspendedCenter = null
        offsetX = width / 2 - center.x * scale; offsetY = height / 2 - center.y * scale
        constrain()
    }
    fun reset() {
        suspendedCenter = null
        zoom = 1.0; offsetX = (width - pageWidth * scale) / 2; offsetY = (height - pageHeight * scale) / 2
    }
    fun toScene(x: Double, y: Double): PointD {
        require(scale > 0 && x.isFinite() && y.isFinite())
        return PointD((x - offsetX) / scale, (y - offsetY) / scale)
    }
    fun toView(x: Double, y: Double) = PointD(x * scale + offsetX, y * scale + offsetY)
    /** Map the old finger centroid to the new one, anchored in scene coordinates. */
    fun gesture(oldX: Double, oldY: Double, newX: Double, newY: Double, ratio: Double) {
        if (scale <= 0 || !listOf(oldX, oldY, newX, newY, ratio).all { it.isFinite() } || ratio <= 0 ||
            listOf(oldX, oldY, newX, newY).any { it !in -1e9..1e9 }) return
        val anchor = toScene(oldX, oldY)
        zoom = (zoom * ratio).coerceIn(1.0, 6.0)
        offsetX = newX - anchor.x * scale; offsetY = newY - anchor.y * scale
        constrain()
    }
    private fun constrain() {
        fun axis(offset: Double, view: Double, page: Double) =
            if (page <= view) (view - page) / 2 else offset.coerceIn(view - page, 0.0)
        offsetX = axis(offsetX, width, pageWidth * scale); offsetY = axis(offsetY, height, pageHeight * scale)
    }
}
