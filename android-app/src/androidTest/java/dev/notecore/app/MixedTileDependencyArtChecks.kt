package dev.notecore.app

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.text.TextRunShaper
import android.os.Build
import dev.notecore.canvas.prototype.*
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Fixed scene and draw configuration. Exercises native dependencies that change without a scene edit. */
internal object MixedTileDependencyArtChecks {
    private const val WIDTH = 96
    private const val HEIGHT = 120
    private const val TILE_BYTES = WIDTH * HEIGHT * 4L
    private const val TEXT = "骨直令"
    private enum class Slot { IMAGE, TEXT }
    private data class ImageStamp(
        val sourceSha: String, val sample: Int, val width: Int, val height: Int, val mirrored: Boolean,
    )
    private data class GlyphStamp(val sourceSha: String, val ttcIndex: Int, val glyph: Int, val x: Float, val y: Float)
    private data class Tile(val dependency: Any, val bitmap: Bitmap)
    private data class PlannedTile(val slot: Slot, val dependency: Any, val draw: (Canvas) -> Unit)
    private data class Job(val frame: FrameToken, val plan: PlannedTile, val reservation: ResourceReservation)

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun decode(bytes: ByteArray, sample: Int): Bitmap =
        checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }))

    private fun imagePlan(sourceSha: String, sample: Int, bitmap: Bitmap?, mirrored: Boolean = false): PlannedTile {
        // Sample 0 denotes a missing resource and its fixed placeholder in this fixture.
        val stamp = ImageStamp(sourceSha, sample, bitmap?.width ?: 0, bitmap?.height ?: 0, mirrored)
        return PlannedTile(Slot.IMAGE, stamp) { canvas ->
            if (bitmap == null) canvas.drawColor(0xff9a9a9a.toInt())
            else {
                // Exercise a same-bounds source-to-display orientation change.
                if (mirrored) canvas.scale(-1f, 1f, WIDTH / 2f, 0f)
                canvas.drawBitmap(bitmap, null, Rect(0, 0, WIDTH, HEIGHT), Paint().apply { isFilterBitmap = false })
            }
        }
    }

    private fun textPlan(primary: FontFamily, fallbackFile: File, face: Int, fallbackSha: String): PlannedTile {
        val fallback = Font.Builder(fallbackFile).setTtcIndex(face).build()
        val family = FontFamily.Builder(fallback).build()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 26f
            typeface = Typeface.CustomFallbackBuilder(primary).addCustomFallback(family).build()
        }
        val glyphs = TextRunShaper.shapeTextRun(TEXT, 0, TEXT.length, 0, TEXT.length, 5f, 48f, false, paint)
        check(glyphs.glyphCount() == TEXT.length)
        val stamp = List(glyphs.glyphCount()) { index ->
            val actual = glyphs.getFont(index)
            check(actual.file == fallbackFile && actual.ttcIndex == face)
            val glyph = glyphs.getGlyphId(index)
            check(glyph > 0)
            GlyphStamp(fallbackSha, actual.ttcIndex, glyph, glyphs.getGlyphX(index), glyphs.getGlyphY(index))
        }
        return PlannedTile(Slot.TEXT, stamp) { canvas ->
            stamp.forEachIndexed { index, glyph ->
                canvas.drawGlyphs(intArrayOf(glyph.glyph), 0, floatArrayOf(glyph.x, glyph.y), 0, 1,
                    glyphs.getFont(index), paint)
            }
        }
    }

    private fun render(plan: PlannedTile): Tile {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            check(bitmap.allocationByteCount.toLong() == TILE_BYTES)
            bitmap.eraseColor(Color.WHITE)
            plan.draw(Canvas(bitmap))
            return Tile(plan.dependency, bitmap)
        } catch (failure: Throwable) {
            bitmap.recycle()
            throw failure
        }
    }

    fun verify(assets: AssetManager): String {
        check(Build.VERSION.SDK_INT >= 31)
        val bytes = assets.open("image-ownership/jpeg-1.jpeg").use { it.readBytes() }
        val sourceSha = sha(bytes)
        val high = decode(bytes, 1)
        val low = decode(bytes, 2)
        try {
            check(high.width == low.width * 2 && high.height == low.height * 2)
            val primaryFile = File("/system/fonts/RobotoStatic-Regular.ttf")
            val fallbackFile = File("/system/fonts/NotoSansCJK-Regular.ttc")
            val fallbackSha = sha(fallbackFile.readBytes())
            val primary = FontFamily.Builder(Font.Builder(primaryFile).build()).build()
            val japanese = textPlan(primary, fallbackFile, 0, fallbackSha)
            val simplified = textPlan(primary, fallbackFile, 2, fallbackSha)
            return verifyTrace(imagePlan(sourceSha, 0, null), imagePlan(sourceSha, 1, high),
                imagePlan(sourceSha, 2, low, mirrored = true), japanese, simplified, sourceSha, fallbackSha)
        } finally {
            high.recycle()
            low.recycle()
        }
    }

    private fun verifyTrace(missing: PlannedTile, high: PlannedTile, low: PlannedTile, japanese: PlannedTile,
        simplified: PlannedTile, imageSha: String, fallbackSha: String,
    ): String {
        val pool = ResourceLeasePool<Slot, Tile>(TILE_BYTES * 2, TILE_BYTES * 4, releaseLane = ResourceLane.OWNER)
        val first = FrameToken(SceneId("mixed-tile-probe"), 1, 1, 1, 1, 1)
        val gate = FramePublicationGate(first)
        val rows = JSONArray()
        var recycled = 0
        var peak = 0L
        fun reserve(): ResourceReservation {
            val reservation = (pool.reserve(TILE_BYTES) as ReservationResult.Reserved).reservation
            peak = maxOf(peak, pool.chargedBytes)
            if (pool.chargedBytes == pool.totalBudget) {
                check(pool.reserve(1) == ReservationResult.Rejected(ReservationFailure.TOTAL_LIMIT))
            }
            return reservation
        }
        fun releaseReady() {
            while (true) {
                val dispatch = pool.nextRelease(ResourceLane.OWNER) as? ResourceReleaseDispatch.Ready ?: return
                val ticket = dispatch.ticket
                check(!ticket.value.bitmap.isRecycled)
                ticket.value.bitmap.recycle()
                check(ticket.value.bitmap.isRecycled)
                check(pool.completeRelease(ticket, ResourceReleaseOutcome.RELEASED) == ResourceCompletion.UPDATED)
                recycled++
            }
        }
        fun step(sequence: Long, image: PlannedTile, text: PlannedTile, expectedHits: Set<Slot>) {
            val frame = first.copy(requestSequence = sequence)
            check(gate.expect(frame))
            val hits = mutableSetOf<Slot>()
            for (plan in listOf(image, text)) {
                val old = pool.borrow(plan.slot) as? ResourceBorrow.Borrowed
                val cold = render(plan)
                try {
                    if (old?.lease?.value?.dependency == plan.dependency) {
                        hits += plan.slot
                        check(old.lease.value.bitmap.sameAs(cold.bitmap))
                    } else {
                        if (old != null) {
                            check(!old.lease.value.bitmap.sameAs(cold.bitmap)) {
                                "Dependency change on ${plan.slot} at frame $sequence needs a pixel-distinct fixture"
                            }
                        }
                        val charge = reserve()
                        val fresh = render(plan)
                        check(fresh.bitmap.sameAs(cold.bitmap) && gate.canPublish(frame))
                        check(pool.publish(charge, plan.slot, fresh) is ResourcePublication.Resident)
                        if (old != null) {
                            check(pool.retiredBytes == TILE_BYTES && !old.lease.value.bitmap.isRecycled)
                            check(pool.nextRelease(ResourceLane.OWNER) == ResourceReleaseDispatch.Idle)
                        }
                    }
                } finally {
                    cold.bitmap.recycle()
                    if (old != null) check(pool.returnLease(old.lease) == LeaseReturn.RETURNED)
                }
                releaseReady()
            }
            check(hits == expectedHits)
            rows.put(JSONObject().put("sequence", sequence).put("hits", JSONArray(hits.map { it.name })))
        }
        try {
            step(1, missing, japanese, emptySet())
            step(2, missing, japanese, Slot.entries.toSet())
            step(3, high, japanese, setOf(Slot.TEXT))

            val worker = BoundedOwnerExecutor(1, TILE_BYTES, "mixed-tile-probe")
            try {
                val job = Job(first.copy(requestSequence = 3), high, reserve())
                check(worker.submit(job, TILE_BYTES, { source ->
                    worker.checkWorkerThread()
                    render(source.plan)
                }) { source, result ->
                    worker.checkOwnerThread()
                    val tile = (result as WorkResult.Succeeded).value
                    check(!gate.canPublish(source.frame))
                    check(pool.publishRetired(source.reservation, tile) is ResourcePublication.Retired)
                    ResultDisposition.ACKNOWLEDGED
                } is WorkAdmission.Accepted)
                worker.stopAccepting()
                check(worker.awaitWorkerStop(5000) && worker.status().chargedBytes == TILE_BYTES)
                step(4, low, japanese, setOf(Slot.TEXT))
                check(worker.drainResults().acknowledged == 1 && worker.status().chargedBytes == 0L)
                check(pool.retiredBytes == TILE_BYTES)
                releaseReady()
            } finally {
                worker.stopAccepting()
                check(worker.awaitWorkerStop(5000))
            }

            step(5, low, simplified, setOf(Slot.IMAGE))
            step(6, low, simplified, Slot.entries.toSet())
        } finally {
            gate.close()
            pool.close()
            releaseReady()
        }
        check(pool.isDrained && peak == TILE_BYTES * 4 && recycled == 6)
        return JSONObject().put("api", Build.VERSION.SDK_INT).put("image_sha256", imageSha)
            .put("fallback_font_sha256", fallbackSha).put("font_faces", JSONArray(listOf(0, 2)))
            .put("frames", rows).put("cold_tile_comparisons", 12)
            .put("same_scene_revision", first.sceneRevision).put("tile_bytes", TILE_BYTES)
            .put("peak_charged_tile_bytes", peak).put("recycled_tiles", recycled)
            .put("late_worker_bitmap_rejected", true).toString()
    }
}
