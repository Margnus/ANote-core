import Foundation
import Dispatch

private enum TestFailure: Error { case injected }
private final class Payload { let number: Int; init(_ number: Int) { self.number = number } }
private func expect(_ condition: @autoclosure () -> Bool, _ detail: String = "contract failed") {
    precondition(condition(), detail)
}
private func accepted(_ admission: AppleWorkAdmission) -> AppleWorkTicket {
    guard case .accepted(let ticket) = admission else { fatalError("Expected acceptance") }; return ticket
}
private func rejected(_ admission: AppleWorkAdmission, _ reason: AppleAdmissionFailure) {
    guard case .rejected(let actual) = admission else { fatalError("Expected rejection") }; expect(actual == reason)
}
private func wait(_ semaphore: DispatchSemaphore) { expect(semaphore.wait(timeout: .now() + 3) == .success, "Barrier timed out") }
private var passed: [String] = []
private func test(_ id: String, _ body: () throws -> Void) rethrows { try body(); passed.append(id) }

@main struct ExecutorConformance {
    static func main() throws {
        let owner = DispatchQueue(label: "NoteCore.test.owner")
        try test("fifo_finite_drain") {
            try owner.sync {
                let executor = try AppleBoundedOwnerExecutor(ownerQueue: owner, automaticPump: false)
                var received: [Int] = []
                for i in 0..<3 {
                    _ = accepted(try executor.submit(input: i, chargedBytes: 4, compute: { $0 }, receive: { input, result in
                        try executor.checkOwner()
                        guard case .succeeded(let value) = result else { fatalError() }
                        expect(value == input); received.append(value); return .acknowledged
                    }))
                }
                try executor.stopAccepting(); expect(try! executor.awaitWorkerStop(timeout: 3))
                expect(try! executor.status().chargedBytes == 12)
                expect(try! executor.drainResults(limit: 1).acknowledged == 1); expect(received == [0])
                expect(try! executor.drainResults().acknowledged == 2); expect(received == [0, 1, 2])
                expect(try! executor.status().isDrained)
            }
        }
        try test("all_states_capacity_stop") {
            try owner.sync {
                let executor = try AppleBoundedOwnerExecutor(ownerQueue: owner, maxEntries: 2, maxBytes: 8, automaticPump: false)
                let started = DispatchSemaphore(value: 0), release = DispatchSemaphore(value: 0)
                _ = accepted(try executor.submit(input: 1, chargedBytes: 4, compute: { value in started.signal(); wait(release); return value }, receive: { _, _ in .acknowledged }))
                wait(started)
                _ = accepted(try executor.submit(input: 2, chargedBytes: 4, compute: { $0 }, receive: { _, _ in .acknowledged }))
                rejected(try executor.submit(input: 3, chargedBytes: 1, compute: { _ in fatalError("Rejected work ran") }, receive: { _, _ in .acknowledged }), .entryCapacity)
                try executor.stopAccepting()
                expect(!(try! executor.awaitWorkerStop(timeout: 0.02)), "Timeout must not cancel accepted work")
                release.signal(); expect(try! executor.awaitWorkerStop(timeout: 3))
                expect(try! executor.status().chargedBytes == 8); expect(!(try! executor.status().isDrained))
                rejected(try executor.submit(input: 3, chargedBytes: 1, compute: { $0 }, receive: { _, _ in .acknowledged }), .stopped)
                _ = try executor.drainResults(); expect(try! executor.status().isDrained)
            }
        }
        try test("byte_bounds") {
            try owner.sync {
                let executor = try AppleBoundedOwnerExecutor(ownerQueue: owner, maxBytes: Int64.max, automaticPump: false)
                rejected(try executor.submit(input: 0, chargedBytes: 0, compute: { $0 }, receive: { _, _ in .acknowledged }), .invalidCharge)
                rejected(try executor.submit(input: 0, chargedBytes: -1, compute: { $0 }, receive: { _, _ in .acknowledged }), .invalidCharge)
                _ = accepted(try executor.submit(input: 1, chargedBytes: Int64.max, compute: { $0 }, receive: { _, _ in .acknowledged }))
                rejected(try executor.submit(input: 2, chargedBytes: 1, compute: { $0 }, receive: { _, _ in .acknowledged }), .byteCapacity)
                try executor.stopAccepting(); expect(try! executor.awaitWorkerStop(timeout: 3))
                _ = try executor.drainResults(); expect(try! executor.status().chargedBytes == 0)
            }
        }
        try test("blocked_identity_retry_no_recompute") {
            try owner.sync {
                let executor = try AppleBoundedOwnerExecutor(ownerQueue: owner, automaticPump: false)
                let input = Payload(1), output = Payload(2)
                var computes = 0, deliveries = 0, sequence: [Int] = []
                let ticket = accepted(try executor.submit(input: input, chargedBytes: 8, compute: { original in
                    expect(original === input); computes += 1; return output
                }, receive: { original, result in
                    expect(original === input)
                    guard case .succeeded(let value) = result else { fatalError() }; expect(value === output)
                    deliveries += 1; sequence.append(1)
                    return deliveries == 1 ? .retryRequired : .acknowledged
                }))
                _ = accepted(try executor.submit(input: 2, chargedBytes: 4, compute: { $0 }, receive: { _, _ in sequence.append(2); return .acknowledged }))
                try executor.stopAccepting(); expect(try! executor.awaitWorkerStop(timeout: 3))
                expect(try! executor.drainResults().blocked === ticket)
                expect(try! executor.status().chargedBytes == 12); expect(sequence == [1])
                expect(try! executor.drainResults().acknowledged == 0)
                expect(try! executor.retryDelivery(ticket)); _ = try executor.drainResults()
                expect(computes == 1 && deliveries == 2 && sequence == [1, 1, 2])
                expect(!(try! executor.retryDelivery(ticket))); expect(try! executor.status().isDrained)
            }
        }
        try test("throwing_receiver_idempotent_redelivery") {
            try owner.sync {
                let executor = try AppleBoundedOwnerExecutor(ownerQueue: owner, automaticPump: false)
                var applied = Set<Int>(), attempts = 0
                let ticket = accepted(try executor.submit(input: 7, chargedBytes: 4, compute: { $0 }, receive: { id, _ in
                    applied.insert(id); attempts += 1
                    if attempts == 1 { throw TestFailure.injected }; return .acknowledged
                }))
                try executor.stopAccepting(); expect(try! executor.awaitWorkerStop(timeout: 3))
                expect(try! executor.drainResults().blocked === ticket)
                expect(try! executor.status().entries[0].deliveryFailure != nil)
                expect(try! executor.retryDelivery(ticket)); _ = try executor.drainResults()
                expect(applied.count == 1 && attempts == 2 && (try! executor.status().isDrained))
            }
        }
        try test("compute_side_effect_unknown_next_job") {
            try owner.sync {
                let executor = try AppleBoundedOwnerExecutor(ownerQueue: owner, automaticPump: false)
                let input = Payload(4); var effects = 0, unknown = false, next = false
                _ = accepted(try executor.submit(input: input, chargedBytes: 4, compute: { _ -> Int in
                    effects += 1; throw TestFailure.injected
                }, receive: { original, result in
                    expect(original === input); if case .outcomeUnknown = result { unknown = true } else { fatalError() }; return .acknowledged
                }))
                _ = accepted(try executor.submit(input: 2, chargedBytes: 4, compute: { $0 }, receive: { _, _ in next = true; return .acknowledged }))
                try executor.stopAccepting(); expect(try! executor.awaitWorkerStop(timeout: 3)); _ = try executor.drainResults()
                expect(effects == 1 && unknown && next && (try! executor.status().isDrained))
            }
        }
        try test("owner_queue_guard_worker_separation") {
            let workerChecked = DispatchSemaphore(value: 0)
            let executor = try owner.sync { try AppleBoundedOwnerExecutor(ownerQueue: owner, automaticPump: false) }
            do { try executor.stopAccepting(); fatalError("Foreign owner accepted") } catch AppleExecutorError.wrongOwner { }
            try owner.sync {
                _ = accepted(try executor.submit(input: 1, chargedBytes: 4, compute: { value in
                    do { try executor.checkOwner(); fatalError("Worker became owner") } catch AppleExecutorError.wrongOwner { }
                    workerChecked.signal(); return value
                }, receive: { _, _ in try executor.checkOwner(); return .acknowledged }))
                try executor.stopAccepting(); expect(try! executor.awaitWorkerStop(timeout: 3)); _ = try executor.drainResults()
            }
            wait(workerChecked)
        }
        try test("recursive_drain_guard") {
            try owner.sync {
                let executor = try AppleBoundedOwnerExecutor(ownerQueue: owner, automaticPump: false)
                _ = accepted(try executor.submit(input: 1, chargedBytes: 4, compute: { $0 }, receive: { _, _ in
                    do { _ = try executor.drainResults(); fatalError("Recursive drain accepted") } catch AppleExecutorError.recursiveDrain { }
                    try executor.stopAccepting(); return .acknowledged
                }))
                try executor.stopAccepting(); expect(try! executor.awaitWorkerStop(timeout: 3)); _ = try executor.drainResults()
                expect(try! executor.status().isDrained)
            }
        }
        try test("foreign_ticket_rejected") {
            try owner.sync {
                let first = try AppleBoundedOwnerExecutor(ownerQueue: owner, automaticPump: false)
                let second = try AppleBoundedOwnerExecutor(ownerQueue: owner, automaticPump: false)
                var attempts = 0
                let ticket = accepted(try first.submit(input: 1, chargedBytes: 4, compute: { $0 }, receive: { _, _ in attempts += 1; return attempts == 1 ? .retryRequired : .acknowledged }))
                try first.stopAccepting(); try second.stopAccepting()
                expect(try! first.awaitWorkerStop(timeout: 3)); expect(try! second.awaitWorkerStop(timeout: 3))
                _ = try first.drainResults(); expect(!(try! second.retryDelivery(ticket)))
                expect(try! first.retryDelivery(ticket)); _ = try first.drainResults()
                expect(try! first.status().isDrained); expect(try! second.status().isDrained)
            }
        }
        try test("payload_release_only_after_ack") {
            try owner.sync {
                let executor = try AppleBoundedOwnerExecutor(ownerQueue: owner, automaticPump: false)
                weak var retained: Payload?
                var count = 0
                let ticket: AppleWorkTicket = try {
                    let input = Payload(1); retained = input
                    return accepted(try executor.submit(input: input, chargedBytes: 4, compute: { $0.number }, receive: { original, _ in
                        expect(original.number == 1); count += 1; return count == 1 ? .retryRequired : .acknowledged
                    }))
                }()
                try executor.stopAccepting(); expect(try! executor.awaitWorkerStop(timeout: 3))
                _ = try executor.drainResults(); expect(retained != nil)
                expect(try! executor.retryDelivery(ticket)); _ = try executor.drainResults()
                expect(retained == nil, "ACK must release input; ticket must not retain executor or input")
            }
        }
        try test("automatic_owner_wake_stop_drain") {
            let done = DispatchSemaphore(value: 0)
            var executor: AppleBoundedOwnerExecutor!
            var count = 0
            try owner.sync {
                executor = try AppleBoundedOwnerExecutor(ownerQueue: owner, maxEntries: 40, onOwnerProgress: {
                    if (try! executor.status().isDrained) { done.signal() }
                })
                for i in 0..<40 {
                    _ = accepted(try executor.submit(input: i, chargedBytes: 4, compute: { $0 }, receive: { input, _ in
                        try executor.checkOwner(); expect(input == count); count += 1; return .acknowledged
                    }))
                }
                try executor.stopAccepting()
            }
            wait(done)
            owner.sync { expect(count == 40); expect(try! executor.status().isDrained); executor = nil }
        }
        try test("automatic_blocked_wake_requires_explicit_retry") {
            let blocked = DispatchSemaphore(value: 0), done = DispatchSemaphore(value: 0)
            var executor: AppleBoundedOwnerExecutor!, ticket: AppleWorkTicket!
            var deliveries = 0
            try owner.sync {
                executor = try AppleBoundedOwnerExecutor(ownerQueue: owner, onOwnerProgress: {
                    let status = try! executor.status()
                    if status.entries.first?.stage == .deliveryBlocked { blocked.signal() }
                    if status.isDrained { done.signal() }
                })
                ticket = accepted(try executor.submit(input: 1, chargedBytes: 4, compute: { $0 }, receive: { _, _ in
                    deliveries += 1; return deliveries == 1 ? .retryRequired : .acknowledged
                }))
                try executor.stopAccepting()
            }
            wait(blocked)
            owner.sync { expect(deliveries == 1); expect(try! executor.status().chargedBytes == 4); expect(try! executor.retryDelivery(ticket)) }
            wait(done)
            owner.sync { expect(deliveries == 2); executor = nil }
        }
        let data = try JSONSerialization.data(withJSONObject: ["platform": "macOS", "real_apple_threads_verified": true,
            "ios_runtime_verified": false, "passed": passed, "test_count": passed.count], options: [.prettyPrinted, .sortedKeys])
        print(String(data: data, encoding: .utf8)!)
    }
}
