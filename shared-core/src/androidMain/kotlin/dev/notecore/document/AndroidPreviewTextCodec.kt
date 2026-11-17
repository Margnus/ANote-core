package dev.notecore.document

import java.io.Reader

/** App-only streaming adapter for the existing preview and recovery grammars. */
object AndroidPreviewTextCodec {
    fun write(snapshot: PagedPreviewSnapshot, beforeWrite: ((Int) -> Unit)?, row: (String) -> Unit) {
        PreviewRecoveryCodec.writeRows(snapshot, beforeWrite, row)
    }

    fun readCheckpoint(reader: Reader): PreviewRecoveryRead = read(reader, allowRecovery = true)
    fun readDocument(reader: Reader): PreviewRecoveryRead = read(reader, allowRecovery = false)

    private fun read(reader: Reader, allowRecovery: Boolean): PreviewRecoveryRead =
        PreviewStreamReader.read(allowRecovery) { buffer ->
            reader.read(buffer).also { if (it == 0) throw java.io.IOException("Preview reader made no progress") }
        }
}
