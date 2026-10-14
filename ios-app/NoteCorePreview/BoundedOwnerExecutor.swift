import Foundation
import Dispatch

// Unfrozen Apple adapter for the common OwnedWork contract. No UI or notebook dependencies.
final class AppleWorkTicket {
    fileprivate let identity: UUID
    let number: Int64
    fileprivate init(identity: UUID, number: Int64) { self.identity = identity; self.number = number }
}
enum AppleAdmissionFailure: String { case stopped, entryCapacity, byteCapacity, invalidCharge, idExhausted }
enum AppleWorkAdmission {
    case accepted(AppleWorkTicket), rejected(AppleAdmissionFailure)
}
enum AppleWorkResult<Value> {
    case succeeded(Value)
    // A thrown computation may already have committed a side effect. Never automatically rerun it.
    case outcomeUnknown(String)
}
enum AppleResultDisposition { case acknowledged, retryRequired }
enum AppleWorkStage: String { case queued, running, ready, delivering, deliveryBlocked }
enum AppleExecutorError: Error { case wrongOwner, recursiveDrain, invalidLimit }
struct AppleWorkSummary {
    let ticket: AppleWorkTicket
    let stage: AppleWorkStage
    let chargedBytes: Int64
    let deliveryFailure: String?
}
struct AppleExecutorStatus {
    let accepting: Bool
    let workerStopped: Bool
    let chargedBytes: Int64
    let entries: [AppleWorkSummary]
    var isDrained: Bool { !accepting && workerStopped && entries.isEmpty }
}
struct AppleResultDrain { let acknowledged: Int; let blocked: AppleWorkTicket? }

final class AppleBoundedOwnerExecutor {
    private final class Work {
        let ticket: AppleWorkTicket
        let charge: Int64
        var stage: AppleWorkStage = .queued
        var failure: String?
        // Compute is cleared on completion. Delivery retains the exact input and result until ACK.
        var compute: (() -> (() throws -> AppleResultDisposition))?
        var deliver: (() throws -> AppleResultDisposition)?
        init(ticket: AppleWorkTicket, charge: Int64, compute: @escaping () -> (() throws -> AppleResultDisposition)) {
            self.ticket = ticket; self.charge = charge; self.compute = compute
        }
    }
    private let condition = NSCondition()
    private let identity = UUID()
    private let ownerKey = DispatchSpecificKey<UUID>()
    private let ownerQueue: DispatchQueue
    private let mainOwner: Bool
    private let maxEntries: Int
    private let maxBytes: Int64
    private let automaticPump: Bool
    private let onOwnerProgress: (() -> Void)?
    private var work: [Work] = [] // Bounded by maxEntries; no secondary payload callback queue.
    private var charged: Int64 = 0
    private var sequence: Int64 = 0
    private var accepting = true
    private var workerStopped = false
    private var wakePending = false
    private var draining = false // Owner-only.
    private var worker: Thread!

    // ownerQueue must be serial and this constructor must run on it (main thread for .main).
    // The caller keeps the executor alive until stopAccepting + isDrained.
    init(ownerQueue: DispatchQueue = .main, maxEntries: Int = 64, maxBytes: Int64 = 8 * 1024 * 1024,
         automaticPump: Bool = true, onOwnerProgress: (() -> Void)? = nil) throws {
        precondition(maxEntries > 0 && maxEntries <= 4096 && maxBytes > 0)
        self.ownerQueue = ownerQueue; self.mainOwner = ownerQueue === DispatchQueue.main
        self.maxEntries = maxEntries; self.maxBytes = maxBytes
        self.automaticPump = automaticPump; self.onOwnerProgress = onOwnerProgress
        ownerQueue.setSpecific(key: ownerKey, value: identity)
        do { try checkOwner() } catch { ownerQueue.setSpecific(key: ownerKey, value: nil); throw error }
        work.reserveCapacity(maxEntries)
        worker = Thread { [self] in workerLoop() }
        worker.name = "NoteCore.bounded.worker"
        worker.start()
    }
    deinit { ownerQueue.setSpecific(key: ownerKey, value: nil) }

