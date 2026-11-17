package dev.notecore.document

/** The App readers provide bounded, strictly decoded UTF-16 chunks. */
internal object PreviewStreamReader {
    fun read(allowRecovery: Boolean, readChars: (CharArray) -> Int): PreviewRecoveryRead {
        val input = PreviewInput(readChars)
        val result = PreviewRecoveryCodec.readParsed {
            val magic = input.line(1024)
            if (allowRecovery && magic == PreviewRecoveryCodec.MAGIC.dropLast(1)) {
                val metadata = input.line(4096 - PreviewRecoveryCodec.MAGIC.length)
                val (baseSize, inkSize, operation) = PreviewRecoveryCodec.readFrame(input.line(1023))
                val baseEnd = input.position.toLong() + baseSize
                val inkEnd = baseEnd + inkSize
                require(inkEnd + PreviewRecoveryCodec.END.length <= PagedPreviewCodec.MAX_CHARACTERS)
                val base = readRoot(input, input.line(1024, baseEnd.toInt()), baseEnd.toInt(), metadata)
                val ink = readRoot(input, input.line(1024, inkEnd.toInt()), inkEnd.toInt())
                val footerOffset = input.position
                require(input.line(PreviewRecoveryCodec.END.length) == PreviewRecoveryCodec.END.dropLast(1))
                require(input.position - footerOffset == PreviewRecoveryCodec.END.length && input.next() < 0)
                PreviewRecoveryCodec.restore(base, ink, operation)
            } else {
                PagedPreviewSnapshot(readRoot(input, magic, null))
            }
        }
        input.readerFailure?.let { throw it }
        // A malformed early row must not mask a later decoding failure or a document over the character limit.
        input.drain()
        return if (input.position > PagedPreviewCodec.MAX_CHARACTERS)
            PreviewRecoveryRead(null, PreviewReadFailure.CHARACTER_CAPACITY)
        else result
    }

    private fun readRoot(input: PreviewInput, magic: String, end: Int?, metadata: String? = null): DocumentRoot {
        var first = true
        val rows = object : PreviewRows {
            override fun line(): String {
                val line = input.line(1024, end)
                if (first && metadata != null) require(line == metadata)
                first = false
                return line.removeSuffix("\r")
            }

            override fun end() {
                if (end == null) require(input.next() < 0) { "Trailing preview data" }
                else require(input.position == end) { "Preview frame length differs from its content" }
            }
        }
        return PreviewRecoveryCodec.accepted(PagedPreviewCodec.decodeRows(previewFields(magic.removeSuffix("\r"), 2), rows))
    }

    private class PreviewInput(private val readChars: (CharArray) -> Int) {
        private val buffer = CharArray(8192)
        private var offset = 0
        private var size = 0
        private var eof = false
        var readerFailure: IllegalArgumentException? = null
            private set
        var position = 0
            private set

        private fun refill(): Boolean {
            if (eof) return false
            if (offset < size) return true
            size = try { readChars(buffer) }
            catch (failure: IllegalArgumentException) {
                readerFailure = failure
                throw failure
            }
            offset = 0
            if (size < 0) {
                eof = true
                return false
            }
            check(size in 1..buffer.size) { "Invalid preview reader chunk size" }
            return true
        }

        fun next(): Int = if (refill()) buffer[offset++].code.also { position++ } else -1

        fun line(limit: Int, end: Int? = null): String {
            var prefix: StringBuilder? = null
            val startPosition = position
            while ((end == null || position < end) && refill()) {
                val start = offset
                val bound = if (end == null) size else minOf(size, offset + end - position)
                while (offset < bound && buffer[offset] != '\n') offset++
                val count = offset - start
                position += count
                require(count <= limit - (prefix?.length ?: 0))
                require(count == 0 || position <= PagedPreviewCodec.MAX_CHARACTERS)
                val text = buffer.concatToString(start, offset)
                if (offset < bound || position == end) {
                    if (offset < bound) { offset++; position++ } // LF inside this section.
                    return prefix?.append(text)?.toString() ?: text
                }
                if (prefix == null) prefix = StringBuilder()
                prefix.append(text)
            }
            require(position > startPosition) { "Truncated preview document" }
            return checkNotNull(prefix).toString()
        }

        fun drain() {
            while (refill()) {
                position += size - offset
                offset = size
            }
        }
    }
}
