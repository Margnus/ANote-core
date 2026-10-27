package dev.notecore.document

import dev.notecore.canvas.prototype.*

data class PagedPreviewHeader(val documentId: String, val title: String, val pageCount: Int,
                              val objectCount: Int, val revision: Long, val formatVersion: Int)

/** Unfrozen bounded preview interchange. No filesystem, mutable authority, history or durable receipts. */
object PagedPreviewCodec {
    const val MAX_PAGES = 128
    const val MAX_OBJECTS = 5000
    const val MAX_POINTS = 200_000
    const val MAX_STROKE_POINTS = 20_000
    private const val MAX_LINE = 1024
    // Shortest finite Double/Float and nonnegative Long point fields fit 96 ASCII bytes.
    // Metadata UTF-8 is bounded separately per row. Include one full recovery completion.
    private const val MAX_POINT_LINE = 96
    private const val MAX_BASE_TEXT = 2048 + MAX_PAGES * MAX_LINE + MAX_OBJECTS * MAX_LINE + MAX_POINTS * MAX_POINT_LINE
    const val MAX_CHARACTERS = MAX_BASE_TEXT + 8 * MAX_LINE + MAX_STROKE_POINTS * MAX_POINT_LINE
    const val MAX_UTF8_BYTES = 32 * 1024 * 1024
    /** Unfrozen primitive accessors for native hosts; one shared limit, no mirrored magic numbers. */
    fun maximumCharacterCount(): Int = MAX_CHARACTERS
    fun maximumUtf8ByteCount(): Int = MAX_UTF8_BYTES
    fun maximumDecodingWorkByteCharge(): Long = decodingWorkByteCharge(MAX_CHARACTERS)
    private const val MAX_RETAINED_BYTES = 8L * 1024 * 1024

    /** Bounded metadata only. Callers must decode the whole document before trusting its body. */
    @Throws(IllegalArgumentException::class)
    fun header(text: String): PagedPreviewHeader {
        require(text.length <= 4096)
        val cursor = Rows(text)
        return when (cursor.row(2)) {
            listOf("NCPREVIEW", "1") -> LegacyPreviewReader.header(text)
            listOf("NCPREVIEW", "2") -> metadata(cursor.row(6))
            else -> throw IllegalArgumentException("Unsupported preview version")
        }
    }

    @Throws(IllegalArgumentException::class)
    fun decode(text: String): DocumentRoot {
        require(text.length <= MAX_CHARACTERS) { "Preview document too large" }
        val rows = Rows(text)
        when (rows.row(2)) {
            listOf("NCPREVIEW", "1") -> {
                return LegacyPreviewReader.decode(text)
            }
            listOf("NCPREVIEW", "2") -> Unit
            else -> throw IllegalArgumentException("Unsupported preview version")
        }
        val header = metadata(rows.row(6))
        val pages = ArrayList<PageDescriptor>(header.pageCount)
        val pageIds = mutableSetOf<PageId>(); val layers = mutableSetOf<LayerId>()
        var retained = 512L + header.documentId.length * 2L + header.title.length * 2L
        repeat(header.pageCount) {
            val row = rows.row(7); require(row[0] == "G")
            val page = PageDescriptor(PageId(unescape(row[1])), LayerId(unescape(row[2])), row[3].toDouble(), row[4].toDouble(),
                PaperKind.valueOf(row[5]), row[6].toLong())
            require(page.contentRevision <= header.revision && pageIds.add(page.id) && layers.add(page.layerId))
            retained += pageCharge(page); require(retained <= MAX_RETAINED_BYTES)
            pages.add(page)
        }
        val objects = ArrayList<StrokeObject>(header.objectCount); val ids = mutableSetOf<ObjectId>()
        var total = 0
        repeat(header.objectCount) {
            val row = rows.row(15); require(row[0] == "S")
            val id = ObjectId(unescape(row[1])); val layer = LayerId(unescape(row[2])); val revision = row[3].toLong()
            val brush = BrushSpec(BrushKind.valueOf(row[4]), row[5].toInt(), row[7].toDouble(), row[6].toInt())
            require(ids.add(id) && layer in layers && revision > 0 && brush.algorithmVersion == 1)
            val size = row[8].toInt(); require(size in 1..MAX_STROKE_POINTS && size <= MAX_POINTS - total)
            total += size
            retained += 256L + (id.value.length + layer.value.length) * 2L + size.toLong() * 28
            require(retained <= MAX_RETAINED_BYTES)
            val transform = Affine2D(row[9].toDouble(), row[10].toDouble(), row[11].toDouble(), row[12].toDouble(), row[13].toDouble(), row[14].toDouble())
            require(transform.inverseOrNull() != null)
            val xs = DoubleArray(size); val ys = DoubleArray(size); val pressures = FloatArray(size); val times = LongArray(size)
            repeat(size) { point ->
                val p = rows.row(5); require(p[0] == "P")
                xs[point] = p[1].toDouble(); ys[point] = p[2].toDouble(); pressures[point] = p[3].toFloat(); times[point] = p[4].toLong()
            }
            objects.add(StrokeObject(id, revision, layer, brush, StrokeSamples.copyOf(xs, ys, pressures, times), transform))
        }
        require(rows.row(1) == listOf("END")); rows.end()
        return DocumentRoot.compose(header.title, SceneSnapshot.restored(SceneId(header.documentId), header.revision, objects), pages)
    }

