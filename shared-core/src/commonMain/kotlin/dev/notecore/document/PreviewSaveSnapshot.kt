package dev.notecore.document

import dev.notecore.canvas.preview.PreviewNotebookSnapshot

/** Closed immutable save inputs. The ledger never reads a mutable document or platform capability. */
sealed interface PreviewSaveSnapshot {
    val documentId: String
    val title: String
    val version: Long
    val retainedByteCharge: Long
    val encodingWorkByteCharge: Long
    @Throws(IllegalArgumentException::class)
    fun encode(): String
    fun sameContent(other: PreviewSaveSnapshot): Boolean
}

/** Transition adapter; keeps document/storage business contracts out of canvas-core. */
class LegacyPreviewSaveSnapshot(private val source: PreviewNotebookSnapshot) : PreviewSaveSnapshot {
    override val documentId: String get() = source.documentId
    override val title: String get() = source.title
    override val version: Long get() = source.version
    override val retainedByteCharge: Long get() = source.retainedByteCharge + 128L
    override val encodingWorkByteCharge: Long get() = source.encodingWorkByteCharge + 128L
    override fun encode(): String = source.encode()
    override fun sameContent(other: PreviewSaveSnapshot): Boolean = other is LegacyPreviewSaveSnapshot &&
        documentId == other.documentId && version == other.version && title == other.title &&
        source.nextObjectSequence == other.source.nextObjectSequence &&
        (source.canonicalScene() === other.source.canonicalScene() || source.canonicalScene().objects() == other.source.canonicalScene().objects())
}
