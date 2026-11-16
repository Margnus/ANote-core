package dev.notecore.document

import java.io.Reader

/** App-only streaming adapter for the existing preview and recovery grammars. */
object AndroidPreviewTextCodec {
    fun write(snapshot: PagedPreviewSnapshot, beforeWrite: ((Int) -> Unit)?, row: (String) -> Unit) {
        PreviewRecoveryCodec.writeRows(snapshot, beforeWrite, row)
    }

    fun readCheckpoint(reader: Reader): PreviewRecoveryRead = read(reader, allowRecovery = true)
    fun readDocument(reader: Reader): PreviewRecoveryRead = read(reader, allowRecovery = false)

    private fun read(reader: Reader, allowRecovery: Boolean): PreviewRecoveryRead {
        val input = PreviewInput(reader)
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

    private class PreviewInput(private val reader: Reader) {
        private val buffer = CharArray(8192)
        private var offset = 0
        private var size = 0
        private var eof = false
        var readerFailure: IllegalArgumentException? = null
            private set
        var position = 0
            private set

        fun next(): Int {
            if (eof) return -1
            if (offset == size) {
                size = try { reader.read(buffer) }
                catch (failure: IllegalArgumentException) {
                    readerFailure = failure
                    throw failure
                }
                offset = 0
                if (size < 0) {
                    eof = true
                    return -1
                }
                if (size == 0) throw java.io.IOException("Preview reader made no progress")
            }
            return buffer[offset++].code.also { position++ }
        }

        fun line(limit: Int, end: Int? = null): String {
            val line = StringBuilder()
            val start = position
            while (end == null || position < end) {
                val value = next()
                if (value < 0) break
                if (value == '\n'.code) return line.toString()
                require(line.length < limit && position <= PagedPreviewCodec.MAX_CHARACTERS)
                line.append(value.toChar())
            }
            require(position > start) { "Truncated preview document" }
            return line.toString()
        }

        fun drain() {
            while (next() >= 0) Unit
        }
    }
}
