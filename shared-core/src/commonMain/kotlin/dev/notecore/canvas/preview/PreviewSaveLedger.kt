package dev.notecore.canvas.preview

import dev.notecore.document.PreviewSaveSnapshot

/** Preview full-snapshot replacement only; not a command ledger or production durable authority.
 * Single owner; an admitted latest snapshot and every in-flight older snapshot retain their budgets.
 * Version order assumes one live canonical notebook owner per document, never concurrent forks.
 */
enum class PreviewSaveAdmission { ACCEPTED, STALE_VERSION, VERSION_CONFLICT, DOCUMENT_CAPACITY, BYTE_CAPACITY }
class PreviewSaveAttempt internal constructor(internal val identity: Any, val number: Long, val snapshot: PreviewSaveSnapshot)
data class PreviewSaveState(val snapshot: PreviewSaveSnapshot, val inFlight: Boolean, val retryRequired: Boolean)

enum class PreviewWriteDisposition { READY, IDLE, BACKPRESSURE, WORK_CAPACITY, INVALID_BUDGET, IDENTITY_EXHAUSTED, BACKEND_UNAVAILABLE }

/** Only READY transfers an attempt to the caller. Other results leave every snapshot in the ledger. */
class PreviewWriteAdmission internal constructor(val disposition: PreviewWriteDisposition,
    val attempt: PreviewSaveAttempt? = null, val documentId: String? = null)

class PreviewSaveLedger(private val maxDocuments: Int = 8, private val byteBudget: Long = 32L * 1024 * 1024,
    private val ownerCheck: () -> Unit = {}) {
    private class Entry(var latest: PreviewSaveSnapshot, var flight: PreviewSaveAttempt? = null, var retryRequired: Boolean = false)
    private val identity = Any()
    private val entries = linkedMapOf<String, Entry>()
    private var sequence = 0L
    init { ownerCheck(); require(maxDocuments in 1..64 && byteBudget >= 0) }
    val chargedBytes: Long get() { ownerCheck(); return entries.values.sumOf { charge(it.latest, it.flight) } }
    val documentCount: Int get() { ownerCheck(); return entries.size }
    val isDrained: Boolean get() { ownerCheck(); return entries.isEmpty() }
    private fun charge(latest: PreviewSaveSnapshot, flight: PreviewSaveAttempt?): Long =
        latest.retainedByteCharge + if (flight != null && flight.snapshot !== latest) flight.snapshot.retainedByteCharge else 0

    /** Rejected snapshots remain with caller. A newer complete snapshot can supersede unsent/failed older content.
     * An in-flight older write is never replaced or freed before its matching completion.
     * Same-version admission retries the retained immutable content; it does not replace it with a fork.
     */
    fun offer(snapshot: PreviewSaveSnapshot): PreviewSaveAdmission {
        ownerCheck()
        val entry = entries[snapshot.documentId]
        if (entry != null && snapshot.version < entry.latest.version) return PreviewSaveAdmission.STALE_VERSION
        if (entry == null && entries.size >= maxDocuments) return PreviewSaveAdmission.DOCUMENT_CAPACITY
        if (entry != null && snapshot.version == entry.latest.version) {
            if (snapshot === entry.latest || entry.latest.sameContent(snapshot)) {
                entry.retryRequired = false
                return PreviewSaveAdmission.ACCEPTED
            }
            if (!snapshot.strictlyExtends(entry.latest)) return PreviewSaveAdmission.VERSION_CONFLICT
        }
        val oldCharge = if (entry == null) 0L else charge(entry.latest, entry.flight)
        val newCharge = charge(snapshot, entry?.flight)
        if (newCharge > byteBudget - (chargedBytes - oldCharge)) return PreviewSaveAdmission.BYTE_CAPACITY
        if (entry == null) entries[snapshot.documentId] = Entry(snapshot)
        else { entry.latest = snapshot; entry.retryRequired = false }
        return PreviewSaveAdmission.ACCEPTED
    }
    fun latest(documentId: String): PreviewSaveSnapshot? { ownerCheck(); return entries[documentId]?.latest }
    fun states(): List<PreviewSaveState> {
        ownerCheck(); return entries.values.map { PreviewSaveState(it.latest, it.flight != null, it.retryRequired) }
    }
    /** Compatibility polling only. Hosts use the typed admission to distinguish capacity and exhaustion. */
    fun nextWrite(): PreviewSaveAttempt? = admitNextWrite(Long.MAX_VALUE, Long.MAX_VALUE).attempt

    /** Owner reads the original executor's remaining/total declared bytes immediately before this call.
     * A later executor slot/stop refusal must returnUnstarted; no side effect has run at admission time.
     * Skip permanently oversized documents, never a same-document flight. A temporarily blocked
     * eligible document keeps its place so a stream of smaller saves cannot starve it.
     */
    fun admitNextWrite(availableWorkBytes: Long, maximumWorkBytes: Long, backendAccepting: Boolean = true): PreviewWriteAdmission {
        ownerCheck()
        if (maximumWorkBytes <= 0 || availableWorkBytes < 0 || availableWorkBytes > maximumWorkBytes)
            return PreviewWriteAdmission(PreviewWriteDisposition.INVALID_BUDGET)
        var oversized: Entry? = null
        for (entry in entries.values) {
            if (entry.flight != null || entry.retryRequired) continue
            if (!backendAccepting)
                return PreviewWriteAdmission(PreviewWriteDisposition.BACKEND_UNAVAILABLE, documentId = entry.latest.documentId)
            val workBytes = entry.latest.encodingWorkByteCharge
            if (workBytes > maximumWorkBytes) { if (oversized == null) oversized = entry; continue }
            if (workBytes > availableWorkBytes)
                return PreviewWriteAdmission(PreviewWriteDisposition.BACKPRESSURE, documentId = entry.latest.documentId)
            if (sequence == Long.MAX_VALUE)
                return PreviewWriteAdmission(PreviewWriteDisposition.IDENTITY_EXHAUSTED, documentId = entry.latest.documentId)
            val attempt = PreviewSaveAttempt(identity, ++sequence, entry.latest)
            entry.flight = attempt
            return PreviewWriteAdmission(PreviewWriteDisposition.READY, attempt, entry.latest.documentId)
        }
        return when {
            oversized != null -> PreviewWriteAdmission(PreviewWriteDisposition.WORK_CAPACITY, documentId = oversized.latest.documentId)
            else -> PreviewWriteAdmission(PreviewWriteDisposition.IDLE)
        }
    }
    /** Backend rejected before execution; return to pending without reporting a disk write failure. */
    fun returnUnstarted(attempt: PreviewSaveAttempt): Boolean {
        ownerCheck()
        val entry = matching(attempt) ?: return false
        entry.flight = null
        return true
    }
    /** Success means the platform completed its atomic write. Unknown/failure retains full content for explicit retry.
     * A newer admitted complete snapshot survives either result and remains eligible to write.
     */
    fun complete(attempt: PreviewSaveAttempt, success: Boolean): Boolean {
        ownerCheck()
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
