package dev.notecore.probes

import dev.notecore.canvas.prototype.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).let {
    it.isAccessible = true; it.get(target)
}

/** Does not transfer owner, ACK a result, clear a charge, or mutate executor internals. */
fun main() {
    val deadWorkers = mutableListOf<Thread>()
    for (withTasks in listOf(false, true)) {
        val reference = AtomicReference<BoundedOwnerExecutor>()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val computations = AtomicInteger()
        val deliveries = AtomicInteger()
        val inputs = listOf(ByteArray(4) { 1 }, ByteArray(4) { 2 })
        val results = listOf(ByteArray(4) { 3 }, ByteArray(4) { 4 })
        val owner = Thread({
            val executor = BoundedOwnerExecutor(2, 16, "OwnerStop-original-worker")
            reference.set(executor)
            if (withTasks) {
                for (i in inputs.indices) {
                    check(executor.submit(inputs[i], 8, {
                        if (i == 0) { started.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
                        computations.incrementAndGet(); results[i]
                    }) { _, _ -> deliveries.incrementAndGet(); ResultDisposition.ACKNOWLEDGED } is WorkAdmission.Accepted)
                }
                check(started.await(5, TimeUnit.SECONDS))
            }
        }, "OwnerStop-original-owner")
        owner.start(); owner.join(5000); check(owner.state == Thread.State.TERMINATED)
        val executor = reference.get()
        val worker = field(executor, "worker") as Thread
        release.countDown()
        worker.join(3000)
        deadWorkers.add(worker)
        val stopped = !worker.isAlive
        synchronized(field(executor, "monitor")!!) {
            val entries = field(executor, "entries") as Map<*, *>
            check(entries.size == if (withTasks) 2 else 0)
            check(field(executor, "chargedBytes") == if (withTasks) 16L else 0L)
            check(computations.get() == if (withTasks) 2 else 0)
            check(deliveries.get() == 0 && field(executor, "workerFailure") == null)
            entries.values.forEachIndexed { i, value ->
                val job = value!!
                val stage = job.javaClass.superclass.getDeclaredField("stage").let { it.isAccessible = true; it.get(job) }
                check(stage == OwnedWorkStage.READY)
                check(field(job, "input") === inputs[i])
                check((field(job, "result") as WorkResult.Succeeded<*>).value === results[i])
            }
            if (stopped) check(field(executor, "accepting") == false && field(executor, "workerStopped") == true)
        }
        try { executor.drainResults(); error("Surviving thread acquired owner") }
        catch (failure: IllegalStateException) { check(failure.message == "Operation requires executor owner thread") }
        println("CASE withTasks=$withTasks stopped=$stopped computed=${computations.get()} delivered=${deliveries.get()} retained=true foreignOwnerRefused=true")
    }
    // A live but idle owner, including a failed wake, must not be treated as dead.
    val live = BoundedOwnerExecutor(requestOwnerWake = { error("Wake delivery unavailable") })
    live.submit(4, { 42 }) { ResultDisposition.ACKNOWLEDGED }
    Thread.sleep(750)
    check(live.status().accepting && !live.status().workerStopped && live.status().chargedBytes == 4L)
    live.stopAccepting(); check(live.awaitWorkerStop(3000))
    check(live.drainResults().acknowledged == 1 && live.status().isDrained)
    println("CASE liveOwner=true wakeFailure=true idleRetained=true normalShutdown=true")
    check(deadWorkers.none { it.isAlive }) { "Terminated creator left original workers alive" }
    println("OWNER_THREAD_STOP_PASSED")
}