    func checkOwner() throws {
        guard (mainOwner && Thread.isMainThread) || DispatchQueue.getSpecific(key: ownerKey) == identity else {
            throw AppleExecutorError.wrongOwner
        }
    }
    func submit<Input, Output>(input: Input, chargedBytes: Int64,
                              compute: @escaping (Input) throws -> Output,
                              receive: @escaping (Input, AppleWorkResult<Output>) throws -> AppleResultDisposition) throws -> AppleWorkAdmission {
        try checkOwner()
        condition.lock(); defer { condition.unlock() }
        guard accepting else { return .rejected(.stopped) }
        guard chargedBytes > 0 else { return .rejected(.invalidCharge) }
        guard work.count < maxEntries else { return .rejected(.entryCapacity) }
        guard chargedBytes <= maxBytes - charged else { return .rejected(.byteCapacity) }
        guard sequence < Int64.max else { return .rejected(.idExhausted) }
        sequence += 1
        let ticket = AppleWorkTicket(identity: identity, number: sequence)
        let item = Work(ticket: ticket, charge: chargedBytes) {
            let result: AppleWorkResult<Output>
            do { result = .succeeded(try compute(input)) }
            catch { result = .outcomeUnknown(String(String(reflecting: error).prefix(256))) }
            return { try receive(input, result) }
        }
        work.append(item); charged += chargedBytes
        condition.signal()
        return .accepted(ticket)
    }
    func status() throws -> AppleExecutorStatus {
        try checkOwner()
        condition.lock(); defer { condition.unlock() }
        return AppleExecutorStatus(accepting: accepting, workerStopped: workerStopped, chargedBytes: charged,
            entries: work.map { AppleWorkSummary(ticket: $0.ticket, stage: $0.stage, chargedBytes: $0.charge, deliveryFailure: $0.failure) })
    }
    func stopAccepting() throws {
        try checkOwner()
        condition.lock(); accepting = false; condition.broadcast(); condition.unlock()
    }
    func retryDelivery(_ ticket: AppleWorkTicket) throws -> Bool {
        try checkOwner()
        condition.lock()
        guard ticket.identity == identity, let first = work.first, first.ticket === ticket, first.stage == .deliveryBlocked else {
            condition.unlock(); return false
        }
        first.stage = .ready; first.failure = nil
        scheduleWakeLocked()
        condition.unlock(); return true
    }
    func drainResults(limit: Int = 16) throws -> AppleResultDrain {
        try checkOwner()
        guard limit > 0 && limit <= 4096 else { throw AppleExecutorError.invalidLimit }
        guard !draining else { throw AppleExecutorError.recursiveDrain }
        draining = true; defer { draining = false }
        var acknowledged = 0
        var blocked: AppleWorkTicket?
        while acknowledged < limit {
            condition.lock()
            guard let item = work.first else { condition.unlock(); break }
            if item.stage == .deliveryBlocked { blocked = item.ticket; condition.unlock(); break }
            guard item.stage == .ready, let deliver = item.deliver else { condition.unlock(); break }
            item.stage = .delivering
            condition.unlock()
            let disposition: AppleResultDisposition
            var failure: String?
            do { disposition = try deliver() } catch {
                disposition = .retryRequired; failure = String(String(reflecting: error).prefix(256))
            }
            condition.lock()
            if disposition == .acknowledged {
                precondition(work.first === item)
                work.removeFirst(); charged -= item.charge
                item.deliver = nil; item.compute = nil; acknowledged += 1
            } else {
                item.stage = .deliveryBlocked; item.failure = failure ?? "Receiver retained responsibility"
                blocked = item.ticket
            }
            condition.unlock()
            if blocked != nil { break }
        }
        condition.lock(); scheduleWakeLocked(); condition.unlock()
        return AppleResultDrain(acknowledged: acknowledged, blocked: blocked)
    }
    // Test/diagnostic only. Never wait on the UI owner for storage shutdown.
    func awaitWorkerStop(timeout: TimeInterval) throws -> Bool {
        try checkOwner()
        precondition(timeout > 0 && timeout <= 60)
        let deadline = Date(timeIntervalSinceNow: timeout)
        condition.lock(); defer { condition.unlock() }
        while !workerStopped { if !condition.wait(until: deadline) { return workerStopped } }
        return true
    }
    private func workerLoop() {
        while true {
            condition.lock()
            while !work.contains(where: { $0.stage == .queued }) && accepting { condition.wait() }
            guard let item = work.first(where: { $0.stage == .queued }) else {
                workerStopped = true; worker = nil; condition.broadcast(); scheduleWakeLocked(force: true); condition.unlock(); return
            }
            item.stage = .running
            let compute = item.compute!
            condition.unlock()
            let delivery = autoreleasepool { compute() }
            condition.lock()
            item.compute = nil; item.deliver = delivery; item.stage = .ready
            scheduleWakeLocked(); condition.unlock()
        }
    }
    // Exactly one no-payload owner wake can be outstanding. A blocked result never spins a pump.
    private func scheduleWakeLocked(force: Bool = false) {
        guard automaticPump, !wakePending, force || work.first?.stage == .ready else { return }
        wakePending = true
        ownerQueue.async { [self] in
            condition.lock(); wakePending = false; condition.unlock()
            _ = try? drainResults(limit: min(16, maxEntries))
            onOwnerProgress?()
        }
    }
}
