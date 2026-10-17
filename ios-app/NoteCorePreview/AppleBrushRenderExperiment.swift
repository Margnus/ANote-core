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
    private let ownsExecutor: Bool
    private var dispatched: [ObjectIdentifier: AppleBrushDispatchExperiment] = [:]
    private var acknowledged: [AppleBrushDispatchExperiment] = [] // bounded ACK handles, no payloads
    init(frame: FrameToken, layer: LayerId, totalBytes: Int64 = 262144, maxJobs: Int = 4, residentBytes: Int64 = 32768,
         sharedExecutor: AppleBoundedOwnerExecutor? = nil) throws {
        ownsExecutor = sharedExecutor == nil
        executor = try sharedExecutor ?? AppleBoundedOwnerExecutor(maxEntries: maxJobs, maxBytes: totalBytes, automaticPump: false)
        let executor = self.executor
        owner = AppleBrushOwnerExperiment(frame: frame, layer: layer, residentBudget: residentBytes,
            totalBudget: totalBytes, maxJobs: Int32(maxJobs), checkOwner: { try! executor.checkOwner() })
    }
    var chargedBytes: Int64 { owner.chargedBytes }
    var isDrained: Bool { owner.isDrained && dispatched.isEmpty && acknowledged.isEmpty && (!ownsExecutor || (try! executor.status()).isDrained) }
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
        maxEdgeTests: Int64 = 20000000,
        beforeCompute: @escaping () throws -> Void = {},
        permitDelivery: @escaping () throws -> Bool = { true }
    ) throws -> AppleBrushDispatchExperiment? {
        try executor.checkOwner()
        guard let job = owner.admitPage(scene: scene, frame: frame, layer: layer, originX: originX, originY: originY,
            width: width, height: height, pixelSize: pixelSize, workingBytes: workingBytes, nativeAllowance: nativeAllowance,
            maxEdgeTests: maxEdgeTests) else { return nil }
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
        return try settleAfterDrain()
    }
    func settleAfterDrain(_ receive: (AppleBrushDispatchExperiment, String) -> Void = { _, _ in }) throws -> [String] {
        try executor.checkOutsideDrain()
        // Only here has drain returned and cleared executor input/result closures, including locals.
        let outstanding = try executor.status().entries
        let results = acknowledged.map { item -> String in
            precondition(!outstanding.contains { $0.ticket === item.ticket })
            let ack = owner.acknowledge(job: item.job)
            if ack != "QUARANTINED" { dispatched.removeValue(forKey: ObjectIdentifier(item)) }
            receive(item, ack)
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
        if ownsExecutor { try executor.stopAccepting() }
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
// CG may release on any thread. This receipt owns no Kotlin object and never settles the pool.
// CGDataProvider.h: releaseData is called when the provider is freed. Only that callback frees bytes.
private final class AppleBrushSourceReceipt {
    private let lock = NSLock()
    private var freed = false
    var onRelease: (() -> Void)? // Installed on owner before the provider can escape.
    var confirmed: Bool { lock.lock(); defer { lock.unlock() }; return freed }
    func release(_ data: UnsafeRawPointer) {
        UnsafeMutableRawPointer(mutating: data).deallocate()
        #if DEBUG
        NSLog("NOTECORE_RENDER provider-bytes-freed")
        #endif
        lock.lock(); precondition(!freed); freed = true; lock.unlock()
        let notify = onRelease
        DispatchQueue.main.async { notify?() }
    }
}
final class AppleBrushImageExperiment {
    private let adapter: AppleBrushRenderExperiment
    private let reader: AppleBrushReaderExperiment
    private var image: CGImage?
    private var unknown = false
    private var closing = false
    private var source: AppleBrushSourceReceipt?
    private var onSourceRelease: () -> Void = {}
    private(set) var released = false
    private(set) var rowStorageUpperBytes = 0
    private let releaseBoundary: (Bool) throws -> Void
    private init(_ adapter: AppleBrushRenderExperiment, _ reader: AppleBrushReaderExperiment,
                 _ releaseBoundary: @escaping (Bool) throws -> Void) {
        self.adapter = adapter; self.reader = reader; self.releaseBoundary = releaseBoundary
    }
    static func create(_ adapter: AppleBrushRenderExperiment, _ item: AppleBrushDispatchExperiment,
        trackedSource: Bool = false, onSourceRelease: @escaping () -> Void = {},
        afterCopy: () throws -> Void = {}, releaseBoundary: @escaping (Bool) throws -> Void = { _ in }
    ) throws -> AppleBrushImageBuildExperiment {
        try adapter.executor.checkOwner()
        let w = Int(item.job.width), h = Int(item.job.height), bytes = w * h * 4
        guard item.job.nativeAllowance >= Int64(bytes * 2) else { return .rejected("native-copy-budget") }
        guard let reader = adapter.owner.borrowNative(job: item.job) else { return .rejected("native-reader-unavailable") }
        let holder = AppleBrushImageExperiment(adapter, reader, releaseBoundary)
        holder.onSourceRelease = onSourceRelease
        do {
            // This scope ends before close on failure: no live context while returning its permit.
            try autoreleasepool {
                if trackedSource {
                    let receipt = AppleBrushSourceReceipt()
                    holder.source = receipt
                    receipt.onRelease = { [weak holder] in
                        guard let holder else { return }
                        // Main owner only; callback itself touches no native-reader authority.
                        _ = try! holder.finishConfirmedRelease()
                        holder.onSourceRelease()
                    }
                    let data = UnsafeMutableRawPointer.allocate(byteCount: bytes, alignment: 16)
                    for i in 0..<bytes { data.storeBytes(of: UInt8(reader.byteAt(offset: Int32(i))), toByteOffset: i, as: UInt8.self) }
                    let info = Unmanaged.passRetained(receipt).toOpaque()
                    guard let provider = CGDataProvider(dataInfo: info, data: data, size: bytes, releaseData: { info, data, _ in
                        Unmanaged<AppleBrushSourceReceipt>.fromOpaque(info!).takeRetainedValue().release(data)
                    }) else {
                        Unmanaged<AppleBrushSourceReceipt>.fromOpaque(info).release()
                        receipt.release(data)
                        throw AppleBrushImageFailureExperiment.layout
                    }
                    holder.image = CGImage(width: w, height: h, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: w * 4,
                        space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGBitmapInfo(rawValue:
                            CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue),
                        provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent)
                    guard holder.image != nil else { throw AppleBrushImageFailureExperiment.layout }
                    holder.rowStorageUpperBytes = bytes // Conservative original two-copy allowance stays reserved.
                    try afterCopy()
                    return
                }
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
        guard !released, !closing, !unknown, adapter.owner.canPublish(reader: reader), context.data != nil, let image else { return false }
        context.interpolationQuality = .none; context.draw(image, in: rect); return true
    }
    // Separate contract: UIKit may retain the provider. This source remains immutable and charged
    // until its release callback, independently of the destination's bitmap/GPU implementation.
    func drawUIKit(_ context: CGContext, in rect: CGRect) throws -> Bool {
        try adapter.executor.checkOwner()
        guard source != nil, !released, !closing, !unknown, adapter.owner.canPublish(reader: reader), let image else { return false }
        context.interpolationQuality = .none; context.draw(image, in: rect); return true
    }
    #if DEBUG
    // Test-only delayed Core Graphics consumer; no app call site or production image export.
    func retainSourceForTesting(_ retain: (CGDataProvider) -> Void) throws {
        try adapter.executor.checkOwner()
        if source != nil, let provider = image?.dataProvider { retain(provider) }
    }
    #endif
    private func finishConfirmedRelease() throws -> Bool {
        try adapter.executor.checkOwner()
        if released { return true }
        guard closing, !unknown, image == nil, source?.confirmed ?? true else { return false }
        precondition(adapter.owner.release(reader: reader, unknown: false)); released = true
        #if DEBUG
        if source != nil { NSLog("NOTECORE_RENDER provider-owner-settled charged=%lld", adapter.chargedBytes) }
        #endif
        return true
    }
    @discardableResult func close() throws -> Bool {
        try adapter.executor.checkOwner()
        if released { return true }
        if unknown { return false }
        if closing { return try finishConfirmedRelease() }
        closing = true
        do {
            try releaseBoundary(false)
            image = nil // No raw image escapes; relinquish controlled ARC ownership on owner.
            try releaseBoundary(true)
        } catch {
            unknown = true; precondition(!adapter.owner.release(reader: reader, unknown: true)); return false
        }
        return try finishConfirmedRelease()
    }
    func reconcileReleased() throws -> Bool {
        try adapter.executor.checkOwner()
        guard unknown, image == nil, source?.confirmed ?? true else { return false }
        precondition(adapter.owner.reconcileReleased(reader: reader)); released = true; return true
    }
}
