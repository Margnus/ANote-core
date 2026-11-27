package dev.notecore.canvas.prototype

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** API 26 has no per-glyph resolved-font identity. Prove correctness on a tile containing both
 * the image and real text, while a neighboring image-only tile stays reusable.
 */
internal object LegacyTextTileArtChecks {
    private const val WIDTH = 96
    private const val HEIGHT = 64
    private const val BYTES = WIDTH * HEIGHT * 4L
    private const val TEXT = "骨直令"
    private enum class Slot { IMAGE, MIXED }
    private data class Tile(val key: CompositionTileKeyPoc<String>, val bitmap: Bitmap)

    private fun newBitmap() = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).also {
        check(it.allocationByteCount.toLong() == BYTES)
    }

    private fun encodedBackdrop(): ByteArray {
        val bitmap = newBitmap()
        try {
            bitmap.eraseColor(0xfff0e2ca.toInt())
            val paint = Paint().apply { color = 0xff98c6e0.toInt() }
            Canvas(bitmap).drawRect(0f, 0f, 40f, HEIGHT.toFloat(), paint)
            return ByteArrayOutputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                it.toByteArray()
            }
        } finally { bitmap.recycle() }
    }

    private fun render(backdrop: Bitmap, paint: Paint?): Bitmap {
        val result = newBitmap()
        try {
            val canvas = Canvas(result)
            canvas.drawBitmap(backdrop, 0f, 0f, null)
            if (paint != null) canvas.drawText(TEXT, 5f, 43f, paint)
            return result
        } catch (failure: Throwable) {
            result.recycle()
            throw failure
        }
    }

    fun verify(): String {
        check(Build.VERSION.SDK_INT == 26)
        val bytes = encodedBackdrop()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        try {
            check(bitmap.width == WIDTH && bitmap.height == HEIGHT)
            val commonFont = Typeface.create("sans-serif", Typeface.NORMAL)
            fun paint(locale: Locale) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = commonFont
                textLocale = locale
                textSize = 26f
                color = Color.BLACK
            }
            val jp = paint(Locale.JAPAN)
            val cn = paint(Locale.SIMPLIFIED_CHINESE)
            check(jp.typeface === cn.typeface && jp.textSize == cn.textSize)
            check(TEXT.all { jp.hasGlyph(it.toString()) && cn.hasGlyph(it.toString()) })
            return verifyFrames(bitmap, hash, jp, cn)
        } finally { bitmap.recycle() }
    }

    private fun verifyFrames(backdrop: Bitmap, hash: String, jp: Paint, cn: Paint): String {
        val pool = ResourceLeasePool<Slot, Tile>(2 * BYTES, 3 * BYTES, releaseLane = ResourceLane.OWNER)
        val first = FrameToken(SceneId("api26-text-raster"), 1, 1, 1, 1, 1)
        val gate = FramePublicationGate(first)
        val renderer = "android-api26-srgb-argb8888"
        val rasterDependency = "backdrop-id/1/$hash"
        val grids = Slot.entries.associateWith { slot ->
            BrushTileGridPoc(PointD(if (slot == Slot.IMAGE) WIDTH.toDouble() else 0.0, 0.0), WIDTH, HEIGHT, 1.0, 1)
        }
        val rows = JSONArray()
        var highWater = 0L
        var recycled = 0
        var differingTextPixels = false

        fun releaseReady() {
            while (true) {
                val result = pool.nextRelease(ResourceLane.OWNER) as? ResourceReleaseDispatch.Ready ?: return
                val ticket = result.ticket
                check(!ticket.value.bitmap.isRecycled)
                ticket.value.bitmap.recycle()
                check(ticket.value.bitmap.isRecycled)
                check(pool.completeRelease(ticket, ResourceReleaseOutcome.RELEASED) == ResourceCompletion.UPDATED)
                recycled++
            }
        }

        fun frame(sequence: Long, text: Paint, expected: List<Slot>) {
            val frame = first.copy(requestSequence = sequence)
            check(gate.expect(frame))
            val hits = mutableListOf<Slot>()
            for (slot in Slot.entries) {
                val mixed = slot == Slot.MIXED
                val ordered = if (mixed) listOf(rasterDependency, null) else listOf(rasterDependency)
                val key = CompositionTileKeyPoc.capture(frame, grids.getValue(slot), renderer, ordered)
                val old = (pool.borrow(slot) as? ResourceBorrow.Borrowed)?.lease
                val cold = render(backdrop, if (mixed) text else null)
                try {
                    if (old?.value?.key == key) {
                        check(old.value.bitmap.sameAs(cold))
                        hits += slot
                    } else {
                        if (sequence == 2L && mixed) {
                            // The naive same-text/revision cache would select these old pixels.
                            check(!checkNotNull(old).value.bitmap.sameAs(cold)) { "Regional CJK fixture must differ" }
                            differingTextPixels = true
                        }
                        val reservation = (pool.reserve(BYTES) as ReservationResult.Reserved).reservation
                        highWater = maxOf(highWater, pool.chargedBytes)
                        if (pool.chargedBytes == pool.totalBudget) {
                            check(pool.reserve(1) == ReservationResult.Rejected(ReservationFailure.TOTAL_LIMIT))
                        }
                        val fresh = render(backdrop, if (mixed) text else null)
                        check(fresh.sameAs(cold) && gate.canPublish(frame))
                        check(pool.publish(reservation, slot, Tile(key, fresh)) is ResourcePublication.Resident)
                        if (old != null) {
                            check(pool.retiredBytes == BYTES && !old.value.bitmap.isRecycled)
                            check(pool.nextRelease(ResourceLane.OWNER) == ResourceReleaseDispatch.Idle)
                        }
                    }
                } finally {
                    cold.recycle()
                    if (old != null) check(pool.returnLease(old) == LeaseReturn.RETURNED)
                }
                releaseReady()
            }
            check(hits == expected)
            rows.put(JSONObject().put("request", sequence).put("locale", text.textLocale.toLanguageTag())
                .put("hits", JSONArray(hits.map { it.name })))
        }

        try {
            frame(1, jp, emptyList())
            frame(1, jp, Slot.entries)
            frame(2, cn, listOf(Slot.IMAGE))
            check(!gate.canPublish(first))
            frame(2, cn, Slot.entries)
            frame(3, cn, listOf(Slot.IMAGE))
        } finally {
            gate.close()
            pool.close()
            releaseReady()
        }
        check(differingTextPixels && pool.isDrained && recycled == 4 && highWater == 3 * BYTES)
        return JSONObject().put("api", Build.VERSION.SDK_INT).put("same_scene_revision", first.sceneRevision)
            .put("backdrop_sha256", hash).put("frames", rows).put("cold_tile_comparisons", 10)
            .put("same_text_and_primary_raster_difference", true)
            .put("tile_bytes", BYTES).put("peak_charged_tile_bytes", highWater)
            .put("recycled_tiles", recycled).put("stale_frame_rejected", true).toString()
    }
}
