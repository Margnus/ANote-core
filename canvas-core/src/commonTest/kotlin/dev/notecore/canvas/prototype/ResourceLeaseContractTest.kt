package dev.notecore.canvas.prototype

import kotlin.test.*

class ResourceLeaseContractTest {
    private fun pool(resident: Long = 8, total: Long = 16, count: Int = 256, leases: Int = 1024) =
        ResourceLeasePool<String, FakeGpuResource>(resident, total, count, leases)
    private fun reserve(pool: ResourceLeasePool<String, FakeGpuResource>, bytes: Long = 4) =
        assertIs<ReservationResult.Reserved>(pool.reserve(bytes)).reservation
    private fun publish(pool: ResourceLeasePool<String, FakeGpuResource>, key: String, value: FakeGpuResource = FakeGpuResource(key), bytes: Long = 4): ResourceHandle =
        assertIs<ResourcePublication.Resident>(pool.publish(reserve(pool, bytes), key, value)).handle
    private fun borrow(pool: ResourceLeasePool<String, FakeGpuResource>, key: String) =
        assertIs<ResourceBorrow.Borrowed<FakeGpuResource>>(pool.borrow(key)).lease
    private fun release(pool: ResourceLeasePool<String, FakeGpuResource>) =
        assertIs<ResourceReleaseDispatch.Ready<FakeGpuResource>>(pool.nextRelease(ResourceLane.GPU)).ticket
    private fun audit(pool: ResourceLeasePool<String, FakeGpuResource>) {
        assertEquals(pool.chargedBytes, pool.reservedBytes + pool.residentBytes + pool.retiredBytes)
        assertTrue(pool.chargedBytes in 0..pool.totalBudget)
        assertTrue(pool.residentBytes in 0..pool.residentBudget)
        assertTrue(pool.borrowedBytes <= pool.chargedBytes)
    }

    @Test fun reservationPrecedesAllocationAndCancellationRestoresBudget() {
        val pool = pool(); val reservation = reserve(pool)
        assertEquals(4L, pool.chargedBytes); assertEquals(4L, pool.reservedBytes)
        assertEquals(1, pool.retainedResources); audit(pool)
        assertTrue(pool.cancelUnallocated(reservation)); assertFalse(pool.cancelUnallocated(reservation))
        assertEquals(0L, pool.chargedBytes)
    }

    @Test fun admissionFailureNeverEvictsResidentOrPretendsRetiredBytesAreFree() {
        val pool = pool(total = 8); publish(pool, "old", bytes = 8)
        assertEquals(ReservationFailure.TOTAL_LIMIT, assertIs<ReservationResult.Rejected>(pool.reserve(4)).reason)
        pool.evict("old")
        assertEquals(ReservationFailure.TOTAL_LIMIT, assertIs<ReservationResult.Rejected>(pool.reserve(4)).reason)
        val ticket = release(pool)
        assertEquals(8L, pool.chargedBytes)
        pool.completeRelease(ticket, ResourceReleaseOutcome.RELEASED)
        assertIs<ReservationResult.Reserved>(pool.reserve(4)); audit(pool)
    }

    @Test fun evictionCannotDestroyAResourceStillBorrowedByReader() {
        val pool = pool(); publish(pool, "a")
        val lease = borrow(pool, "a"); pool.evict("a")
        assertEquals("a", lease.value.read()); assertIs<ResourceReleaseDispatch.Idle>(pool.nextRelease(ResourceLane.GPU))
        assertEquals(4L, pool.borrowedBytes); assertEquals(4L, pool.retiredBytes)
        assertEquals(LeaseReturn.RETURNED, pool.returnLease(lease))
        assertFailsWith<IllegalStateException> { lease.value }
        val ticket = release(pool); assertEquals(4L, pool.chargedBytes)
        pool.completeRelease(ticket, ResourceReleaseOutcome.RELEASED)
        assertEquals(0L, pool.chargedBytes); audit(pool)
    }

