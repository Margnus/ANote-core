package dev.notecore.document

import dev.notecore.canvas.prototype.BrushKind
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
        for (stroke in root.scene.objects()) {
            val m = stroke.transform
            if (stroke.brush.kind != BrushKind.MONOLINE || stroke.brush.algorithmVersion != 1 ||
                listOf(m.a, m.b, m.c, m.d, m.tx, m.ty).any { !it.isFinite() || abs(it) > 1e12 })
                return PreviewDocumentRead(null, PreviewReadFailure.UNSUPPORTED_PREVIEW_CONTENT)
        }
        return PreviewDocumentRead(root, null)
    }
}
