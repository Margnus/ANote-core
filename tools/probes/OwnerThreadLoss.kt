package dev.notecore.probes

import dev.notecore.canvas.prototype.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Deliberately unsupported lifecycle, isolated by the parent process. No reflective mutation. */
fun main(args: Array<String>) {
    val receipt = File(args.single())
    val executorRef = AtomicReference<BoundedOwnerExecutor>()
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    val wakes = CountDownLatch(2)
    val runs = AtomicInteger()
    val deliveries = AtomicInteger()
    val inputs = listOf(ByteArray(4) { 1 }, ByteArray(4) { 2 })
    val outputs = listOf(ByteArray(4) { 3 }, ByteArray(4) { 4 })
    val owner = Thread({
        val executor = BoundedOwnerExecutor(2, 16, "OwnerLoss-original-worker", {
            wakes.countDown()
            error("Original owner event loop unavailable")
        })
        executorRef.set(executor)
        for (index in inputs.indices) {
            check(executor.submit(inputs[index], 8, {
                if (index == 0) {
                    started.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
                runs.incrementAndGet()
                outputs[index]
            }) { _, _ -> deliveries.incrementAndGet(); ResultDisposition.ACKNOWLEDGED } is WorkAdmission.Accepted)
        }
        check(started.await(5, TimeUnit.SECONDS))
        // Return for real, without stop/ACK/transfer. Owner loss is not a retirement request.
    }, "OwnerLoss-original-owner")
    owner.start(); owner.join(5000)
    check(!owner.isAlive && owner.state == Thread.State.TERMINATED)
    release.countDown()
    check(wakes.await(5, TimeUnit.SECONDS))
    val executor = executorRef.get()
    fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).let {
        it.isAccessible = true; it.get(target)
    }
    val rejected = mutableListOf<String>()
    for ((name, action) in listOf<Pair<String, () -> Unit>>(
        "status" to { executor.status(); Unit }, "stopAccepting" to { executor.stopAccepting() },
        "drainResults" to { executor.drainResults(); Unit }, "awaitWorkerStop" to { executor.awaitWorkerStop(1); Unit },
        "submit" to { executor.submit(1, { 0 }) { ResultDisposition.ACKNOWLEDGED }; Unit }
    )) {
        try { action(); error("Surviving thread unexpectedly became owner: $name") }
        catch (failure: IllegalStateException) {
            check(failure.message == "Operation requires executor owner thread")
            rejected.add(name)
        }
    }
    val worker = field(executor, "worker") as Thread
    val monitor = field(executor, "monitor")!!
    synchronized(monitor) {
        val entries = field(executor, "entries") as Map<*, *>
        check(entries.size == 2 && field(executor, "chargedBytes") == 16L)
        check(field(executor, "accepting") == true && field(executor, "workerStopped") == false)
        check(field(executor, "workerFailure") == null)
        check(field(executor, "ownerWakeFailure") == "IllegalStateException")
        entries.values.forEachIndexed { index, value ->
            val job = value!!
            val stage = job.javaClass.superclass.getDeclaredField("stage").let { it.isAccessible = true; it.get(job) }
            check(stage == OwnedWorkStage.READY)
            check(field(job, "input") === inputs[index] && field(job, "compute") == null)
            check((field(job, "result") as WorkResult.Succeeded<*>).value === outputs[index])
        }
    }
    check(runs.get() == 2 && deliveries.get() == 0 && worker.isAlive && !worker.isDaemon)
    // Observe main actually returning too. This daemon cannot keep the child process alive.
    val main = Thread.currentThread()
    Thread({
        main.join()
        check(!main.isAlive && worker.isAlive)
        receipt.writeText("""{"owner_terminated":true,"main_terminated":true,"worker_alive":true,"worker_daemon":false,"entries":2,"charged_bytes":16,"retained_input_result_identity":true,"computations":2,"deliveries":0,"survivor_calls_rejected":${rejected.size},"production_state_mutated_by_probe":false}""", Charsets.UTF_8)
    }, "OwnerLoss-observer").apply { isDaemon = true; start() }
}