    /** Conservative retained charge; validates encode/decode capacity and known preview algorithms. */
    @Throws(IllegalArgumentException::class)
    fun retainedByteCharge(root: DocumentRoot): Long {
        require(root.pageCount in 1..MAX_PAGES && root.scene.objectCount <= MAX_OBJECTS)
        validText(root.id.value); validText(root.title)
        var charge = 512L + (root.id.value.length + root.title.length) * 2L
        for (page in root.pages()) { validText(page.id.value); validText(page.layerId.value); charge += pageCharge(page) }
        var total = 0
        root.scene.forEachObject { obj ->
            validText(obj.id.value); validText(obj.layerId.value)
            require(obj.brush.algorithmVersion == 1 && obj.transform.hasInverse())
            require(obj.samples.size in 1..MAX_STROKE_POINTS && obj.samples.size <= MAX_POINTS - total)
            // Public canonical snapshots already validate geometry at restore/prepare boundaries.
            // Charging a UI capture must not rescan every immutable real sample.
            total += obj.samples.size
            charge += 256L + (obj.id.value.length + obj.layerId.value.length) * 2L + obj.samples.size.toLong() * 28
        }
        require(charge <= MAX_RETAINED_BYTES)
        return charge
    }

    /** Pending accepted ink must still fit the same future canonical codec, before any retry. */
    internal fun validateCompletedStrokeCapacity(root: DocumentRoot, objectId: ObjectId, layerId: LayerId, points: Int) {
        require(root.scene.objectCount < MAX_OBJECTS && points in 1..MAX_STROKE_POINTS)
        var existingPoints = 0L
        root.scene.forEachObject { existingPoints += it.samples.size }
        require(points.toLong() <= MAX_POINTS - existingPoints)
        val futureCharge = retainedByteCharge(root) + 256L + (objectId.value.length + layerId.value.length) * 2L + points.toLong() * 28L
        require(futureCharge <= MAX_RETAINED_BYTES)
    }

    @Throws(IllegalArgumentException::class)
    fun encodingWorkByteCharge(root: DocumentRoot): Long {
        return retainedByteCharge(root) + encodingTextWorkByteCharge(root)
    }

    /** Internal caller must already have validated the same immutable root's retained charge. */
    internal fun encodingTextWorkByteCharge(root: DocumentRoot): Long {
        var points = 0L
        root.scene.forEachObject { points += it.samples.size }
        val characters = minOf(MAX_CHARACTERS.toLong(), 2048L + root.pageCount * 1024L + root.scene.objectCount * 1024L + points * 144L)
        return characters * 8L
    }

    /** Reserve input text / UTF conversion plus worst-case owned sample arrays before worker decoding. */
    @Throws(IllegalArgumentException::class)
    fun decodingWorkByteCharge(characterCount: Int): Long {
        require(characterCount in 0..MAX_CHARACTERS)
        return 4096L + characterCount * 8L + MAX_RETAINED_BYTES * 3L
    }

    @Throws(IllegalArgumentException::class)
    fun encode(root: DocumentRoot): String {
        retainedByteCharge(root)
        val out = BoundedText()
        out.row("NCPREVIEW", 2)
        out.row("D", escape(root.id.value), escape(root.title), root.pageCount, root.scene.objectCount, root.revision)
        for (page in root.pages()) out.row("G", escape(page.id.value), escape(page.layerId.value), page.width, page.height, page.paper.name, page.contentRevision)
        root.scene.forEachObject { obj ->
            val m = obj.transform
            out.row("S", escape(obj.id.value), escape(obj.layerId.value), obj.objectRevision, obj.brush.kind.name, obj.brush.algorithmVersion,
                obj.brush.argb, obj.brush.width, obj.samples.size, m.a, m.b, m.c, m.d, m.tx, m.ty)
            for (p in 0 until obj.samples.size) out.point("P\t${obj.samples.x(p)}\t${obj.samples.y(p)}\t${obj.samples.pressure(p)}\t${obj.samples.timeMicros(p)}\n")
        }
        out.row("END")
        return out.toString()
    }

    /** Caller supplies all new identities; no allocator or mutable authority is hidden in a codec. */
    @Throws(IllegalArgumentException::class)
    fun importAsCopy(text: String, documentId: SceneId, pageIds: Map<PageId, PageId>, layerIds: Map<LayerId, LayerId>,
                     objectIds: Map<ObjectId, ObjectId>): DocumentRoot = copyAsNew(decode(text), documentId, pageIds, layerIds, objectIds)

