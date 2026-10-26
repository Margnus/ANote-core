package dev.notecore.document

import dev.notecore.canvas.prototype.*

/** Internal preview checkpoint envelope in the original atomic file. Never a second authority or journal.
 * Older readers refuse its magic; committed v1/v2 files remain readable without rewriting them.
 */
/** Full immutable checkpoint or refusal; never a base-only fallback or mutable session. */
class PreviewRecoveryRead internal constructor(val snapshot: PagedPreviewSnapshot?, val failure: PreviewReadFailure?)

object PreviewRecoveryCodec {
    private class ContentRefusal(val reason: PreviewReadFailure) : IllegalArgumentException("Preview refused: $reason")
    private fun previewRoot(text: String): DocumentRoot {
        val result = PreviewDocumentReader.read(text)
        return result.root ?: throw ContentRefusal(requireNotNull(result.failure))
    }
    /** Pure bounded content boundary. Decode/validation only; no IO, owner or callbacks to catch. */
    fun read(text: String): PreviewRecoveryRead {
        if (text.length > PagedPreviewCodec.MAX_CHARACTERS) return PreviewRecoveryRead(null, PreviewReadFailure.CHARACTER_CAPACITY)
        return try { PreviewRecoveryRead(decode(text), null) }
        catch (refusal: ContentRefusal) { PreviewRecoveryRead(null, refusal.reason) }
        catch (_: IllegalArgumentException) { PreviewRecoveryRead(null, PreviewReadFailure.INVALID_OR_UNSUPPORTED_FORMAT) }
    }

    private const val MAGIC = "NCRECOVERY\t1\n"
    private const val END = "RECOVERY-END\n"
    private fun payload(root: DocumentRoot, pending: DocumentCommand.Canvas): DocumentRoot {
        val stroke = pending.command as CanvasCommand.AddStroke
        return DocumentRoot.compose("", SceneSnapshot.restored(root.id, 0, listOf(
            StrokeObject(stroke.objectId, 1, stroke.target.layerId, stroke.target.brush, stroke.samples))),
            listOf(root.findPage(pending.pageId)!!.copy(contentRevision = 0)))
    }
    internal fun validate(root: DocumentRoot, pending: DocumentCommand.Canvas) {
        val stroke = pending.command as? CanvasCommand.AddStroke ?: throw IllegalArgumentException("Not a stroke completion")
        require(pending.documentId == root.id && pending.baseRevision == root.revision)
        require(root.findPage(pending.pageId)?.layerId == stroke.target.layerId)
        require(root.scene.objects().none { it.id == stroke.objectId })
        require(stroke.samples.size in 1..PagedPreviewCodec.MAX_STROKE_POINTS)
        require(stroke.target.brush.kind == BrushKind.MONOLINE && stroke.target.brush.algorithmVersion == 1)
        require(pending.id.value.hasValidUnicode() && pending.pageId.value.hasValidUnicode())
        require(PreviewDocumentReader.accept(payload(root, pending)).failure == null)
    }
    internal fun pendingCharge(pending: DocumentCommand.Canvas): Long {
        val stroke = pending.command as CanvasCommand.AddStroke
        return pendingCharge(pending.id.value, pending.pageId.value, stroke.objectId.value,
            stroke.target.layerId.value, stroke.samples.size)
    }
    /** Same retained-command formula before DOWN, without placeholder samples or a second command. */
    internal fun pendingCharge(operation: String, page: String, objectId: String, layer: String, points: Int): Long =
        2048L + (operation.length.toLong() + page.length + objectId.length + layer.length) * 2L + points.toLong() * 28L
    internal fun pendingWorkCharge(pending: DocumentCommand.Canvas): Long = pendingCharge(pending) +
        (4096L + (pending.command as CanvasCommand.AddStroke).samples.size.toLong() * 144L) * 8L

