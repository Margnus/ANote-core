@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.notecore.document

import platform.Foundation.NSCondition
import platform.Foundation.NSDate
import platform.Foundation.NSThread
import platform.posix.usleep
import kotlin.test.*

/** Actual Native threads, test-only synchronization; no App worker or durable receipt. */
class DocumentOwnerNativeTest {
    private fun guard(): () -> Unit {
        val owner = NSThread.currentThread
        return { check(NSThread.currentThread.isEqual(owner)) { "Foreign preview owner" } }
    }

    private fun <T> onOwner(action: () -> T): T {
        val condition = NSCondition()
        var done = false
        var outcome: Result<T>? = null
        val deadline = NSDate().timeIntervalSinceReferenceDate + 10.0
        val caller = NSThread.currentThread
        val worker = NSThread {
            val result = runCatching {
                assertFalse(NSThread.currentThread.isEqual(caller))
                action()
            }
            condition.lock()
            try { outcome = result; done = true; condition.broadcast() }
            finally { condition.unlock() }
        }
        worker.name = "NoteCore.document.test.owner"
        worker.start()
        condition.lock()
        val result = try {
            while (!done) check(condition.waitUntilDate(NSDate(timeIntervalSinceReferenceDate = deadline)))
            checkNotNull(outcome)
        } finally { condition.unlock() }
        while (!worker.isFinished() && NSDate().timeIntervalSinceReferenceDate < deadline) usleep(1000u)
        assertTrue(worker.isFinished(), "Previous owner thread must finish before a new owner starts")
        return result.getOrThrow()
    }

    @Test fun foreignNativeThreadCannotReadOrMutateAndOriginalOwnerCanContinue() {
        val note = assertNotNull(PagedPreviewNotebook.createValidated(
            "native-owner", "title", "first", ownerCheck = guard()).notebook)
        val root = note.snapshot()
        onOwner {
            assertFailsWith<IllegalStateException> { note.snapshot() }
            assertFailsWith<IllegalStateException> { note.captureRecovery() }
            assertFailsWith<IllegalStateException> { note.edit(PreviewEdit.Rename("foreign")) }
            assertFailsWith<IllegalStateException> { note.beginStroke(1.0, 2.0, .5f, 1) }
        }
        assertSame(root, note.snapshot())
        assertFalse(note.hasActiveStroke)
        assertTrue(note.edit(PreviewEdit.Rename("owner")).accepted)
        assertEquals("owner", note.title)
        assertTrue(note.retire())
    }

    @Test fun retiredNativeOwnerHandsOffImmutableCheckpointToFreshOwnerOnly() {
        val handed = onOwner {
            val note = assertNotNull(PagedPreviewNotebook.createValidated(
                "native-handoff", "title", "old", ownerCheck = guard()).notebook)
            assertTrue(note.beginStroke(1.0, 2.0, .25f, 1))
            assertTrue(note.endStroke(3.0, 4.0, .75f, 2))
            val checkpoint = note.captureRecovery()
            assertTrue(note.retire())
            assertEquals(PreviewFailureKind.CLOSED, note.edit(PreviewEdit.Rename("late")).failure?.kind)
            assertEquals(0L, note.authorityStatus().journalByteCharge)
            note to checkpoint
        }
        val checkpoint = handed.second
        val encoded = checkpoint.encode()
        assertFailsWith<IllegalStateException> { handed.first.snapshot() }
        assertFailsWith<IllegalStateException> { handed.first.retryPendingStroke() }
        val result = onOwner {
            val note = assertNotNull(PagedPreviewNotebook.openRecovery(
                checkpoint, "new", ownerCheck = guard()).notebook)
            assertSame(checkpoint.root, note.snapshot())
            assertEquals(encoded, note.captureRecovery().encode())
            assertEquals(0, note.authorityStatus().undoCount)
            assertTrue(note.beginStroke(5.0, 6.0, .5f, 3))
            assertTrue(note.endStroke(7.0, 8.0, .5f, 4))
            assertEquals(2, note.totalStrokeCount)
            assertEquals(1, note.authorityStatus().undoCount)
            val result = note.captureRecovery()
            assertTrue(note.retire())
            result
        }
        assertEquals(encoded, checkpoint.encode())
        assertEquals(1, checkpoint.root.scene.objectCount)
        assertEquals(2, result.root.scene.objectCount)
        assertSame(checkpoint.root.scene.find(checkpoint.root.scene.objects().first().id),
            result.root.scene.find(checkpoint.root.scene.objects().first().id))
        assertEquals(2, assertNotNull(PreviewRecoveryCodec.read(result.encode()).snapshot).root.scene.objectCount)
    }
}
