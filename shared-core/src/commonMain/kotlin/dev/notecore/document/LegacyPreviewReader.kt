package dev.notecore.document

import dev.notecore.canvas.prototype.*

/** Read-only v1 compatibility boundary. Never creates an authority or exposes a v1 writer. */
internal object LegacyPreviewReader {
    private data class Metadata(val header: PagedPreviewHeader, val nextObject: Long)
    fun header(text: String): PagedPreviewHeader {
        require(text.length <= 4096)
        val rows = PagedPreviewCodec.Rows(text)
        require(rows.row(2) == listOf("NCPREVIEW", "1"))
        return metadata(rows.row(6)).header
    }
    fun decodeRows(rows: PreviewRows): DocumentRoot {
        val meta = metadata(rows.row(6)); val h = meta.header
        val objects = ArrayList<StrokeObject>(h.objectCount); val ids = mutableSetOf<ObjectId>()
        val points = PreviewPointReader(rows)
        var total = 0
        repeat(h.objectCount) {
            val row = rows.row(5); require(row[0] == "S")
            val id = ObjectId(row[1]); val number = row[1].removePrefix("ink-").toLong()
            require(row[1] == "ink-$number" && number in 1 until meta.nextObject && ids.add(id))
            val color = row[2].toInt(); val width = row[3].toDouble(); val size = row[4].toInt()
            require(width.isFinite() && width in 0.5..16.0)
            require(size in 1..PagedPreviewCodec.MAX_STROKE_POINTS && size <= PagedPreviewCodec.MAX_POINTS - total)
            total += size // Admit the aggregate before allocating the next real-sample arrays.
            objects.add(StrokeObject(id, 1, LayerId("ink"), BrushSpec(BrushKind.MONOLINE, 1, width, color),
                points.read(size)))
        }
        require(rows.row(1) == listOf("END")); rows.end()
        return DocumentRoot.compose(h.title, SceneSnapshot.restored(SceneId(h.documentId), h.revision, objects),
            listOf(PageDescriptor(PageId("page-1"), LayerId("ink"), 1000.0, 1400.0, contentRevision = h.revision)))
            .also { PagedPreviewCodec.retainedByteCharge(it) }
    }
    private fun metadata(row: List<String>): Metadata {
        require(row[0] == "D")
        val id = unescape(row[1]); val title = unescape(row[2]); val next = row[3].toLong()
        val count = row[4].toInt(); val revision = row[5].toLong()
        SceneId(id); require(title.length <= 120 && next > 0 && count in 0..500 && revision >= 0)
        return Metadata(PagedPreviewHeader(id, title, 1, count, revision, 1), next)
    }
    private fun unescape(value: String): String = buildString {
        var index = 0
        while (index < value.length) {
            val c = value[index++]
            require(c != '\r')
            if (c != '\\') append(c) else {
                require(index < value.length)
                append(when (value[index++]) { '\\' -> '\\'; 't' -> '\t'; 'n' -> '\n'; 'r' -> '\r'; else -> throw IllegalArgumentException("Invalid legacy escape") })
            }
        }
    }.also { text ->
        var index = 0
        while (index < text.length) {
            val c = text[index++]
            if (c.isHighSurrogate()) { require(index < text.length && text[index++].isLowSurrogate()) }
            else require(!c.isLowSurrogate())
        }
    }
}
