@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.notecore.canvas.prototype

import kotlinx.cinterop.*
import platform.CoreGraphics.*
import kotlin.test.*
import kotlin.time.TimeSource

/** Real CoreGraphics consumption of masks generated in this same Kotlin/Native process. */
class BrushTileCGImageTest {
    private val f = BrushTileImageFixture
    private fun context(width: Int, height: Int): CGContextRef {
        val color = checkNotNull(CGColorSpaceCreateDeviceRGB())
        try {
            return checkNotNull(CGBitmapContextCreate(null, width.toULong(), height.toULong(), 8u,
                (width * 4).toULong(), color, CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder32Big))
        } finally { CGColorSpaceRelease(color) }
    }
    private fun image(pixels: BrushTilePixelsPoc): CGImageRef {
        val g = pixels.grid; val context = context(g.width, g.height)
        try {
            val data = checkNotNull(CGBitmapContextGetData(context)).reinterpret<UByteVar>()
            val stride = CGBitmapContextGetBytesPerRow(context).toInt()
            for (y in 0 until g.height) for (x in 0 until g.width * 4)
                data[y * stride + x] = pixels.byteAt(y * g.width * 4 + x).toUByte()
            return checkNotNull(CGBitmapContextCreateImage(context))
        } finally { CGContextRelease(context) }
    }
    private fun bytes(context: CGContextRef, width: Int, height: Int): ByteArray {
        val data = checkNotNull(CGBitmapContextGetData(context)).reinterpret<UByteVar>()
        val stride = CGBitmapContextGetBytesPerRow(context).toInt()
        return ByteArray(width * height * 4) { i -> data[(i / (width * 4)) * stride + i % (width * 4)].toByte() }
    }
    private fun pixels(reader: BrushTileReaderPoc) = (BrushTilePixelsPoc.build(reader, f.rgb) as BrushTilePixelBuild.Ready).pixels

    @Test fun premultipliedImageCompositesOriginalMarkerColorAndSingleAlpha() {
        val cache = BrushTileCachePoc(32768, 65536); val reader = f.reader(cache, f.catalog())
        val conversionStart = TimeSource.Monotonic.markNow()
        val payload = pixels(reader)
        val conversionNs = conversionStart.elapsedNow().inWholeNanoseconds
        val copyStart = TimeSource.Monotonic.markNow(); val image = image(payload)
        val copyNs = copyStart.elapsedNow().inWholeNanoseconds
        val output = context(64, 32)
        var drawNs = 0L; var releaseNs = 0L
        val imageStride = CGImageGetBytesPerRow(image); val outputStride = CGBitmapContextGetBytesPerRow(output)
        try {
            val drawStart = TimeSource.Monotonic.markNow()
            CGContextSetRGBFillColor(output, 1.0, 1.0, 1.0, 1.0)
            CGContextFillRect(output, CGRectMake(0.0, 0.0, 64.0, 32.0))
            CGContextSetInterpolationQuality(output, kCGInterpolationNone)
            CGContextDrawImage(output, CGRectMake(0.0, 0.0, 64.0, 32.0), image)
            drawNs = drawStart.elapsedNow().inWholeNanoseconds
            val rgba = bytes(output, 64, 32)
            for (i in 0 until 2048) {
                val alpha = payload.byteAt(i * 4 + 3)
                assertEquals(255, rgba[i * 4 + 3].toInt() and 255)
                for (channel in 0..2) {
                    val expected = payload.byteAt(i * 4 + channel) + 255 - alpha
                    val actual = rgba[i * 4 + channel].toInt() and 255
                    assertTrue(kotlin.math.abs(expected - actual) <= 1, "pixel=$i channel=$channel expected=$expected actual=$actual")
                }
            }
        } finally {
            val releaseStart = TimeSource.Monotonic.markNow()
            CGImageRelease(image); CGContextRelease(output)
            releaseNs = releaseStart.elapsedNow().inWholeNanoseconds
            cache.returnReader(reader); cache.close()
        }
        assertTrue(cache.isDrained)
        println("NOTECORE_NATIVE_IMAGE {\"color_pixels\":2048,\"payload_bytes\":${payload.logicalBytes},\"image_row_bytes\":$imageStride,\"output_row_bytes\":$outputStride,\"conversion_elapsed_ns\":$conversionNs,\"copy_create_elapsed_ns\":$copyNs,\"draw_elapsed_ns\":$drawNs,\"release_call_elapsed_ns\":$releaseNs}")
    }

