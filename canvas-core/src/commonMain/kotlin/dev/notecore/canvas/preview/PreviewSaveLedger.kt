package dev.notecore.canvas.preview

/** Preview full-snapshot replacement only; not a command ledger or production durable authority.
 * Single owner; an admitted latest snapshot and every in-flight older snapshot retain their budgets.
 * Version order assumes one live canonical notebook owner per document, never concurrent forks.
 */
enum class PreviewSaveAdmission { ACCEPTED, STALE_VERSION, DOCUMENT_CAPACITY, BYTE_CAPACITY }
class PreviewSaveAttempt internal constructor(internal val identity: Any, val number: Long, val snapshot: PreviewNotebookSnapshot)
data class PreviewSaveState(val snapshot: PreviewNotebookSnapshot, val inFlight: Boolean, val retryRequired: Boolean)

class PreviewSaveLedger(private val maxDocuments: Int = 8, private val byteBudget: Long = 32L * 1024 * 1024) {
    private class Entry(var latest: PreviewNotebookSnapshot, var flight: PreviewSaveAttempt? = null, var retryRequired: Boolean = false)
    private val identity = Any()
    private val entries = linkedMapOf<String, Entry>()
    private var sequence = 0L
    init { require(maxDocuments in 1..64 && byteBudget >= 0) }
    val chargedBytes: Long get() = entries.values.sumOf { charge(it.latest, it.flight) }
    val documentCount: Int get() = entries.size
    val isDrained: Boolean get() = entries.isEmpty()
    private fun charge(latest: PreviewNotebookSnapshot, flight: PreviewSaveAttempt?): Long =
        latest.retainedByteCharge + if (flight != null && flight.snapshot !== latest) flight.snapshot.retainedByteCharge else 0

    /** Rejected snapshots remain with caller. A newer complete snapshot can supersede unsent/failed older content.
     * An in-flight older write is never replaced or freed before its matching completion.
     * Same-version admission retries the retained immutable content; it does not replace it with a fork.
     */
    fun offer(snapshot: PreviewNotebookSnapshot): PreviewSaveAdmission {
        val entry = entries[snapshot.documentId]
        if (entry != null && snapshot.version < entry.latest.version) return PreviewSaveAdmission.STALE_VERSION
        if (entry == null && entries.size >= maxDocuments) return PreviewSaveAdmission.DOCUMENT_CAPACITY
        if (entry != null && snapshot.version == entry.latest.version) {
            entry.retryRequired = false
            return PreviewSaveAdmission.ACCEPTED
        }
        val oldCharge = if (entry == null) 0L else charge(entry.latest, entry.flight)
        val newCharge = charge(snapshot, entry?.flight)
        if (newCharge > byteBudget - (chargedBytes - oldCharge)) return PreviewSaveAdmission.BYTE_CAPACITY
        if (entry == null) entries[snapshot.documentId] = Entry(snapshot)
        else { entry.latest = snapshot; entry.retryRequired = false }
        return PreviewSaveAdmission.ACCEPTED
    }
    fun latest(documentId: String): PreviewNotebookSnapshot? = entries[documentId]?.latest
    fun states(): List<PreviewSaveState> = entries.values.map { PreviewSaveState(it.latest, it.flight != null, it.retryRequired) }
    fun nextWrite(): PreviewSaveAttempt? {
        val entry = entries.values.firstOrNull { it.flight == null && !it.retryRequired } ?: return null
        check(sequence < Long.MAX_VALUE) { "Save attempt identity exhausted; retain snapshots for recovery" }
        return PreviewSaveAttempt(identity, ++sequence, entry.latest).also { entry.flight = it }
    }
    /** Backend rejected before execution; return to pending without reporting a disk write failure. */
    fun returnUnstarted(attempt: PreviewSaveAttempt): Boolean {
        val entry = matching(attempt) ?: return false
        entry.flight = null
        return true
    }
    /** Success means the platform completed its atomic write. Unknown/failure retains full content for explicit retry.
     * A newer admitted complete snapshot survives either result and remains eligible to write.
     */
    fun complete(attempt: PreviewSaveAttempt, success: Boolean): Boolean {
        val entry = matching(attempt) ?: return false
        entry.flight = null
        if (entry.latest === attempt.snapshot) {
            if (success) entries.remove(attempt.snapshot.documentId) else entry.retryRequired = true
        }
        return true
    }
    private fun matching(attempt: PreviewSaveAttempt): Entry? =
        entries[attempt.snapshot.documentId]?.takeIf { attempt.identity === identity && it.flight === attempt }
}
