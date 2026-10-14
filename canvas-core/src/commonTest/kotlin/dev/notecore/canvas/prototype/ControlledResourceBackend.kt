package dev.notecore.canvas.prototype

/** Finite test-only executor. Lane labels are modeled on one real thread. */
internal class ControlledResourceScheduler(private val maxTasks: Int = 64) {
    private data class Job(val lane: ResourceLane, val action: () -> Unit)
    private val jobs = linkedMapOf<String, Job>()
    var currentLane: ResourceLane? = null; private set
    val queued: Int get() = jobs.size
    fun post(id: String, lane: ResourceLane, action: () -> Unit): Boolean {
        require(id !in jobs)
        if (jobs.size >= maxTasks) return false
        jobs[id] = Job(lane, action); return true
    }
    fun run(id: String) {
        val job = checkNotNull(jobs.remove(id))
        check(currentLane == null)
        currentLane = job.lane
        try { job.action() } finally { currentLane = null }
    }
}

internal class FakeGpuResource(val name: String) {
    var destroyed = false; private set
    var destructions = 0; private set
    fun read(): String { check(!destroyed) { "Use after destruction" }; return name }
    fun destroy(scheduler: ControlledResourceScheduler) {
        check(scheduler.currentLane == ResourceLane.GPU) { "Wrong destruction lane" }
        check(!destroyed) { "Double destruction" }
        destroyed = true; destructions++
    }
}