    private fun scaled(catalog: ChunkedBrushPoc, split: Boolean, gutter: Int, scale: Double, filtered: Boolean): ByteArray {
        val cache = BrushTileCachePoc(65536, 131072)
        val output = context(144, 80)
        try {
            CGContextSetShouldAntialias(output, false)
            CGContextSetInterpolationQuality(output, if (filtered) kCGInterpolationLow else kCGInterpolationNone)
            val parts = if (split) 2 else 1
            for (side in 0 until parts) {
                val core = if (split) f.grid.copy(origin = PointD(0.25 + side * 16, 8.0), width = 32) else f.grid
                val reader = f.reader(cache, catalog, checkNotNull(core.withGutter(gutter)))
                try {
                    val image = image(pixels(reader))
                    try {
                        val x = 3.25 + side * core.width * scale
                        val y = 3.25
                        CGContextSaveGState(output)
                        // Assign device pixel centers to one core. Fractional clips can themselves
                        // blend both neighboring images, even when the image filtering is correct.
                        val left = kotlin.math.ceil(x - 0.5); val top = kotlin.math.ceil(y - 0.5)
                        val right = kotlin.math.ceil(x + core.width * scale - 0.5)
                        val bottom = kotlin.math.ceil(y + core.height * scale - 0.5)
                        CGContextClipToRect(output, CGRectMake(left, top, right - left, bottom - top))
                        CGContextDrawImage(output, CGRectMake(x - gutter * scale, y - gutter * scale,
                            (core.width + 2 * gutter) * scale, (core.height + 2 * gutter) * scale), image)
                        CGContextRestoreGState(output)
                    } finally { CGImageRelease(image) }
                } finally { cache.returnReader(reader) }
            }
            return bytes(output, 144, 80)
        } finally { CGContextRelease(output); cache.close(); check(cache.isDrained) }
    }
    @Test fun adjacentNearestScaledImagesMatchWholeImage() {
        val c = f.catalog()
        val whole = scaled(c, false, 0, 2.0, false); val parts = scaled(c, true, 0, 2.0, false)
        val first = whole.indices.firstOrNull { whole[it] != parts[it] }
        assertNull(first, "first mismatch byte=$first")
    }

    @Test fun filteredTilesUseTrueNeighborBorderAndClipToNonoverlappingCore() {
        var controlDifferences = 0; var observedMax = 0
        for (kind in BrushKind.entries) for (scale in listOf(1.5, 2.0)) {
            val c = f.catalog(kind)
            val reference = scaled(c, false, 1, scale, true)
            val tiled = scaled(c, true, 1, scale, true)
            val unpadded = scaled(c, true, 0, scale, true)
            var maxDelta = 0
            // Compare the interior, including seam; outer device clip AA is a separate contract.
            for (y in 5 until (3 + 32 * scale).toInt() - 1) for (x in 5 until (3 + 64 * scale).toInt() - 1) for (channel in 0..3) {
                val offset = (y * 144 + x) * 4 + channel
                val expected = reference[offset].toInt() and 255
                maxDelta = maxOf(maxDelta, kotlin.math.abs(expected - (tiled[offset].toInt() and 255)))
                if (expected != (unpadded[offset].toInt() and 255)) controlDifferences++
            }
            observedMax = maxOf(observedMax, maxDelta)
            assertTrue(maxDelta <= 1, "kind=$kind scale=$scale maxDelta=$maxDelta")
        }
        assertTrue(controlDifferences > 0, "Fixture must expose the missing-neighbor problem")
        println("NOTECORE_NATIVE_IMAGE {\"filter_cases\":6,\"max_channel_delta\":$observedMax,\"unpadded_channel_differences\":$controlDifferences}")
    }
}
