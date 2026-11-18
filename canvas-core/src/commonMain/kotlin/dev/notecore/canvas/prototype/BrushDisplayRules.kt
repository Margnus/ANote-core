package dev.notecore.canvas.prototype

/**
 * DEV-042 E3c display semantics at algorithm version 1. Canonical stroke content (samples, width,
 * argb) never changes; only the presented color differs by brush kind. HIGHLIGHTER renders the
 * same color at half alpha on both platforms so saved documents stay visually consistent.
 */
object BrushDisplayRules {
    const val HIGHLIGHTER_ALPHA = 0.5f

    fun displayArgb(brush: BrushSpec): Int =
        if (brush.kind == BrushKind.HIGHLIGHTER) scaleAlpha(brush.argb, HIGHLIGHTER_ALPHA) else brush.argb

    private fun scaleAlpha(argb: Int, factor: Float): Int {
        val alpha = (argb ushr 24) and 0xff
        val scaled = (alpha * factor).toInt().coerceIn(0, 255)
        return (argb and 0x00ffffff) or (scaled shl 24)
    }
}
