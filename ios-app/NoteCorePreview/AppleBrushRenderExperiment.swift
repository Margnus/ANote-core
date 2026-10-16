import Foundation
import CoreGraphics
import NoteCanvasPrototype

// Experiment only. Reuses the existing worker and Kotlin resource authority, no payload queue.
final class AppleBrushDispatchExperiment {
    let job: AppleBrushJobExperiment
    fileprivate var ticket: AppleWorkTicket?
    private let lock = NSLock()
    private var cancelled = false
    fileprivate init(_ job: AppleBrushJobExperiment) { self.job = job }
    fileprivate func cancel() { lock.lock(); cancelled = true; lock.unlock() }
    fileprivate func isCancelled() -> Bool { lock.lock(); defer { lock.unlock() }; return cancelled }
}
final class AppleBrushRenderExperiment {
    let executor: AppleBoundedOwnerExecutor
    let owner: AppleBrushOwnerExperiment
    private var dispatched: [ObjectIdentifier: AppleBrushDispatchExperiment] = [:]
    private var acknowledged: [AppleBrushDispatchExperiment] = [] // bounded ACK handles, no payloads
    init(frame: FrameToken, layer: LayerId, totalBytes: Int64 = 262144, maxJobs: Int = 4, residentBytes: Int64 = 32768) throws {
        executor = try AppleBoundedOwnerExecutor(maxEntries: maxJobs, maxBytes: totalBytes, automaticPump: false)
        let executor = self.executor
        owner = AppleBrushOwnerExperiment(frame: frame, layer: layer, residentBudget: residentBytes,
            totalBudget: totalBytes, maxJobs: Int32(maxJobs), checkOwner: { try! executor.checkOwner() })
    }
    var chargedBytes: Int64 { owner.chargedBytes }
    var isDrained: Bool { owner.isDrained && (try! executor.status()).isDrained }
    @discardableResult func submit(stroke: StrokeObject, frame: FrameToken,
        originX: Double = 0.25, originY: Double = 8, width: Int32 = 64, height: Int32 = 32,
        pixelSize: Double = 0.5, nativeAllowance: Int64 = 16384,
        beforeCompute: @escaping () throws -> Void = {},
        permitDelivery: @escaping () throws -> Bool = { true }
    ) throws -> AppleBrushDispatchExperiment? {
        try executor.checkOwner()
        guard let job = owner.admit(stroke: stroke, frame: frame, originX: originX, originY: originY,
            width: width, height: height, pixelSize: pixelSize, workingBytes: 65536, nativeAllowance: nativeAllowance) else { return nil }
        return try dispatch(job, beforeCompute: beforeCompute, permitDelivery: permitDelivery)
    }
    @discardableResult func submitPage(scene: SceneSnapshot, frame: FrameToken, layer: LayerId,
        originX: Double = 0.25, originY: Double = 8, width: Int32 = 64, height: Int32 = 32,
        pixelSize: Double = 0.5, nativeAllowance: Int64 = 16384,
        workingBytes: Int64 = 65536,
        beforeCompute: @escaping () throws -> Void = {},
        permitDelivery: @escaping () throws -> Bool = { true }
    ) throws -> AppleBrushDispatchExperiment? {
        try executor.checkOwner()
        guard let job = owner.admitPage(scene: scene, frame: frame, layer: layer, originX: originX, originY: originY,
            width: width, height: height, pixelSize: pixelSize, workingBytes: workingBytes, nativeAllowance: nativeAllowance) else { return nil }
        return try dispatch(job, beforeCompute: beforeCompute, permitDelivery: permitDelivery)
    }
    private func dispatch(_ job: AppleBrushJobExperiment, beforeCompute: @escaping () throws -> Void,
        permitDelivery: @escaping () throws -> Bool
    ) throws -> AppleBrushDispatchExperiment? {
        let item = AppleBrushDispatchExperiment(job)
        let admission = try executor.submit(input: item, chargedBytes: job.peakBytes, compute: { input in
            precondition(!Thread.isMainThread && Thread.current.name == "NoteCore.bounded.worker")
            do {
                try beforeCompute()
                return input.job.execute { KotlinBoolean(bool: input.isCancelled()) }
            } catch { return input.job.unknown(message: String(reflecting: error)) }
        }, receive: { [self] input, result in
            // Compute mints the wrapper once, including UNKNOWN; retries retain its identity.
            switch result {
            case .succeeded(let output): precondition(owner.receive(job: input.job, result: output))
            case .outcomeUnknown: preconditionFailure("Experiment compute must convert thrown failures once")
            }
            guard try permitDelivery() else { return .retryRequired }
            acknowledged.append(input); return .acknowledged
        })
        guard case .accepted(let ticket) = admission else { precondition(owner.reject(job: job)); return nil }
        item.ticket = ticket; dispatched[ObjectIdentifier(item)] = item
        return item
    }
    func drain() throws -> [String] {
        _ = try executor.drainResults()
        // Only here has drain returned and cleared executor input/result closures, including locals.
        let results = acknowledged.map { item -> String in
            let ack = owner.acknowledge(job: item.job)
            if ack != "QUARANTINED" { dispatched.removeValue(forKey: ObjectIdentifier(item)) }
            return ack
        }
        acknowledged.removeAll(keepingCapacity: true)
        return results
    }
    func cancel(_ item: AppleBrushDispatchExperiment) throws {
        try executor.checkOwner()
        if owner.cancel(job: item.job) { item.cancel() }
    }
    func expect(_ frame: FrameToken, layer: LayerId) throws {
        try executor.checkOwner()
        if owner.expect(frame: frame, layer: layer) { for item in dispatched.values where item.job.frame != frame { try cancel(item) } }
    }
    func retry(_ item: AppleBrushDispatchExperiment) throws -> Bool {
        try executor.checkOwner(); guard let ticket = item.ticket else { return false }
        return try executor.retryDelivery(ticket)
    }
    func close() throws {
        try executor.checkOwner(); owner.close()
        for item in dispatched.values { item.cancel() }
        try executor.stopAccepting()
    }
    // Caller must explicitly establish that this pure CPU job escaped no output. Worker stop alone
    // is insufficient for a general side-effecting computation; this experiment allocates no native data.
    func reconcileNoOutput(_ item: AppleBrushDispatchExperiment) throws -> Bool {
        guard try executor.status().workerStopped, owner.reconcileNoOutput(job: item.job) else { return false }
        dispatched.removeValue(forKey: ObjectIdentifier(item)); return true
    }
}

