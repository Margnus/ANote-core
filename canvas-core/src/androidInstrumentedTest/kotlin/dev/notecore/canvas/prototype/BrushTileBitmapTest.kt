package dev.notecore.canvas.prototype

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import java.nio.ByteBuffer
import org.junit.Test
import kotlin.test.*

/** Actual ART generation -> Android premultiplied Bitmap -> Canvas, without app UI. */
class BrushTileBitmapTest {
    companion object { val observations = mutableListOf<String>() }
    private val frame = FrameToken(SceneId("bitmap-page"), 1, 1, 1, 1, 1)
    private val layer = LayerId("ink")
    private val grid = BrushTileGridPoc(PointD(0.25, 8.0), 64, 32, pixelSize = 0.5, samplesPerAxis = 4)
    private fun catalog(): ChunkedBrushPoc {
        val s = StrokeObject(ObjectId("marker"), 1, layer, BrushSpec(BrushKind.HIGHLIGHTER, 1, 7.0, 0xcc336699.toInt()),
            StrokeSamples.copyOf(doubleArrayOf(4.0, 28.0, 4.0), doubleArrayOf(16.0, 18.0, 16.0),
                floatArrayOf(1f, 1f, 1f), longArrayOf(0, 1, 2)))
        return (ChunkedBrushPoc.build(frame, s, segmentsPerChunk = 1) as ChunkedBrushBuild.Ready).catalog
    }
    private fun bitmap(reader: BrushTileReaderPoc): Bitmap {
        val g = reader.grid
        val pixels = IntArray(g.width * g.height) { i -> Color.argb(reader.alphaByte(i % g.width, i / g.width), 51, 102, 153) }
        return Bitmap.createBitmap(pixels, g.width, g.height, Bitmap.Config.ARGB_8888)
    }

    @Test fun bitmapResolvesMarkerAlphaOnceAndCompositesColorOnWhite() {
        val cache = BrushTileCachePoc(8192, 16384); val c = catalog()
        val reader = (cache.acquire(c, frame, layer, grid) as CachedBrushTileBuild.Ready).reader
        val input = bitmap(reader)
        val output = Bitmap.createBitmap(grid.width, grid.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(output); canvas.drawColor(Color.WHITE); canvas.drawBitmap(input, 0f, 0f, Paint())
            var partial = 0; var covered = 0
            for (y in 0 until grid.height) for (x in 0 until grid.width) {
                val alpha = reader.alphaByte(x, y)
                if (alpha in 1..50) partial++
                if (alpha == 51) covered++ // Original 204 alpha times marker .25, never .25 again.
                assertEquals(alpha, Color.alpha(input.getPixel(x, y)))
                val pixel = output.getPixel(x, y)
                assertEquals(255, Color.alpha(pixel))
                for ((actual, channel) in listOf(Color.red(pixel) to 51, Color.green(pixel) to 102, Color.blue(pixel) to 153)) {
                    val expected = (channel * alpha + 255 * (255 - alpha) + 127) / 255
                    assertTrue(kotlin.math.abs(actual - expected) <= 1, "pixel=$x,$y alpha=$alpha channel=$channel actual=$actual expected=$expected")
                }
            }
            assertTrue(partial > 0 && covered > 0)
        } finally { input.recycle(); output.recycle(); cache.returnReader(reader); cache.close() }
        assertTrue(cache.isDrained)
    }

    @Test fun adjacentNearestScaledImagesMatchOneWholeImageWithoutSeam() {
        val cache = BrushTileCachePoc(16384, 32768); val c = catalog()
        val grids = listOf(grid, grid.copy(width = 32), grid.copy(origin = PointD(16.25, 8.0), width = 32))
        val readers = grids.map { (cache.acquire(c, frame, layer, it) as CachedBrushTileBuild.Ready).reader }
        val images = readers.map(::bitmap)
        val whole = Bitmap.createBitmap(128, 64, Bitmap.Config.ARGB_8888)
        val pieces = Bitmap.createBitmap(128, 64, Bitmap.Config.ARGB_8888)
        try {
            val paint = Paint().also { it.isFilterBitmap = false; it.isAntiAlias = false }
            Canvas(whole).drawBitmap(images[0], null, Rect(0, 0, 128, 64), paint)
            val canvas = Canvas(pieces)
            canvas.drawBitmap(images[1], null, Rect(0, 0, 64, 64), paint)
            canvas.drawBitmap(images[2], null, Rect(64, 0, 128, 64), paint)
            assertTrue(whole.sameAs(pieces)) // All 8,192 pixels, including both sides of the join.
        } finally {
            images.forEach { it.recycle() }; whole.recycle(); pieces.recycle()
            readers.forEach { cache.returnReader(it) }; cache.close()
        }
        assertTrue(cache.isDrained)
    }

