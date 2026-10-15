package dev.notecore.document

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
