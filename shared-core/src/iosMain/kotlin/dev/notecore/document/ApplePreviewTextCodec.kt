package dev.notecore.document

/** App-only adapter for strict UTF-8 decoded by the Apple file worker in bounded chunks. */
object ApplePreviewTextCodec {
    fun read(allowRecovery: Boolean, nextChunk: () -> String?): PreviewRecoveryRead =
        PreviewStreamReader.read(allowRecovery) refill@{ buffer ->
            val chunk = nextChunk() ?: return@refill -1
            require(chunk.length in 1..buffer.size)
            for (index in chunk.indices) buffer[index] = chunk[index]
            chunk.length
        }
}
