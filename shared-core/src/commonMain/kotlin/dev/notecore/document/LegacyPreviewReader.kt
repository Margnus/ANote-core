package dev.notecore.document

import dev.notecore.canvas.prototype.*

/** Read-only v1 compatibility boundary. Never creates an authority or exposes a v1 writer. */
internal object LegacyPreviewReader {
    private data class Metadata(val header: PagedPreviewHeader, val nextObject: Long)
    fun header(text: String): PagedPreviewHeader {
        require(text.length <= 4096)
        val rows = Rows(text)
        require(rows.row(2) == listOf("NCPREVIEW", "1"))
        return metadata(rows.row(6)).header
    }
    fun decode(text: String): DocumentRoot {
        require(text.length <= PagedPreviewCodec.MAX_CHARACTERS)
        val rows = Rows(text)
        require(rows.row(2) == listOf("NCPREVIEW", "1"))
        val meta = metadata(rows.row(6)); val h = meta.header
        val objects = ArrayList<StrokeObject>(h.objectCount); val ids = mutableSetOf<ObjectId>()
        var total = 0
        repeat(h.objectCount) {
            val row = rows.row(5); require(row[0] == "S")
            val id = ObjectId(row[1]); val number = row[1].removePrefix("ink-").toLong()
            require(row[1] == "ink-$number" && number in 1 until meta.nextObject && ids.add(id))
            val color = row[2].toInt(); val width = row[3].toDouble(); val size = row[4].toInt()
            require(width.isFinite() && width in 0.5..16.0)
            require(size in 1..PagedPreviewCodec.MAX_STROKE_POINTS && size <= PagedPreviewCodec.MAX_POINTS - total)
            total += size // Admit the aggregate before allocating the next real-sample arrays.
            val xs = DoubleArray(size); val ys = DoubleArray(size); val pressures = FloatArray(size); val times = LongArray(size)
            repeat(size) { index ->
                val point = rows.row(5); require(point[0] == "P")
                xs[index] = point[1].toDouble(); ys[index] = point[2].toDouble()
                pressures[index] = point[3].toFloat(); times[index] = point[4].toLong()
            }
            objects.add(StrokeObject(id, 1, LayerId("ink"), BrushSpec(BrushKind.MONOLINE, 1, width, color),
                StrokeSamples.copyOf(xs, ys, pressures, times)))
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
    private class Rows(private val text: String) {
        private var offset = 0
        fun row(fields: Int): List<String> {
            require(offset < text.length) { "Truncated legacy preview" }
            val start = offset
            while (offset < text.length && text[offset] != '\n') { require(offset - start < 1024); offset++ }
            val end = if (offset > start && text[offset - 1] == '\r') offset - 1 else offset
            val line = text.substring(start, end)
            if (offset < text.length) offset++
            require(line.count { it == '\t' } == fields - 1)
            return line.split('\t')
        }
        fun end() { require(offset == text.length) { "Trailing legacy data" } }
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