    @Test fun multipleReadersCountUniqueBytesAndAllMustReturn() {
        val pool = pool(); publish(pool, "a")
        val a = borrow(pool, "a"); val b = borrow(pool, "a"); pool.close()
        assertEquals(4L, pool.borrowedBytes); assertEquals(2, pool.outstandingLeases)
        pool.returnLease(a); assertIs<ResourceReleaseDispatch.Idle>(pool.nextRelease(ResourceLane.GPU))
        pool.returnLease(b); assertIs<ResourceReleaseDispatch.Ready<FakeGpuResource>>(pool.nextRelease(ResourceLane.GPU))
        audit(pool)
    }

    @Test fun sameKeyReplacementRetainsOldGenerationUntilItsOwnLeaseEnds() {
        val pool = pool(); val old = FakeGpuResource("old"); publish(pool, "key", old)
        val reader = borrow(pool, "key")
        val new = FakeGpuResource("new"); publish(pool, "key", new)
        val newReader = borrow(pool, "key")
        assertEquals("old", reader.value.read()); assertEquals("new", newReader.value.read())
        assertEquals(8L, pool.chargedBytes); assertEquals(4L, pool.retiredBytes)
        pool.returnLease(reader); val oldRelease = release(pool)
        assertTrue(oldRelease.value === old)
        pool.completeRelease(oldRelease, ResourceReleaseOutcome.RELEASED)
        assertEquals("new", newReader.value.read()); assertEquals(4L, pool.chargedBytes)
        pool.returnLease(newReader); audit(pool)
    }

    @Test fun lruBorrowOrderEvictsOldestResidentWithoutFreeingBytesEarly() {
        val pool = pool(total = 12); publish(pool, "a"); publish(pool, "b")
        pool.returnLease(borrow(pool, "a")); publish(pool, "c")
        assertIs<ResourceBorrow.Missing>(pool.borrow("b"))
        assertEquals("b", release(pool).value.read())
        assertEquals(12L, pool.chargedBytes); assertEquals(8L, pool.residentBytes); audit(pool)
    }

    @Test fun terminalCloseRejectsNewWorkButLateAllocationTransfersForRelease() {
        val pool = pool(); val reservation = reserve(pool)
        pool.close(); pool.close()
        assertFalse(pool.isDrained)
        assertIs<ResourceBorrow.Closed>(pool.borrow("a"))
        assertEquals(ReservationFailure.CLOSED, assertIs<ReservationResult.Rejected>(pool.reserve(4)).reason)
        assertIs<ResourcePublication.RetiredAfterClose>(pool.publish(reservation, "a", FakeGpuResource("late")))
        assertEquals(0L, pool.residentBytes); assertEquals(4L, pool.chargedBytes)
        pool.completeRelease(release(pool), ResourceReleaseOutcome.RELEASED)
        assertTrue(pool.isDrained); audit(pool)
    }

    @Test fun closeCannotCancelReservationOfWorkerWhoseAllocationOutcomeIsUnknown() {
        val pool = pool(); val pending = reserve(pool)
        pool.close(); assertEquals(4L, pool.reservedBytes); assertFalse(pool.isDrained)
        assertTrue(pool.cancelUnallocated(pending)) // worker explicitly reports no allocation occurred
        assertTrue(pool.isDrained)
    }

    @Test fun foreignAndDuplicateTokensCannotReleaseAnotherOwnersBudget() {
        val a = pool(); val b = pool(); val reservation = reserve(a)
        assertFalse(b.cancelUnallocated(reservation))
        assertIs<ResourcePublication.StaleReservation>(b.publish(reservation, "x", FakeGpuResource("x")))
        a.publish(reservation, "x", FakeGpuResource("x"))
        val lease = borrow(a, "x")
        assertEquals(LeaseReturn.FOREIGN, b.returnLease(lease)); assertEquals("x", lease.value.read())
        assertEquals(LeaseReturn.RETURNED, a.returnLease(lease)); assertEquals(LeaseReturn.ALREADY_RETURNED, a.returnLease(lease))
        a.close(); val ticket = release(a)
        assertEquals(ResourceCompletion.STALE, b.completeRelease(ticket, ResourceReleaseOutcome.RELEASED))
        assertEquals(ResourceCompletion.UPDATED, a.completeRelease(ticket, ResourceReleaseOutcome.RELEASED))
        assertFailsWith<IllegalStateException> { ticket.value }
        assertEquals(ResourceCompletion.STALE, a.completeRelease(ticket, ResourceReleaseOutcome.RELEASED))
        assertEquals(0L, a.chargedBytes); audit(b)
    }

