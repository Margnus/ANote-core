package dev.notecore.canvas.prototype

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import org.junit.Test
import kotlin.test.*

class SweptDiscBitmapReleaseTest {
    @Test fun platformBitmapKeepsChargeUntilRecycledAfterOwnerClose() {
        val f = SweptDiscExecutorFixture
        val e = BrushRenderExecutorPoc(f.frame, f.layer, 8_192, 262_144)
        try {
            // The holder reserves both bitmap storage and the pixel staging buffer.
            val allowance = 2 * f.retainedBytes
            val job = f.submit(e, f.catalog(), allowance = allowance)
            f.awaitReady(e, 1)
            assertEquals(BrushRenderAckPoc.PUBLISHED, e.drain().single().second)
            val image = assertIs<AndroidBrushImageBuildPoc.Ready>(AndroidBrushImagePoc.create(e, job)).image
            val target = Bitmap.createBitmap(f.grid.width, f.grid.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(target)
            val rect = RectF(0f, 0f, target.width.toFloat(), target.height.toFloat())
            try {
                assertEquals(f.retainedBytes, image.allocationBytes.toLong())
                assertTrue(image.draw(canvas, rect, false))
                assertEquals(127, Color.alpha(target.getPixel(16, 8)))
                e.close()
                assertTrue(e.awaitWorkerStop(10_000))
                assertEquals(f.retainedBytes + allowance, e.retiredBytes)
                assertFalse(e.isDrained)
                assertFalse(image.draw(canvas, rect, false))
                assertTrue(image.close())
                assertTrue(image.released)
                assertEquals(0, e.chargedBytes)
                assertTrue(e.isDrained)
                println("Swept bitmap: allocation=${image.allocationBytes}, retired=${f.retainedBytes + allowance}, drained")
            } finally {
                image.close()
                target.recycle()
            }
        } finally {
            e.close()
            e.awaitWorkerStop(10_000)
            e.drain()
        }
    }
}
