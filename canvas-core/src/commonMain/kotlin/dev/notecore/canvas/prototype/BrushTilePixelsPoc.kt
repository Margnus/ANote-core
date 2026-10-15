package dev.notecore.canvas.prototype

/** A true neighboring sample border, not repeated edge pixels. Null means explicit grid admission
 * failure: no silent shrink at the 128px/coordinate boundary. Render the expanded grid normally.
 */
internal fun BrushTileGridPoc.withGutter(pixels: Int): BrushTileGridPoc? {
    require(pixels in 0..4)
    if (width + 2 * pixels > 128 || height + 2 * pixels > 128) return null
    val expandedOrigin = PointD(origin.x - pixels * pixelSize, origin.y - pixels * pixelSize)
    if (kotlin.math.abs(expandedOrigin.x) > 1e9 || kotlin.math.abs(expandedOrigin.y) > 1e9) return null
    return copy(origin = expandedOrigin, width = width + 2 * pixels, height = height + 2 * pixels)
}

internal sealed interface BrushTilePixelBuild {
    data class Ready(val pixels: BrushTilePixelsPoc) : BrushTilePixelBuild
    data class Rejected(val reason: String) : BrushTilePixelBuild
    data object Cancelled : BrushTilePixelBuild
}

/** Owned immutable RGBA8 premultiplied byte payload. Source opacity is already resolved in the
 * mask and is applied exactly once. RGB input deliberately has no second alpha channel.
 * Pixel conversion is separately admitted; native copies/row padding require additional budget.
 */
internal class BrushTilePixelsPoc private constructor(
    val frame: FrameToken, val ownerLayer: LayerId, val grid: BrushTileGridPoc,
    val rgb: Int, private val rgba: ByteArray,
) {
    val logicalBytes: Long get() = rgba.size.toLong()
    fun byteAt(offset: Int): Int = rgba[offset].toInt() and 255

    companion object {
        fun build(reader: BrushTileReaderPoc, rgb: Int, maxBytes: Long = 64 * 1024,
            cancelled: () -> Boolean = { false },
        ): BrushTilePixelBuild = convert(reader.frame, reader.ownerLayer, reader.grid, rgb, maxBytes, cancelled, reader::alphaByte)

        fun build(mask: BrushTileMaskPoc, rgb: Int, maxBytes: Long = 64 * 1024,
            cancelled: () -> Boolean = { false },
        ): BrushTilePixelBuild = convert(mask.frame, mask.ownerLayer, mask.grid, rgb, maxBytes, cancelled, mask::alphaByte)

        private fun convert(frame: FrameToken, layer: LayerId, grid: BrushTileGridPoc, rgb: Int,
            maxBytes: Long, cancelled: () -> Boolean, alphaAt: (Int, Int) -> Int,
        ): BrushTilePixelBuild {
            require(maxBytes > 0)
            if (rgb !in 0..0xffffff) return BrushTilePixelBuild.Rejected("rgb-range")
            if (cancelled()) return BrushTilePixelBuild.Cancelled
            val bytes = grid.width * grid.height * 4
            if (bytes > maxBytes) return BrushTilePixelBuild.Rejected("pixel-budget")
            val rgba = ByteArray(bytes)
            for (y in 0 until grid.height) for (x in 0 until grid.width) {
                val pixel = y * grid.width + x
                if (pixel % 256 == 0 && cancelled()) return BrushTilePixelBuild.Cancelled
                val alpha = alphaAt(x, y)
                val offset = pixel * 4
                rgba[offset] = (((rgb ushr 16 and 255) * alpha + 127) / 255).toByte()
                rgba[offset + 1] = (((rgb ushr 8 and 255) * alpha + 127) / 255).toByte()
                rgba[offset + 2] = (((rgb and 255) * alpha + 127) / 255).toByte()
                rgba[offset + 3] = alpha.toByte()
            }
            if (cancelled()) return BrushTilePixelBuild.Cancelled
            return BrushTilePixelBuild.Ready(BrushTilePixelsPoc(frame, layer, grid, rgb, rgba))
        }
    }
}
