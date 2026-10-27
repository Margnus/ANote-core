package dev.notecore.document

import dev.notecore.canvas.prototype.BrushKind
import dev.notecore.canvas.prototype.Affine2D
import kotlin.math.abs

/** Unfrozen preview capability boundary, not the canonical format's complete support matrix. */
enum class PreviewReadFailure { CHARACTER_CAPACITY, INVALID_OR_UNSUPPORTED_FORMAT, UNSUPPORTED_PREVIEW_CONTENT }

class PreviewDocumentRead internal constructor(val root: DocumentRoot?, val failure: PreviewReadFailure?)

/** Pure synchronous validation over the original codec/root. No IO, mutable owner or callback.
 * Invalid interchange is a typed refusal; platform IO/owner/programmer failures are not caught here.
 */
object PreviewDocumentReader {
    fun read(text: String): PreviewDocumentRead {
        if (text.length > PagedPreviewCodec.MAX_CHARACTERS)
            return PreviewDocumentRead(null, PreviewReadFailure.CHARACTER_CAPACITY)
        val root = try { PagedPreviewCodec.decode(text) }
            catch (_: IllegalArgumentException) { return PreviewDocumentRead(null, PreviewReadFailure.INVALID_OR_UNSUPPORTED_FORMAT) }
        return accept(root)
    }
    /** Also gates already-retained immutable roots at the original host's adoption boundary. */
    fun accept(root: DocumentRoot): PreviewDocumentRead {
        try { PagedPreviewCodec.retainedByteCharge(root) }
        catch (_: IllegalArgumentException) { return PreviewDocumentRead(null, PreviewReadFailure.INVALID_OR_UNSUPPORTED_FORMAT) }
        var supported = true
        root.scene.forEachObject { stroke ->
            val m = stroke.transform
            if (stroke.brush.kind != BrushKind.MONOLINE || stroke.brush.algorithmVersion != 1 ||
                listOf(m.a, m.b, m.c, m.d, m.tx, m.ty).any { !it.isFinite() || abs(it) > 1e12 })
                { supported = false; return@forEachObject }
            // Common preview capability includes the original Android Float Path/Matrix consumer.
            // Preserve canonical doubles; refuse zero-radius/hairline or singular derived geometry.
            if (stroke.brush.width.toFloat() / 2f <= 0f ||
                Affine2D(m.a.toFloat().toDouble(), m.b.toFloat().toDouble(),
                    m.c.toFloat().toDouble(), m.d.toFloat().toDouble(),
                    m.tx.toFloat().toDouble(), m.ty.toFloat().toDouble()).inverseOrNull() == null)
                supported = false
        }
        return if (supported) PreviewDocumentRead(root, null)
            else PreviewDocumentRead(null, PreviewReadFailure.UNSUPPORTED_PREVIEW_CONTENT)
    }
}