    private fun rgbaBitmap(reader: BrushTileReaderPoc): Bitmap = rgbaBitmap(
        (BrushTilePixelsPoc.build(reader, BrushTileImageFixture.rgb) as BrushTilePixelBuild.Ready).pixels)

    private fun rgbaBitmap(pixels: BrushTilePixelsPoc): Bitmap {
        val bitmap = Bitmap.createBitmap(pixels.grid.width, pixels.grid.height, Bitmap.Config.ARGB_8888)
        try {
            val copy = ByteBuffer.allocate(pixels.logicalBytes.toInt())
            for (i in 0 until pixels.logicalBytes.toInt()) copy.put(pixels.byteAt(i).toByte())
            copy.rewind(); bitmap.copyPixelsFromBuffer(copy)
            return bitmap
        } catch (failure: Throwable) { bitmap.recycle(); throw failure }
    }

    @Test fun sharedPremultipliedPayloadCompositesWithoutSecondAlphaOrChannelSwap() {
        val f = BrushTileImageFixture; val cache = BrushTileCachePoc(32768, 65536)
        val reader = f.reader(cache, f.catalog())
        val conversionStart = System.nanoTime()
        val payload = (BrushTilePixelsPoc.build(reader, f.rgb) as BrushTilePixelBuild.Ready).pixels
        val conversionNs = System.nanoTime() - conversionStart
        val copyStart = System.nanoTime(); val image = rgbaBitmap(payload)
        val copyNs = System.nanoTime() - copyStart
        val output = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        var observation: String? = null
        try {
            val drawStart = System.nanoTime()
            val canvas = Canvas(output); canvas.drawColor(Color.WHITE); canvas.drawBitmap(image, 0f, 0f, Paint())
            val drawNs = System.nanoTime() - drawStart
            for (i in 0 until 2048) {
                val alpha = payload.byteAt(i * 4 + 3); val pixel = output.getPixel(i % 64, i / 64)
                assertEquals(alpha, Color.alpha(image.getPixel(i % 64, i / 64)))
                for ((channel, actual) in listOf(0 to Color.red(pixel), 1 to Color.green(pixel), 2 to Color.blue(pixel)))
                    assertTrue(kotlin.math.abs(payload.byteAt(i * 4 + channel) + 255 - alpha - actual) <= 1)
            }
            observation = "{\"color_pixels\":2048,\"payload_bytes\":${payload.logicalBytes},\"bitmap_allocation_bytes\":${image.allocationByteCount},\"bitmap_row_bytes\":${image.rowBytes},\"output_allocation_bytes\":${output.allocationByteCount},\"conversion_elapsed_ns\":$conversionNs,\"copy_create_elapsed_ns\":$copyNs,\"draw_elapsed_ns\":$drawNs"
        } finally {
            val releaseStart = System.nanoTime(); image.recycle(); output.recycle()
            val releaseNs = System.nanoTime() - releaseStart
            cache.returnReader(reader); cache.close()
            observation?.let { observations.add("$it,\"release_call_elapsed_ns\":$releaseNs}") }
        }
        assertTrue(cache.isDrained)
    }

