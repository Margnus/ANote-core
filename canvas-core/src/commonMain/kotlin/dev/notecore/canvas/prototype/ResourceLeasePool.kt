package dev.notecore.canvas.prototype

/** Logical dispatch destination, not evidence of an actual platform thread. */
enum class ResourceLane { OWNER, WORKER, GPU }
class ResourceReservation internal constructor(internal val owner: Any, val bytes: Long)
class ResourceHandle internal constructor(internal val owner: Any, val bytes: Long)
class ResourceLease<V : Any> internal constructor(internal val owner: Any, resource: V) {
    private var held: V? = resource
    internal fun invalidate() { held = null }
    val value: V get() = checkNotNull(held) { "Lease has been returned" }
}
class ResourceReleaseTicket<V : Any> internal constructor(val handle: ResourceHandle, resource: V) {
    private var held: V? = resource
    internal fun invalidate() { held = null }
    val value: V get() = checkNotNull(held) { "Release attempt has finished" }
}
enum class ReservationFailure { CLOSED, INVALID_BYTES, RESIDENT_LIMIT, TOTAL_LIMIT, RESOURCE_COUNT }
sealed interface ReservationResult {
    data class Reserved(val reservation: ResourceReservation) : ReservationResult
    data class Rejected(val reason: ReservationFailure) : ReservationResult
}
sealed interface ResourcePublication {
    data class Resident(val handle: ResourceHandle) : ResourcePublication
    data class RetiredAfterClose(val handle: ResourceHandle) : ResourcePublication
    data object StaleReservation : ResourcePublication
    data object AlreadyOwned : ResourcePublication
}
sealed interface ResourceBorrow<out V : Any> {
    data class Borrowed<V : Any>(val lease: ResourceLease<V>) : ResourceBorrow<V>
    data object Missing : ResourceBorrow<Nothing>
    data object Closed : ResourceBorrow<Nothing>
    data object LeaseLimit : ResourceBorrow<Nothing>
}
enum class LeaseReturn { RETURNED, ALREADY_RETURNED, FOREIGN }
enum class ResourceReleaseOutcome { RELEASED, NOT_RELEASED, UNKNOWN }
enum class ResourceCompletion { UPDATED, STALE }
data class ResourceDrainStatus(
    val reservations: Int, val readers: Int, val releasing: Int,
    val unknown: List<ResourceHandle>, val ready: Int, val chargedBytes: Long, val isDrained: Boolean,
)
sealed interface ResourceReleaseDispatch<out V : Any> {
    data class Ready<V : Any>(val ticket: ResourceReleaseTicket<V>) : ResourceReleaseDispatch<V>
    data object Idle : ResourceReleaseDispatch<Nothing>
    data object WrongLane : ResourceReleaseDispatch<Nothing>
}

/** Single-owner C1 lifecycle model. Counts reservations and retired/in-flight/unknown releases until
 * the backend confirms destruction. Declared bytes are not measured heap/GPU footprint.
 */
