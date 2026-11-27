package dev.notecore.canvas.prototype

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Offscreen owner/worker trace. A pulse is one admitted tile and its eventual ACK, not UI vsync. */
internal object S2StagedFrameProbe {
    data class TileResult(val pixels: BrushTilePixelsPoc, val work: Long)
    private data class Complete(val token: FrameToken, val bytes: ByteArray)
    private data class Staging(val token: FrameToken, val bytes: ByteArray, var tiles: Int = 0)
    private data class Job(val token: FrameToken, val slot: Int, val workLimit: Long)
    private data class Receipt(val job: Job, val pulseWork: Long?)
    private sealed interface Result {
        data class Ready(val tile: TileResult) : Result
        data object Rejected : Result
    }

    fun verify(
        old: List<BrushTilePixelsPoc>,
        current: List<BrushTilePixelsPoc>,
        prefixBytes: Long,
        workLimit: Long,
        workingBytes: Long,
        render: (frame: FrameToken, slot: Int, workLimit: Long) -> TileResult?,
    ) {
        val tileBytes = old.first().logicalBytes.toInt()
        check(old.size == current.size && old.zip(current).all { (a, b) ->
            a.grid == b.grid && a.logicalBytes == tileBytes.toLong() && b.logicalBytes == a.logicalBytes
        })
        val atlasBytes = old.size * tileBytes
        val tilePeak = tileBytes + workingBytes
        val budget = prefixBytes + 2L * atlasBytes + tilePeak
        // Wake events carry no payload and can be coalesced.
        val wake = LinkedBlockingQueue<Unit>(1)
        val executor = BoundedOwnerExecutor(1, budget, "s2-visible-worker") { wake.offer(Unit) }
        val initial = old.first().frame
        val gate = FramePublicationGate(initial)
        fun copy(pixels: BrushTilePixelsPoc, target: ByteArray, offset: Int) {
            repeat(tileBytes) { i -> target[offset + i] = pixels.byteAt(i).toByte() }
        }
        fun matches(bytes: ByteArray, oracle: List<BrushTilePixelsPoc>): Boolean =
            oracle.withIndex().all { (slot, tile) ->
                (0 until tileBytes).all { tile.byteAt(it) == (bytes[slot * tileBytes + it].toInt() and 255) }
            }

        check(executor.trySetRetainedOwnerBytes(prefixBytes + atlasBytes))
        var visible = Complete(initial, ByteArray(atlasBytes))
        old.forEachIndexed { slot, tile -> copy(tile, visible.bytes, slot * tileBytes) }
        var held: Complete? = null
        var stage: Staging? = null
        var receipt: Receipt? = null
        var blockDelivery = false
        var discarded = 0
        var failed = 0
        var publications = 0
        var peak = 0L
        var maxPulse = 0L
        var currentWork = 0L
        var currentPulses = 0
        fun charge(extraAtlas: Boolean = false) {
            val bytes = prefixBytes + visible.bytes.size + (held?.bytes?.size ?: 0) +
                (stage?.bytes?.size ?: 0) + if (extraAtlas) atlasBytes else 0
            check(executor.trySetRetainedOwnerBytes(bytes))
            peak = maxOf(peak, budget - executor.availableBytes())
        }
        fun begin(sequence: Long) {
            val token = initial.copy(requestSequence = sequence)
            check(gate.expect(token))
            stage = null
            charge(extraAtlas = true)
            stage = Staging(token, ByteArray(atlasBytes))
        }
        fun dispatch(limit: Long = workLimit): OwnerWorkTicket {
            val pending = checkNotNull(stage)
            val input = Job(pending.token, pending.tiles, limit)
            val admitted = executor.submit(input, tilePeak, compute = { job ->
                executor.checkWorkerThread()
                val output = render(job.token, job.slot, job.workLimit - tileBytes)
                if (output == null) Result.Rejected else Result.Ready(output)
            }, receive = { job, result ->
                if (blockDelivery) ResultDisposition.RETRY_REQUIRED else {
                    check(receipt == null)
                    val output = (result as? WorkResult.Succeeded)?.value
                        ?: error("Unexpected executor failure: $result")
                    val work = if (gate.canPublish(job.token) && output is Result.Ready) {
                        val target = checkNotNull(stage)
                        val pixels = output.tile.pixels
                        check(job.token == target.token && job.slot == target.tiles)
                        check(pixels.frame == job.token && pixels.grid == old[job.slot].grid &&
                            pixels.logicalBytes == tileBytes.toLong())
                        check(executor.status().chargedBytes == tilePeak)
                        val pulseWork = output.tile.work + tileBytes
                        check(pulseWork <= job.workLimit)
                        // Copy while the executor owns the output; no pixel reference survives ACK.
                        copy(pixels, target.bytes, job.slot * tileBytes)
                        pulseWork
                    } else null
                    receipt = Receipt(job, work)
                    ResultDisposition.ACKNOWLEDGED
                }
            })
            peak = maxOf(peak, budget - executor.availableBytes())
            return (admitted as WorkAdmission.Accepted).ticket
        }
        fun waitReady() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (executor.status().entries.single().stage != OwnedWorkStage.READY) {
                val remaining = deadline - System.nanoTime()
                check(remaining > 0 && wake.poll(remaining, TimeUnit.NANOSECONDS) != null) { "S2 tile timed out" }
            }
        }
        fun collect() {
            val drained = executor.drainResults(limit = 1)
            check(drained.acknowledged == 1 && drained.blocked == null)
            executor.checkOutsideDrain()
            val (job, pulseWork) = checkNotNull(receipt)
            receipt = null
            check(executor.status().entries.isEmpty())
            if (!gate.canPublish(job.token)) {
                discarded++
                return
            }
            val target = checkNotNull(stage)
            check(job.token == target.token && job.slot == target.tiles)
            if (pulseWork == null) {
                stage = null
                charge()
                failed++
                return
            }
            maxPulse = maxOf(maxPulse, pulseWork)
            if (job.token.requestSequence == 5L) {
                currentWork += pulseWork
                currentPulses++
            }
            target.tiles++
            if (target.tiles == old.size) {
                check(held == null)
                held = visible
                visible = Complete(target.token, target.bytes)
                stage = null
                charge()
                publications++
            }
        }
        fun pulse() {
            dispatch()
            waitReady()
            collect()
        }
        try {
            begin(4)
            check(executor.submit(tilePeak + 1, compute = { error("Over-budget work ran") },
                receive = { ResultDisposition.ACKNOWLEDGED }) ==
                WorkAdmission.Rejected(WorkAdmissionFailure.BYTE_CAPACITY))
            pulse()
            check(stage?.tiles == 1 && visible.token == initial && matches(visible.bytes, old))
            val staleTicket = dispatch()
            waitReady()
            blockDelivery = true
            check(executor.drainResults(limit = 1).blocked === staleTicket)
            check(executor.availableBytes() == 0L && executor.status().chargedBytes == tilePeak)
            begin(5)
            check(executor.submit(1, compute = { error("Second tile ran") },
                receive = { ResultDisposition.ACKNOWLEDGED }) ==
                WorkAdmission.Rejected(WorkAdmissionFailure.ENTRY_CAPACITY))
            blockDelivery = false
            check(executor.retryDelivery(staleTicket))
            collect()
            check(discarded == 1 && stage?.tiles == 0 && visible.token == initial && publications == 0)
            repeat(old.size - 1) {
                pulse()
                check(visible.token == initial && matches(visible.bytes, old) && publications == 0)
            }
            pulse()
            check(visible.token.requestSequence == 5L && matches(visible.bytes, current) && publications == 1)
            check(matches(checkNotNull(held).bytes, old))
            held = null
            charge()

            begin(6)
            pulse()
            check(stage?.tiles == 1)
            dispatch(limit = tileBytes.toLong())
            waitReady()
            collect()
            check(stage == null && failed == 1 && publications == 1)
            check(visible.token.requestSequence == 5L && matches(visible.bytes, current))
            check(peak == budget && currentPulses == old.size && currentWork > workLimit)
            println("S2 staged tiles=${old.size} currentPulses=$currentPulses currentWork=$currentWork " +
                "maxPulseWork=$maxPulse pulseLimit=$workLimit peakDeclaredBytes=$peak " +
                "prefixBytes=$prefixBytes atlasBytes=$atlasBytes obsolete=$discarded failed=$failed " +
                "publications=$publications coldByteChecks=$atlasBytes")
        } finally {
            gate.close()
            executor.stopAccepting()
            check(executor.awaitWorkerStop(30_000))
        }
    }
}
