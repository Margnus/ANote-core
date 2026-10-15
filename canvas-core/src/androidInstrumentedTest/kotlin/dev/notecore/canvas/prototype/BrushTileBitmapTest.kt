package dev.notecore.canvas.prototype

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import org.junit.Test
import kotlin.test.*

/** Actual ART generation -> Android premultiplied Bitmap -> Canvas, without app UI. */
class BrushTileBitmapTest {
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
}