enum AppleBrushImageBuildExperiment {
    case ready(AppleBrushImageExperiment), rejected(String), cleanupRequired(AppleBrushImageExperiment)
}
enum AppleBrushImageFailureExperiment: Error { case layout, copy }
final class AppleBrushImageExperiment {
    private let adapter: AppleBrushRenderExperiment
    private let reader: AppleBrushReaderExperiment
    private var image: CGImage?
    private var unknown = false
    private(set) var released = false
    private(set) var rowStorageUpperBytes = 0
    private let releaseBoundary: (Bool) throws -> Void
    private init(_ adapter: AppleBrushRenderExperiment, _ reader: AppleBrushReaderExperiment,
                 _ releaseBoundary: @escaping (Bool) throws -> Void) {
        self.adapter = adapter; self.reader = reader; self.releaseBoundary = releaseBoundary
    }
    static func create(_ adapter: AppleBrushRenderExperiment, _ item: AppleBrushDispatchExperiment,
        afterCopy: () throws -> Void = {}, releaseBoundary: @escaping (Bool) throws -> Void = { _ in }
    ) throws -> AppleBrushImageBuildExperiment {
        try adapter.executor.checkOwner()
        let w = Int(item.job.width), h = Int(item.job.height), bytes = w * h * 4
        guard item.job.nativeAllowance >= Int64(bytes * 2) else { return .rejected("native-copy-budget") }
        guard let reader = adapter.owner.borrowNative(job: item.job) else { return .rejected("native-reader-unavailable") }
        let holder = AppleBrushImageExperiment(adapter, reader, releaseBoundary)
        do {
            // This scope ends before close on failure: no live context while returning its permit.
            try autoreleasepool {
                guard let context = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                    space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue),
                    let data = context.data else { throw AppleBrushImageFailureExperiment.layout }
                guard context.bytesPerRow == w * 4 else { throw AppleBrushImageFailureExperiment.layout }
                for i in 0..<bytes { data.storeBytes(of: UInt8(reader.byteAt(offset: Int32(i))), toByteOffset: i, as: UInt8.self) }
                holder.image = context.makeImage()
                guard let image = holder.image, image.bytesPerRow * image.height <= Int(item.job.nativeAllowance) - bytes else {
                    throw AppleBrushImageFailureExperiment.layout
                }
                holder.rowStorageUpperBytes = context.bytesPerRow * h + image.bytesPerRow * image.height
                try afterCopy()
            }
            return .ready(holder)
        } catch {
            return try holder.close() ? .rejected("native-image-create-failed") : .cleanupRequired(holder)
        }
    }
    func draw(_ context: CGContext, in rect: CGRect) throws -> Bool {
        try adapter.executor.checkOwner()
        guard !released, !unknown, adapter.owner.canPublish(reader: reader), context.data != nil, let image else { return false }
        context.interpolationQuality = .none; context.draw(image, in: rect); return true
    }
    @discardableResult func close() throws -> Bool {
        try adapter.executor.checkOwner()
        if released { return true }
        if unknown { return false }
        do {
            try releaseBoundary(false)
            image = nil // No raw image escapes; relinquish controlled ARC ownership on owner.
            try releaseBoundary(true)
        } catch {
            unknown = true; precondition(!adapter.owner.release(reader: reader, unknown: true)); return false
        }
        precondition(adapter.owner.release(reader: reader, unknown: false)); released = true; return true
    }
    func reconcileReleased() throws -> Bool {
        try adapter.executor.checkOwner()
        guard unknown, image == nil else { return false }
        precondition(adapter.owner.reconcileReleased(reader: reader)); released = true; return true
    }
}