    private fun scaled(catalog: ChunkedBrushPoc, split: Boolean, gutter: Int, scale: Float): ByteArray {
        val f = BrushTileImageFixture; val cache = BrushTileCachePoc(65536, 131072)
        val output = Bitmap.createBitmap(144, 80, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(output); val paint = Paint().also { it.isFilterBitmap = true; it.isAntiAlias = false }
            for (side in 0 until if (split) 2 else 1) {
                val core = if (split) f.grid.copy(origin = PointD(0.25 + side * 16, 8.0), width = 32) else f.grid
                val reader = f.reader(cache, catalog, checkNotNull(core.withGutter(gutter)))
                try {
                    val image = rgbaBitmap(reader)
                    try {
                        val x = 3.25f + side * core.width * scale; val y = 3.25f
                        val save = canvas.save()
                        canvas.clipRect(kotlin.math.ceil(x - 0.5f), kotlin.math.ceil(y - 0.5f),
                            kotlin.math.ceil(x + core.width * scale - 0.5f), kotlin.math.ceil(y + core.height * scale - 0.5f))
                        canvas.drawBitmap(image, null, RectF(x - gutter * scale, y - gutter * scale,
                            x + (core.width + gutter) * scale, y + (core.height + gutter) * scale), paint)
                        canvas.restoreToCount(save)
                    } finally { image.recycle() }
                } finally { cache.returnReader(reader) }
            }
            val raw = ByteBuffer.allocate(output.byteCount); output.copyPixelsToBuffer(raw)
            return raw.array()
        } finally { output.recycle(); cache.close(); check(cache.isDrained) }
    }

    @Test fun filteredTilesUseTrueNeighborBorderAndClipToNonoverlappingCore() {
        var controlDifferences = 0; var observedMax = 0
        for (kind in BrushKind.entries) for (scale in listOf(1.5f, 2f)) {
            val c = BrushTileImageFixture.catalog(kind)
            val reference = scaled(c, false, 1, scale); val tiled = scaled(c, true, 1, scale)
            val unpadded = scaled(c, true, 0, scale)
            var maxDelta = 0
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
        observations.add("{\"filter_cases\":6,\"max_channel_delta\":$observedMax,\"unpadded_channel_differences\":$controlDifferences}")
    }

    @Test fun workerOutputBudgetStaysWithNativeBitmapUntilExplicitReaderReturn() {
        val f = BrushTileImageFixture
        val executor = BrushRenderExecutorPoc(f.frame, f.layer, 32768, 262144)
        try {
            val job = assertIs<BrushRenderAdmissionPoc.Accepted>(executor.submit(f.catalog(), f.frame, f.layer,
                f.grid, f.rgb, 65536, nativeCopyAllowanceBytes = 16384)).job
            val deadline = System.nanoTime() + 5_000_000_000L
            while (executor.executorStatus().entries.single().stage != OwnedWorkStage.READY) {
                check(System.nanoTime() < deadline); Thread.sleep(1)
            }
            assertEquals(BrushRenderAckPoc.PUBLISHED, executor.drain().single().second)
            val reader = assertNotNull(executor.borrowNative(job))
            val bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
            try {
                val copy = ByteBuffer.allocate(8192)
                for (i in 0 until 8192) copy.put(reader.byteAt(i).toByte())
                copy.rewind(); bitmap.copyPixelsFromBuffer(copy)
                assertTrue(copy.capacity() + bitmap.allocationByteCount <= reader.nativeCopyAllowanceBytes)
                assertTrue((0 until 2048).any { Color.alpha(bitmap.getPixel(it % 64, it / 64)) == 51 })
                executor.close(); assertFalse(executor.canPublish(reader)); assertEquals(24576, executor.chargedBytes)
                assertFalse(executor.isDrained)
                observations.add("{\"render_native_lease\":true,\"rgba_bytes\":8192,\"native_copy_allowance_bytes\":16384,\"bitmap_allocation_bytes\":${bitmap.allocationByteCount},\"staging_bytes\":${copy.capacity()},\"charged_while_closed_reader_held\":${executor.chargedBytes}}")
            } finally { bitmap.recycle(); check(executor.completeNativeRelease(reader, ResourceReleaseOutcome.RELEASED)) }
        } finally { executor.close(); assertTrue(executor.awaitWorkerStop(5000)); executor.drain() }
        assertTrue(executor.isDrained); assertEquals(0, executor.chargedBytes)
    }
}