    @Throws(IllegalArgumentException::class)
    fun encode(snapshot: PagedPreviewSnapshot): String {
        val pending = requireNotNull(snapshot.pending)
        validate(snapshot.root, pending)
        val base = PagedPreviewCodec.encode(snapshot.root)
        val ink = PagedPreviewCodec.encode(payload(snapshot.root, pending))
        // Fixed-width hex avoids delimiter/Unicode ambiguity; CommandId is bounded by the canonical type.
        val operation = pending.id.value.map { it.code.toString(16).padStart(4, '0') }.joinToString("")
        val metadata = base.substringAfter('\n').substringBefore('\n')
        val framing = "$MAGIC$metadata\n${base.length}\t${ink.length}\t$operation\n"
        require(base.length.toLong() + ink.length + framing.length + END.length <= PagedPreviewCodec.MAX_CHARACTERS)
        return framing + base + ink + END
    }
    /** Strict framing bounds before allocating/decoding either canonical payload. */
    @Throws(IllegalArgumentException::class)
    fun decode(text: String): PagedPreviewSnapshot {
        require(text.length <= PagedPreviewCodec.MAX_CHARACTERS)
        if (!text.startsWith(MAGIC)) {
            return PagedPreviewSnapshot(previewRoot(text))
        }
        val metadataEnd = text.indexOf('\n', MAGIC.length)
        require(metadataEnd in MAGIC.length..4096)
        val frameEnd = text.indexOf('\n', metadataEnd + 1)
        require(frameEnd > metadataEnd && frameEnd - metadataEnd <= 1024)
        val framing = text.substring(metadataEnd + 1, frameEnd)
        require(framing.count { it == '\t' } == 2)
        val fields = framing.split('\t')
        // Numeric parsers accept signs (and platform-dependent Unicode digits). Framing is ASCII,
        // not signed arithmetic: otherwise -001 silently becomes a different operation character.
        require(fields[0].isNotEmpty() && fields[0].all { it in '0'..'9' })
        require(fields[1].isNotEmpty() && fields[1].all { it in '0'..'9' })
        require(fields[2].all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
        val baseSize = fields[0].toInt(); val inkSize = fields[1].toInt()
        require(baseSize > 0 && inkSize > 0 && fields[2].length in 4..512 && fields[2].length % 4 == 0)
        val operation = fields[2].chunked(4).map { it.toInt(16).toChar() }.joinToString("")
        require(frameEnd.toLong() + 1 + baseSize + inkSize + END.length == text.length.toLong())
        val baseStart = frameEnd + 1; val inkStart = baseStart + baseSize
        require(text.substring(inkStart + inkSize) == END)
        val baseText = text.substring(baseStart, inkStart)
        require(baseText.substringAfter('\n').substringBefore('\n') == text.substring(MAGIC.length, metadataEnd))
        val base = previewRoot(baseText)
        val ink = previewRoot(text.substring(inkStart, inkStart + inkSize))
        require(ink.id == base.id && ink.title == "" && ink.revision == 0L && ink.pageCount == 1 && ink.scene.objectCount == 1)
        val page = ink.page(0); val originalPage = requireNotNull(base.findPage(page.id))
        require(page == originalPage.copy(contentRevision = 0))
        val stroke = ink.scene.objects().single()
        require(stroke.objectRevision == 1L && stroke.transform == Affine2D())
        return PagedPreviewSnapshot(base, DocumentCommand.Canvas(page.id, CanvasCommand.AddStroke(
            CommandId(operation), base.revision, StrokeTarget(base.id, page.layerId, stroke.brush), stroke.id, stroke.samples)))
    }
    /** Metadata is advisory only; full decoding is mandatory before opening. */
    @Throws(IllegalArgumentException::class)
    fun header(text: String): PagedPreviewHeader = PagedPreviewCodec.header(
        if (text.startsWith(MAGIC)) "NCPREVIEW\t2\n" + text.substring(MAGIC.length) else text)
}