class ResourceLeasePool<K : Any, V : Any>(
    val residentBudget: Long, val totalBudget: Long,
    val maxResources: Int = 256, val maxLeases: Int = 1024,
    val releaseLane: ResourceLane = ResourceLane.GPU,
) {
    // Returned tokens must not retain the entire pool through their ownership tag.
    private val identity = Any()
    private class Entry<V : Any>(val handle: ResourceHandle, val value: V) {
        var resident = true
        var borrowers = 0
        var release: ResourceReleaseTicket<V>? = null
        var unknownRelease = false
    }
    private val reservations = mutableSetOf<ResourceReservation>()
    private val resources = linkedMapOf<ResourceHandle, Entry<V>>()
    private val resident = linkedMapOf<K, Entry<V>>()
    private val leases = mutableMapOf<ResourceLease<V>, Entry<V>>()
    private var closed = false
    var chargedBytes: Long = 0; private set
    var residentBytes: Long = 0; private set
    val reservedBytes: Long get() = reservations.sumOf { it.bytes }
    val retiredBytes: Long get() = resources.values.filter { !it.resident }.sumOf { it.handle.bytes }
    /** Unique resource bytes referenced by readers; overlaps resident/retired and must not be summed. */
    val borrowedBytes: Long get() = resources.values.filter { it.borrowers > 0 }.sumOf { it.handle.bytes }
    val retainedResources: Int get() = resources.size + reservations.size
    val outstandingLeases: Int get() = leases.size
    val isDrained: Boolean get() = closed && resources.isEmpty() && reservations.isEmpty() && leases.isEmpty()
    init { require(residentBudget > 0 && totalBudget >= residentBudget && maxResources > 0 && maxLeases > 0) }
    fun drainStatus() = ResourceDrainStatus(reservations.size, leases.size,
        resources.values.count { it.release != null }, resources.values.filter { it.unknownRelease }.map { it.handle },
        resources.values.count { !it.resident && it.borrowers == 0 && it.release == null && !it.unknownRelease }, chargedBytes, isDrained)

    /** Reserve BEFORE allocating. Rejecting a replacement does not evict the old resident value. */
    fun reserve(bytes: Long): ReservationResult {
        val failure = when {
            closed -> ReservationFailure.CLOSED
            bytes <= 0 -> ReservationFailure.INVALID_BYTES
            bytes > residentBudget -> ReservationFailure.RESIDENT_LIMIT
            retainedResources >= maxResources -> ReservationFailure.RESOURCE_COUNT
            bytes > totalBudget - chargedBytes -> ReservationFailure.TOTAL_LIMIT
            else -> null
        }
        if (failure != null) return ReservationResult.Rejected(failure)
        val reservation = ResourceReservation(identity, bytes)
        reservations.add(reservation); chargedBytes += bytes
        return ReservationResult.Reserved(reservation)
    }

    /** Only when allocation definitely did not occur. Close cannot assume a pending worker stopped. */
    fun cancelUnallocated(reservation: ResourceReservation): Boolean {
        if (!reservations.remove(reservation)) return false
        chargedBytes -= reservation.bytes
        return true
    }

    /** Successful publication transfers ownership, even if a close requires immediate retirement. */
    fun publish(reservation: ResourceReservation, key: K, value: V): ResourcePublication {
        if (reservation !in reservations) return ResourcePublication.StaleReservation
        if (resources.values.any { it.value === value }) return ResourcePublication.AlreadyOwned
        reservations.remove(reservation)
        val entry = Entry(ResourceHandle(identity, reservation.bytes), value)
        resources[entry.handle] = entry
        if (closed) {
            entry.resident = false
            return ResourcePublication.RetiredAfterClose(entry.handle)
        }
        resident.remove(key)?.let(::retire)
        while (residentBytes > residentBudget - reservation.bytes) {
            val oldest = resident.entries.first()
            resident.remove(oldest.key); retire(oldest.value)
        }
        resident[key] = entry; residentBytes += reservation.bytes
        return ResourcePublication.Resident(entry.handle)
    }

    private fun retire(entry: Entry<V>) {
        entry.resident = false
        residentBytes -= entry.handle.bytes
    }

    fun borrow(key: K): ResourceBorrow<V> {
        if (closed) return ResourceBorrow.Closed
        val entry = resident[key] ?: return ResourceBorrow.Missing
        if (leases.size >= maxLeases) return ResourceBorrow.LeaseLimit
        resident.remove(key); resident[key] = entry
        val lease = ResourceLease(identity, entry.value)
        leases[lease] = entry; entry.borrowers++
        return ResourceBorrow.Borrowed(lease)
    }

    fun returnLease(lease: ResourceLease<V>): LeaseReturn {
        if (lease.owner !== identity) return LeaseReturn.FOREIGN
        val entry = leases.remove(lease) ?: return LeaseReturn.ALREADY_RETURNED
        lease.invalidate(); entry.borrowers--
        return LeaseReturn.RETURNED
    }

    fun evict(key: K): Boolean {
        val entry = resident.remove(key) ?: return false
        retire(entry); return true
    }

    /** Owner schedules the returned ticket on the designated lane; no destructor runs here. */
    fun nextRelease(destination: ResourceLane): ResourceReleaseDispatch<V> {
        if (destination != releaseLane) return ResourceReleaseDispatch.WrongLane
        val entry = resources.values.firstOrNull {
            !it.resident && it.borrowers == 0 && it.release == null && !it.unknownRelease
        } ?: return ResourceReleaseDispatch.Idle
        val ticket = ResourceReleaseTicket(entry.handle, entry.value)
        entry.release = ticket
        return ResourceReleaseDispatch.Ready(ticket)
    }

    /** Route backend outcomes back to this owner. Duplicate/old/foreign tickets never release budget. */
    fun completeRelease(ticket: ResourceReleaseTicket<V>, outcome: ResourceReleaseOutcome): ResourceCompletion {
        val entry = resources[ticket.handle] ?: return ResourceCompletion.STALE
        if (entry.release !== ticket) return ResourceCompletion.STALE
        entry.release = null; ticket.invalidate()
        when (outcome) {
            ResourceReleaseOutcome.RELEASED -> forgetReleased(entry)
            ResourceReleaseOutcome.NOT_RELEASED -> Unit // exact resource can be explicitly retried
            ResourceReleaseOutcome.UNKNOWN -> entry.unknownRelease = true // do not double-destroy
        }
        return ResourceCompletion.UPDATED
    }

    /** Explicit backend reconciliation only; UNKNOWN cannot resolve an UNKNOWN destruction outcome. */
    fun resolveUnknown(handle: ResourceHandle, outcome: ResourceReleaseOutcome): ResourceCompletion {
        if (outcome == ResourceReleaseOutcome.UNKNOWN) return ResourceCompletion.STALE
        val entry = resources[handle]?.takeIf { it.unknownRelease } ?: return ResourceCompletion.STALE
        entry.unknownRelease = false
        if (outcome == ResourceReleaseOutcome.RELEASED) forgetReleased(entry)
        return ResourceCompletion.UPDATED
    }

    private fun forgetReleased(entry: Entry<V>) {
        resources.remove(entry.handle)
        chargedBytes -= entry.handle.bytes
    }

    /** Terminal: readers and reservations still own lifetime obligations and remain charged. */
    fun close() {
        if (closed) return
        closed = true
        resident.values.forEach(::retire); resident.clear()
    }
}