    /** Already decoded immutable input avoids a second full parse/point-array allocation during native import. */
    @Throws(IllegalArgumentException::class)
    fun copyAsNew(source: DocumentRoot, documentId: SceneId, pageIds: Map<PageId, PageId>, layerIds: Map<LayerId, LayerId>,
                  objectIds: Map<ObjectId, ObjectId>): DocumentRoot {
        retainedByteCharge(source)
        require(pageIds.size <= MAX_PAGES && layerIds.size <= MAX_PAGES && objectIds.size <= MAX_OBJECTS)
        val pages = pageIds.toMap(); val layers = layerIds.toMap(); val objects = objectIds.toMap()
        require(documentId != source.id)
        fun <T> fresh(mapping: Map<T, T>, expected: Set<T>) {
            require(mapping.keys == expected && mapping.values.toSet().size == mapping.size && mapping.values.none { it in expected })
        }
        fresh(pages, source.pages().map { it.id }.toSet()); fresh(layers, source.pages().map { it.layerId }.toSet())
        fresh(objects, source.scene.objects().map { it.id }.toSet())
        val copied = DocumentRoot.compose(source.title, SceneSnapshot.restored(documentId, 0, source.scene.objects().map {
            it.copy(id = objects.getValue(it.id), layerId = layers.getValue(it.layerId), objectRevision = 1)
        }), source.pages().map { it.copy(id = pages.getValue(it.id), layerId = layers.getValue(it.layerId), contentRevision = 0) })
        retainedByteCharge(copied)
        return copied
    }

    private fun pageCharge(page: PageDescriptor): Long = 192L + (page.id.value.length + page.layerId.value.length) * 2L
    private fun metadata(row: List<String>): PagedPreviewHeader {
        require(row[0] == "D")
        val id = unescape(row[1]); val title = unescape(row[2]); SceneId(id); require(title.length <= 120)
        val pages = row[3].toInt(); val objects = row[4].toInt(); val revision = row[5].toLong()
        require(pages in 1..MAX_PAGES && objects in 0..MAX_OBJECTS && revision >= 0)
        return PagedPreviewHeader(id, title, pages, objects, revision, 2)
    }
    private class Rows(private val text: String) {
        private var offset = 0
        fun row(fields: Int): List<String> {
            require(offset < text.length) { "Truncated preview document" }
            val start = offset
            while (offset < text.length && text[offset] != '\n') { require(offset - start < MAX_LINE); offset++ }
            val end = if (offset > start && text[offset - 1] == '\r') offset - 1 else offset
            val line = text.substring(start, end)
            if (offset < text.length) offset++
            // Bound split allocation by checking delimiter count before creating a field list.
            require(line.count { it == '\t' } == fields - 1)
            return line.split('\t')
        }
        fun end() { require(offset == text.length) { "Trailing preview data" } }
    }
    private class BoundedText {
        private val value = StringBuilder()
        private var utf8Bytes = 0
        fun row(vararg fields: Any) {
            val line = fields.joinToString("\t") + "\n"
            var size = 0; var index = 0
            while (index < line.length) {
                val c = line[index++]
                size += when {
                    c.code <= 0x7f -> 1
                    c.code <= 0x7ff -> 2
                    c.isHighSurrogate() -> { index++; 4 } // Stored strings were validated before encoding.
                    else -> 3
                }
            }
            require(size <= MAX_LINE) { "Preview metadata row too large" }
            append(line, size)
        }
        fun point(line: String) {
            require(line.length <= MAX_POINT_LINE) { "Preview point row too large" }
            append(line, line.length) // Numeric fields and separators are ASCII.
        }
        private fun append(line: String, bytes: Int) {
            require(line.length <= MAX_LINE && line.length <= MAX_CHARACTERS - value.length && bytes <= MAX_UTF8_BYTES - utf8Bytes) { "Preview document too large" }
            value.append(line); utf8Bytes += bytes
        }
        override fun toString(): String = value.toString()
    }
    private fun validText(value: String) { require(value.hasValidUnicode()) { "Invalid Unicode text" } }
    private fun escape(value: String): String = buildString {
        validText(value)
        for (c in value) append(when (c) { '\\' -> "\\\\"; '\t' -> "\\t"; '\n' -> "\\n"; '\r' -> "\\r"; else -> c.toString() })
    }
    private fun unescape(value: String): String = buildString {
        var i = 0
        while (i < value.length) {
            val c = value[i++]
            require(c != '\r')
            if (c != '\\') append(c) else {
                require(i < value.length)
                append(when (value[i++]) { '\\' -> '\\'; 't' -> '\t'; 'n' -> '\n'; 'r' -> '\r'; else -> throw IllegalArgumentException("Invalid escape") })
            }
        }
    }.also(::validText)
}
