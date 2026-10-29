package dev.notecore.app

import android.app.Application
import android.os.Handler
import android.os.Looper
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import dev.notecore.document.PreviewRecoveryCodec
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import kotlin.test.*

/** The Android Store posts to main; accepting another creator thread cannot provide a valid owner. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class StoreOwnerAdmissionTest {
    @Test fun delayedCaptureCannotOverwriteCurrentAuthorityAfterLatestWriteAcknowledged() {
        val context: Application = RuntimeEnvironment.getApplication()
        val directory = Files.createTempDirectory("store-late-capture-").toFile()
        val writes = AtomicInteger()
        val store = NoteStore.isolated(context, directory, observeWriteBoundary = {
            if (it == PreviewWriteBoundary.VERIFIED_BEFORE_RETURN) writes.incrementAndGet()
        })
        fun until(done: () -> Boolean) {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!done() && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5)
            }
            assertTrue(done(), "Original worker must deliver the actual result")
        }
        try {
            val note = store.create()
            assertTrue(note.rename("older capture")); val older = note.captureRecovery()
            var saved = false
            assertTrue(store.save(older) { _, failure -> assertNull(failure); saved = true })
            until { saved && store.ownerRetirementStatus().pendingWrites == 0 }
            assertTrue(note.rename("newer confirmed")); val newest = note.captureRecovery()
            saved = false
            assertTrue(store.save(newest) { _, failure -> assertNull(failure); saved = true })
            until { saved && store.ownerRetirementStatus().pendingWrites == 0 }
            val file = directory.resolve(note.documentId+".ncp")
            val confirmed = file.readBytes(); val root = note.snapshot(); val history = note.authorityStatus()
            val writesBefore = writes.get()
            var delivered = false; var refused: String? = null
            val admitted = store.save(older) { _, failure -> refused = failure; delivered = true }
            until { delivered && store.ownerRetirementStatus().pendingWrites == 0 }
            val disk = assertNotNull(PreviewRecoveryCodec.read(file.readText(Charsets.UTF_8)).snapshot)
            assertFalse(admitted, "Late capture admitted after ledger drained; disk title=${disk.title}, active title=${note.title}")
            assertNotNull(refused); assertEquals(writesBefore, writes.get())
            assertContentEquals(confirmed, file.readBytes()); assertSame(root, note.snapshot()); assertEquals(history, note.authorityStatus())
            // Same revision but different bytes came from a pure immutable reader, not another mutable authority.
            val fork = assertNotNull(PreviewRecoveryCodec.read(newest.encode().replace("newer confirmed", "foreign revision")).snapshot)
            assertEquals(newest.version, fork.version)
            refused = null
            assertFalse(store.save(fork) { _, failure -> refused = failure })
            assertNotNull(refused); assertEquals(writesBefore, writes.get()); assertContentEquals(confirmed, file.readBytes())
            assertTrue(note.rename("owner continues")); saved = false
            assertTrue(store.save(note.captureRecovery()) { _, failure -> assertNull(failure); saved = true })
            until { saved && store.ownerRetirementStatus().pendingWrites == 0 }
            assertEquals("owner continues", assertNotNull(PreviewRecoveryCodec.read(file.readText(Charsets.UTF_8)).snapshot).title)
        } finally {
            store.stopTestBackend()
            until { store.ownerRetirementStatus().workerStopped }
            directory.deleteRecursively()
        }
    }
    @Test fun invalidConfigurationDoesNotLeaveAnUnownedStorageWorker() {
        val context: Application = RuntimeEnvironment.getApplication()
        val directory = Files.createTempDirectory("store-invalid-budget-").toFile()
        val before = Thread.getAllStackTraces().keys.toSet()
        var created = emptyList<Thread>()
        try {
            assertFailsWith<IllegalArgumentException> {
                NoteStore.isolated(context, directory, exportPickerByteLimit = -1)
            }
            assertFailsWith<IllegalArgumentException> {
                NoteStore.isolated(context, directory, catalogResultByteLimit = -1)
            }
            created = Thread.getAllStackTraces().keys.filter { it !in before && it.name == "NoteCore-preview-storage" }
            assertTrue(created.isEmpty(), "Rejected configuration started a worker without returning its owner")
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally {
            // Only this rejected fixture's new empty worker can exist before the fix; never touch other workers.
            created.forEach { it.interrupt(); it.join(5_000); check(!it.isAlive) }
            directory.deleteRecursively()
        }
    }
    @Test fun backgroundSingletonLookupRefusesAndPreservesTheMainOwner() {
        val context: Application = RuntimeEnvironment.getApplication()
        val directory = Files.createTempDirectory("store-main-owner-").toFile()
        val singleton = NoteStore::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previous = singleton.get(null)
        val store = NoteStore.isolated(context, directory)
        val failure = AtomicReference<Throwable?>()
        try {
            singleton.set(null, store)
            val caller = Thread { try { NoteStore.get(context) } catch (error: Throwable) { failure.set(error) } }
            caller.start(); caller.join(5_000); assertFalse(caller.isAlive)
            assertIs<IllegalStateException>(failure.get())
            assertSame(store, NoteStore.get(context))
            assertEquals(StoreOwnerPhase.ACTIVE, store.ownerRetirementStatus().phase)
            assertNotNull(store.create())
        } finally {
            singleton.set(null, previous)
            store.stopTestBackend()
            val until = System.nanoTime() + 5_000_000_000L
            while (!store.ownerRetirementStatus().workerStopped && System.nanoTime() < until) {
                shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5)
            }
            assertTrue(store.ownerRetirementStatus().workerStopped)
            directory.deleteRecursively()
        }
    }
    @Test fun backgroundConstructionRefusesBeforeCreatingAnExecutor() {
        val context: Application = RuntimeEnvironment.getApplication()
        val directory = Files.createTempDirectory("store-owner-admission-").toFile()
        val failure = AtomicReference<Throwable?>()
        val accepted = AtomicReference<NoteStore?>()
        val thread = Thread {
            try { accepted.set(NoteStore.isolated(context, directory)) }
            catch (error: Throwable) { failure.set(error) }
            finally {
                // Cleanup the pre-fix counterexample on its original owner without running its invalid main pump.
                accepted.get()?.let { store ->
                    store.stopTestBackend()
                    val until = System.nanoTime() + 5_000_000_000L
                    while (!store.ownerRetirementStatus().workerStopped && System.nanoTime() < until) Thread.sleep(5)
                    check(store.ownerRetirementStatus().workerStopped)
                    (NoteStore::class.java.getDeclaredField("main").apply { isAccessible = true }.get(store) as Handler)
                        .removeCallbacksAndMessages(null)
                }
            }
        }
        try {
            thread.start(); thread.join(10_000)
            assertFalse(thread.isAlive)
            assertIs<IllegalStateException>(failure.get(), "Store must reject the wrong creator before worker allocation")
            assertNull(accepted.get())
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally { if (!thread.isAlive) directory.deleteRecursively() }
    }
}
