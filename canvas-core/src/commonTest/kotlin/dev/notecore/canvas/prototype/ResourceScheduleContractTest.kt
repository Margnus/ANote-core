package dev.notecore.canvas.prototype

import kotlin.test.*

class ResourceScheduleContractTest {
    private fun publish(pool: ResourceLeasePool<String, FakeGpuResource>, value: FakeGpuResource) {
        val reservation = assertIs<ReservationResult.Reserved>(pool.reserve(4)).reservation
        assertIs<ResourcePublication.Resident>(pool.publish(reservation, "tile", value))
    }
    private fun ticket(pool: ResourceLeasePool<String, FakeGpuResource>) =
        assertIs<ResourceReleaseDispatch.Ready<FakeGpuResource>>(pool.nextRelease(ResourceLane.GPU)).ticket

    @Test fun detachBeforeGpuFenceKeepsReaderAliveAndBlocksLateFramePublication() {
        val scheduler = ControlledResourceScheduler()
        val pool = ResourceLeasePool<String, FakeGpuResource>(4, 8)
        val resource = FakeGpuResource("tile"); publish(pool, resource)
        val reader = assertIs<ResourceBorrow.Borrowed<FakeGpuResource>>(pool.borrow("tile")).lease
        val frame = FrameToken(SceneId("page"), 1, 0, 1, 1, 1)
        val publication = FramePublicationGate(frame)
        var displayed = false
        assertTrue(scheduler.post("fence", ResourceLane.GPU) {
            assertEquals("tile", reader.value.read())
            assertTrue(scheduler.post("fence-ack", ResourceLane.OWNER) {
                displayed = publication.canPublish(frame)
                pool.returnLease(reader)
            })
        })
        assertTrue(scheduler.post("detach", ResourceLane.OWNER) { publication.close(); pool.close() })
        scheduler.run("detach")
        assertIs<ResourceReleaseDispatch.Idle>(pool.nextRelease(ResourceLane.GPU))
        scheduler.run("fence"); assertFalse(resource.destroyed)
        scheduler.run("fence-ack"); assertFalse(displayed)
        val release = ticket(pool)
        assertTrue(scheduler.post("destroy", ResourceLane.GPU) {
            release.value.destroy(scheduler)
            assertTrue(scheduler.post("destroy-ack", ResourceLane.OWNER) {
                pool.completeRelease(release, ResourceReleaseOutcome.RELEASED)
            })
        })
        scheduler.run("destroy")
        assertEquals(4L, pool.chargedBytes); assertFalse(pool.isDrained) // destruction ack still in flight
        scheduler.run("destroy-ack")
        assertTrue(pool.isDrained); assertEquals(1, resource.destructions); assertEquals(0, scheduler.queued)
    }

    @Test fun fenceBeforeDetachAndDetachBeforeFenceBothDestroyExactlyOnce() {
        for (detachFirst in listOf(false, true)) {
            val scheduler = ControlledResourceScheduler()
            val pool = ResourceLeasePool<String, FakeGpuResource>(4, 8)
            val resource = FakeGpuResource("tile"); publish(pool, resource)
            val reader = assertIs<ResourceBorrow.Borrowed<FakeGpuResource>>(pool.borrow("tile")).lease
            scheduler.post("return", ResourceLane.OWNER) { pool.returnLease(reader) }
            scheduler.post("detach", ResourceLane.OWNER) { pool.close() }
            if (detachFirst) { scheduler.run("detach"); scheduler.run("return") }
            else { scheduler.run("return"); scheduler.run("detach") }
            val release = ticket(pool)
            scheduler.post("release", ResourceLane.GPU) {
                release.value.destroy(scheduler)
                scheduler.post("ack", ResourceLane.OWNER) { pool.completeRelease(release, ResourceReleaseOutcome.RELEASED) }
            }
            scheduler.run("release"); scheduler.run("ack")
            assertEquals(1, resource.destructions); assertTrue(pool.isDrained)
        }
    }