    @Test fun duplicateRawResourceCannotBeRegisteredUnderTwoOwnersInSamePool() {
        val pool = pool(); val value = FakeGpuResource("same"); publish(pool, "a", value)
        val pending = reserve(pool)
        assertIs<ResourcePublication.AlreadyOwned>(pool.publish(pending, "b", value))
        assertEquals(4L, pool.reservedBytes); assertEquals(4L, pool.residentBytes)
        assertTrue(pool.cancelUnallocated(pending)); audit(pool)
    }

    @Test fun failedDestructionKeepsBudgetAndUsesFreshAttemptForExplicitRetry() {
        val pool = pool(); publish(pool, "a"); pool.close()
        val first = release(pool)
        assertIs<ResourceReleaseDispatch.Idle>(pool.nextRelease(ResourceLane.GPU))
        pool.completeRelease(first, ResourceReleaseOutcome.NOT_RELEASED)
        assertEquals(4L, pool.chargedBytes); val second = release(pool)
        assertTrue(first !== second)
        assertEquals(ResourceCompletion.STALE, pool.completeRelease(first, ResourceReleaseOutcome.RELEASED))
        assertEquals("a", second.value.read())
        pool.completeRelease(second, ResourceReleaseOutcome.RELEASED); assertTrue(pool.isDrained)
    }

    @Test fun unknownDestructionQuarantinesUntilExplicitBackendReconciliation() {
        val pool = pool(); val handle = publish(pool, "a"); pool.close()
        pool.completeRelease(release(pool), ResourceReleaseOutcome.UNKNOWN)
        assertEquals(4L, pool.chargedBytes); assertFalse(pool.isDrained)
        assertIs<ResourceReleaseDispatch.Idle>(pool.nextRelease(ResourceLane.GPU))
        assertEquals(ResourceCompletion.STALE, pool.resolveUnknown(handle, ResourceReleaseOutcome.UNKNOWN))
        assertEquals(ResourceCompletion.UPDATED, pool.resolveUnknown(handle, ResourceReleaseOutcome.RELEASED))
        assertTrue(pool.isDrained)
    }

    @Test fun reconciliationThatResourceStillLivesAllowsAnExplicitNewRelease() {
        val pool = pool(); val handle = publish(pool, "a"); pool.close()
        pool.completeRelease(release(pool), ResourceReleaseOutcome.UNKNOWN)
        pool.resolveUnknown(handle, ResourceReleaseOutcome.NOT_RELEASED)
        assertEquals(4L, pool.chargedBytes)
        pool.completeRelease(release(pool), ResourceReleaseOutcome.RELEASED)
        assertTrue(pool.isDrained)
    }

    @Test fun laneAndMetadataLimitsFailWithoutMutation() {
        val pool = pool(count = 1, leases = 1); publish(pool, "a")
        assertEquals(ReservationFailure.RESOURCE_COUNT, assertIs<ReservationResult.Rejected>(pool.reserve(1)).reason)
        val reader = borrow(pool, "a")
        assertIs<ResourceBorrow.LeaseLimit>(pool.borrow("a"))
        pool.close(); pool.returnLease(reader)
        assertIs<ResourceReleaseDispatch.WrongLane>(pool.nextRelease(ResourceLane.WORKER))
        assertEquals(4L, pool.chargedBytes); audit(pool)
    }

    @Test fun subtractionBasedAdmissionDoesNotOverflowLongBudget() {
        val pool = pool(resident = Long.MAX_VALUE, total = Long.MAX_VALUE)
        val pending = reserve(pool, Long.MAX_VALUE - 1)
        assertEquals(ReservationFailure.TOTAL_LIMIT, assertIs<ReservationResult.Rejected>(pool.reserve(2)).reason)
        assertEquals(ReservationFailure.INVALID_BYTES, assertIs<ReservationResult.Rejected>(pool.reserve(0)).reason)
        assertTrue(pool.cancelUnallocated(pending)); assertEquals(0L, pool.chargedBytes)
    }
}
