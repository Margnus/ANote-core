package dev.notecore.probes

import dev.notecore.canvas.prototype.*
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

fun main() {
    val witnesses = AtomicReference<List<WeakReference<Any>>>()
    val ready = CountDownLatch(1)
    val owner = Thread({
        val executor = BoundedOwnerExecutor(1, 65536, "OwnerRetentionRoot-worker")
        val input = ByteArray(32768) { 1 }
        val output = ByteArray(32768) { 2 }
        witnesses.set(listOf(WeakReference(executor), WeakReference(input), WeakReference(output)))
        check(executor.submit(input, 65536, { output }) { _, _ -> error("No surviving owner may ACK") } is WorkAdmission.Accepted)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (executor.status().entries.single().stage != OwnedWorkStage.READY) {
            check(System.nanoTime() < deadline); Thread.sleep(1)
        }
        check(executor.status().chargedBytes == 65536L)
        ready.countDown()
    }, "OwnerRetentionRoot-owner")
    owner.start(); check(ready.await(5, TimeUnit.SECONDS)); owner.join(3000)
    check(owner.state == Thread.State.TERMINATED)
    Thread.sleep(500)
    // GC observation is diagnostic only. Collection never constitutes ACK or resource release.
    repeat(20) { System.gc(); Thread.sleep(50) }
    val refs = witnesses.get()
    val collected = refs.map { it.get() == null }
    val workerAlive = Thread.getAllStackTraces().keys.any { it.name == "OwnerRetentionRoot-worker" && it.isAlive }
    println("RETENTION_ROOT executorCollected=${collected[0]} inputCollected=${collected[1]} resultCollected=${collected[2]} workerAlive=$workerAlive ackCount=0 chargeBeforeOwnerExit=65536")
}