    @Test fun fullExecutorReturnsUnexecutedTicketWithoutFreeingBudgetOrResource() {
        val scheduler = ControlledResourceScheduler(maxTasks = 1)
        val pool = ResourceLeasePool<String, FakeGpuResource>(4, 8)
        val resource = FakeGpuResource("tile"); publish(pool, resource); pool.close()
        scheduler.post("occupied", ResourceLane.WORKER) { }
        val first = ticket(pool)
        assertFalse(scheduler.post("destroy", ResourceLane.GPU) { first.value.destroy(scheduler) })
        assertEquals(ResourceCompletion.UPDATED, pool.completeRelease(first, ResourceReleaseOutcome.NOT_RELEASED))
        assertFalse(resource.destroyed); assertEquals(4L, pool.chargedBytes)
        scheduler.run("occupied")
        val second = ticket(pool)
        scheduler.post("destroy", ResourceLane.GPU) {
            second.value.destroy(scheduler)
            scheduler.post("ack", ResourceLane.OWNER) { pool.completeRelease(second, ResourceReleaseOutcome.RELEASED) }
        }
        scheduler.run("destroy"); scheduler.run("ack")
        assertTrue(pool.isDrained); assertEquals(1, resource.destructions)
    }

    @Test fun destructorLaneFailureIsDefinitiveAndRetryRunsOnGpuLane() {
        val scheduler = ControlledResourceScheduler()
        val pool = ResourceLeasePool<String, FakeGpuResource>(4, 8)
        val resource = FakeGpuResource("tile"); publish(pool, resource); pool.close()
        val first = ticket(pool)
        scheduler.post("bad", ResourceLane.WORKER) {
            assertFailsWith<IllegalStateException> { first.value.destroy(scheduler) }
            scheduler.post("failure", ResourceLane.OWNER) { pool.completeRelease(first, ResourceReleaseOutcome.NOT_RELEASED) }
        }
        scheduler.run("bad"); scheduler.run("failure")
        assertFalse(resource.destroyed); assertEquals(4L, pool.chargedBytes)
        val retry = ticket(pool)
        scheduler.post("good", ResourceLane.GPU) {
            retry.value.destroy(scheduler)
            scheduler.post("ack", ResourceLane.OWNER) { pool.completeRelease(retry, ResourceReleaseOutcome.RELEASED) }
        }
        scheduler.run("good"); scheduler.run("ack")
        assertTrue(pool.isDrained)
    }

    @Test fun destructionWithLostAckNeverAutomaticallyRunsDestructorTwice() {
        val scheduler = ControlledResourceScheduler()
        val pool = ResourceLeasePool<String, FakeGpuResource>(4, 8)
        val resource = FakeGpuResource("tile")
        val reservation = assertIs<ReservationResult.Reserved>(pool.reserve(4)).reservation
        val handle = assertIs<ResourcePublication.Resident>(pool.publish(reservation, "tile", resource)).handle
        pool.close(); val release = ticket(pool)
        scheduler.post("destroy", ResourceLane.GPU) { release.value.destroy(scheduler) } // acknowledgement lost
        scheduler.run("destroy")
        pool.completeRelease(release, ResourceReleaseOutcome.UNKNOWN)
        assertEquals(1, resource.destructions); assertEquals(4L, pool.chargedBytes)
        assertIs<ResourceReleaseDispatch.Idle>(pool.nextRelease(ResourceLane.GPU))
        scheduler.post("query", ResourceLane.GPU) {
            assertTrue(resource.destroyed)
            scheduler.post("reconcile", ResourceLane.OWNER) { pool.resolveUnknown(handle, ResourceReleaseOutcome.RELEASED) }
        }
        scheduler.run("query"); scheduler.run("reconcile")
        assertEquals(ResourceCompletion.STALE, pool.completeRelease(release, ResourceReleaseOutcome.RELEASED))
        assertEquals(1, resource.destructions); assertTrue(pool.isDrained)
    }
}
