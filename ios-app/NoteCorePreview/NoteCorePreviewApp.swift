import SwiftUI
import UniformTypeIdentifiers
import NoteCanvasPrototype

struct PreviewBackup: FileDocument {
    static var readableContentTypes: [UTType] { [.data] }
    var data: Data
    init(data: Data) { self.data = data }
    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents else { throw CocoaError(.fileReadCorruptFile) }
        self.data = data
    }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: data) }
}

struct LocalNote: Identifiable { let id: String; let title: String; let date: Date }
enum PreviewOwnerRetirementRequest { case started, alreadyRetiring, retired, inputPending, transferPending, saveRejected }
enum PreviewOwnerPhase { case active, retiring, retired }
struct PreviewOwnerRetirementStatus {
    let phase: PreviewOwnerPhase
    let pendingWrites: Int
    let retryRequired: Int
    let renderBytes: Int64
    let workerStopped: Bool
}

final class PreviewModel: ObservableObject {
    @Published var notes: [LocalNote] = []
    @Published var catalogError = false
    @Published private(set) var current: PagedPreviewNotebook?
    @Published var status = ""
    @Published var revision: Int64 = 0
    @Published var exportDocument: PreviewBackup?
    @Published var transferBusy = false
    private let directory: URL
    private let beforeInkCompute: () throws -> Void
    private let inkReleaseBoundary: (Bool) throws -> Void
    private let ledger = PreviewSaveLedger(maxDocuments: 8, byteBudget: 32 * 1024 * 1024,
        ownerCheck: { precondition(Thread.isMainThread) })
    private let storageWorkBytes: Int64 = 256 * 1024 * 1024
    // Identity-only saved shortcut; the authority/ledger own content while it is needed.
    private weak var savedRoot: DocumentRoot?
    private weak var savedPending: DocumentCommandCanvas?
    private let documentBudget: DocumentBudget?
    private var storageEpoch: Int64 = 0
    private var navigationEpoch: Int64 = 0
    private lazy var executor = try! AppleBoundedOwnerExecutor(maxEntries: 16, maxBytes: storageWorkBytes,
        onOwnerProgress: { [weak self] in self?.inkOwnerProgress(); self?.pumpWrites(); self?.advanceOwnerRetirement() })
    private var ownerRetiring = false
    private var ownerRetired = false
    private var ownerStopRequested = false
    // The original owner retains responsibility, not a replacement owner or queue.
    private var retirementKeepAlive: PreviewModel?
    private var retirementObserver: ((PreviewOwnerRetirementStatus) -> Void)?
    var ownerRetirementStatus: PreviewOwnerRetirementStatus {
        precondition(Thread.isMainThread)
        let saves = ledger.states()
        return PreviewOwnerRetirementStatus(phase: ownerRetired ? .retired : ownerRetiring ? .retiring : .active,
            pendingWrites: saves.count, retryRequired: saves.filter { $0.retryRequired }.count,
            renderBytes: inkChargedBytes, workerStopped: (try! executor.status()).workerStopped)
    }
    @discardableResult func beginOwnerRetirement(
        onRetired: ((PreviewOwnerRetirementStatus) -> Void)? = nil
    ) -> PreviewOwnerRetirementRequest {
        precondition(Thread.isMainThread)
        if ownerRetired { return .retired }
        if ownerRetiring { return .alreadyRetiring }
        if current?.hasActiveStroke == true || current?.hasPendingStroke == true { return .inputPending }
        if transferBusy || exportDocument != nil { return .transferPending }
        guard save() else { return .saveRejected }
        // No more document/transfer admission; accepted IO still uses the same worker and ledger.
        ownerRetiring = true; retirementKeepAlive = self; retirementObserver = onRetired
        precondition(current?.retire() ?? true)
        navigationEpoch += 1; current = nil; savedRoot = nil
        retireInk(); inkView = nil
        advanceOwnerRetirement()
        return .started
    }
    @discardableResult func retryRetainedWrites() -> Bool {
        precondition(Thread.isMainThread)
        guard !ownerRetired && !ownerStopRequested else { return false }
        for entry in ledger.states() where entry.retryRequired {
            guard ledger.offer(snapshot: entry.snapshot) == .accepted else { return false }
        }
        pumpWrites(); advanceOwnerRetirement(); return true
    }
    private func advanceOwnerRetirement() {
        precondition(Thread.isMainThread)
        guard ownerRetiring && !ownerRetired else { return }
        let worker = try! executor.status()
        guard ledger.states().isEmpty, inkRenderer == nil, inkImage == nil, worker.entries.isEmpty else { return }
        if !ownerStopRequested { ownerStopRequested = true; try! executor.stopAccepting() }
        guard (try! executor.status()).isDrained else { return }
        ownerRetired = true
        let observer = retirementObserver; retirementObserver = nil
        observer?(ownerRetirementStatus)
        retirementKeepAlive = nil
    }
    private struct InkFrameKey: Equatable {
        let document: String
        let page: String
        let revision: Int64
        let surface: UUID
        let width: Int32
        let height: Int32
        let originX: Double
        let originY: Double
        let pixelSize: Double
        var rect: CGRect { CGRect(x: originX, y: originY, width: Double(width) * pixelSize, height: Double(height) * pixelSize) }
    }
    // One render responsibility may outlive its view. No retiring payload queue or new worker.
    private var inkRenderer: AppleBrushRenderExperiment?
    private var inkImage: AppleBrushImageExperiment?
    private var inkPending: AppleBrushDispatchExperiment?
    private var inkKey: InkFrameKey?
    private var inkAttempt: InkFrameKey?
    private var inkGeneration: Int64 = 0
    private var inkLoggedGeneration: Int64 = -1
    private var inkRetiring = false
    private var inkRetryOnProgress = false
    private weak var inkView: InkUIView?
    private(set) var inkFramePublications = 0
    private(set) var inkFrameDraws = 0
    private(set) var inkRejectedTargets = 0
    var inkChargedBytes: Int64 { precondition(Thread.isMainThread); return inkRenderer?.chargedBytes ?? 0 }
    var inkOwnerRetained: Bool { precondition(Thread.isMainThread); return inkRenderer != nil }

    /// Called only on the model owner after the shared executor's finite drain has returned.
    private func inkOwnerProgress() {
        guard let renderer = inkRenderer else { return }
        // The model owner is still alive; retire a vanished presentation endpoint before settling late output.
        // This does not transfer a pool or acknowledge a missing native release.
        if !inkRetiring && inkView == nil { retireInk() }
        _ = try! renderer.settleAfterDrain { [self] item, ack in
            guard item === inkPending else { return }
            inkPending = nil
            guard !inkRetiring, ack == "PUBLISHED", let key = inkKey, key == inkAttempt,
                  current?.documentId == key.document, current?.changeVersion == key.revision,
                  current?.currentPageId.value == key.page, inkView?.surfaceIdentity == key.surface else { return }
            switch try! AppleBrushImageExperiment.create(renderer, item, trackedSource: true,
                onSourceRelease: { [weak self] in self?.inkSourceReleased() }, releaseBoundary: inkReleaseBoundary) {
            case .ready(let image): inkImage = image; inkFramePublications += 1
            case .cleanupRequired(let image): inkImage = image // Retain UNKNOWN and its charge.
            case .rejected: break // The canonical Path remains the complete visible result.
            }
        }
        if inkRetiring && renderer.isDrained {
            inkRenderer = nil; inkRetiring = false
            logInkDrained()
        }
        if inkRetryOnProgress { inkRetryOnProgress = false; inkAttempt = nil }
        inkView?.setNeedsDisplay()
    }
    private func inkSourceReleased() {
        precondition(Thread.isMainThread)
        if inkImage?.released == true { inkImage = nil }
        #if DEBUG
        NSLog("NOTECORE_RENDER source-released charged=%lld", inkChargedBytes)
        #endif
        inkOwnerProgress()
        advanceOwnerRetirement()
    }
    func detachInk(surface: UUID) {
        precondition(Thread.isMainThread)
        guard inkView?.surfaceIdentity == surface else { return }
        retireInk(); inkView = nil
    }
    private func retireInk() {
        precondition(Thread.isMainThread)
        inkKey = nil; inkAttempt = nil; inkPending = nil; inkRetryOnProgress = false
        if let image = inkImage, (try! image.close()) { inkImage = nil }
        if let renderer = inkRenderer {
            inkRetiring = true; try! renderer.close()
            if renderer.isDrained { inkRenderer = nil; inkRetiring = false; logInkDrained() }
        }
    }
    private func logInkDrained() {
        #if DEBUG
        NSLog("NOTECORE_RENDER owner-drained charged=%lld", inkChargedBytes)
        #endif
    }
    #if DEBUG
    func retainInkSourceForTesting(_ retain: (CGDataProvider) -> Void) throws {
        try inkImage?.retainSourceForTesting(retain)
    }
    #endif
    func resumeFromBackground() { precondition(Thread.isMainThread); inkView?.setNeedsDisplay() }
    @discardableResult func reconcileInkRelease() -> Bool {
        precondition(Thread.isMainThread)
        guard let image = inkImage, (try! image.reconcileReleased()) else { return false }
        inkImage = nil; inkOwnerProgress(); advanceOwnerRetirement(); return true
    }

    /// Only the callback-tracked provider holder is eligible for UIKit presentation.
    /// The independent CPU holder's bitmap gate remains unchanged.
    func drawCommittedInk(_ context: CGContext, note: PagedPreviewNotebook, view: InkUIView) -> Bool {
        precondition(Thread.isMainThread)
        guard current === note else { return false }
        if inkView !== view {
            retireInk(); inkView = view
        }
        let viewport = view.viewport, density = Double(view.contentScaleFactor)
        let width = ceil(view.bounds.width * density), height = ceil(view.bounds.height * density)
        let pixelSize = 1 / (viewport.scale * density)
        guard width.isFinite, height.isFinite, width >= 1, height >= 1, width <= 4096, height <= 4096,
              width * height <= 4194304, pixelSize.isFinite, pixelSize >= 0.0001, pixelSize <= 1e6 else { return false }
        let key = InkFrameKey(document: note.documentId, page: note.currentPageId.value, revision: note.changeVersion,
            surface: view.surfaceIdentity, width: Int32(width), height: Int32(height),
            originX: -viewport.offsetX / viewport.scale, originY: -viewport.offsetY / viewport.scale, pixelSize: pixelSize)
        if inkRetiring { return false } // A new document cannot create another pool before the old owner drains.
        if key != inkKey {
            guard inkGeneration < Int64.max else { return false }
            inkGeneration += 1; inkKey = key; inkAttempt = nil; inkPending = nil
            if let image = inkImage {
                guard try! image.close() else { retireInk(); return false }
                inkImage = nil
            }
            let root = note.snapshot()
            let frame = FrameToken(sceneId: root.scene.id, sceneRevision: root.scene.revision, viewportVersion: inkGeneration,
                sessionGeneration: navigationEpoch, surfaceGeneration: inkGeneration, requestSequence: inkGeneration)
            if let renderer = inkRenderer { try! renderer.expect(frame, layer: note.currentPage.layerId) }
            else {
                inkRenderer = try! AppleBrushRenderExperiment(frame: frame, layer: note.currentPage.layerId,
                    totalBytes: 64 * 1024 * 1024, maxJobs: 2, residentBytes: 48 * 1024 * 1024, sharedExecutor: executor)
            }
        }
        if let image = inkImage, key == inkAttempt {
            context.saveGState()
            context.translateBy(x: key.rect.minX, y: key.rect.maxY); context.scaleBy(x: 1, y: -1)
            let drawn = try! image.drawUIKit(context, in: CGRect(origin: .zero, size: key.rect.size))
            context.restoreGState()
            if drawn {
                inkFrameDraws += 1
                #if DEBUG
                if inkLoggedGeneration != inkGeneration {
                    inkLoggedGeneration = inkGeneration
                    NSLog("NOTECORE_RENDER frame-presented w=%ld h=%ld charged=%lld bitmap=%d source=provider", Int(key.width), Int(key.height), inkChargedBytes, context.data == nil ? 0 : 1)
                }
                #endif
                return true
            }
        }
        if inkAttempt != key, let renderer = inkRenderer {
            inkAttempt = key
            let root = note.snapshot()
            let frame = FrameToken(sceneId: root.scene.id, sceneRevision: root.scene.revision, viewportVersion: inkGeneration,
                sessionGeneration: navigationEpoch, surfaceGeneration: inkGeneration, requestSequence: inkGeneration)
            inkPending = try! renderer.submitPage(scene: root.scene, frame: frame, layer: note.currentPage.layerId,
                originX: key.originX, originY: key.originY, width: key.width, height: key.height, pixelSize: key.pixelSize,
                nativeAllowance: Int64(key.width) * Int64(key.height) * 8, workingBytes: 2 * 1024 * 1024,
                maxEdgeTests: 100000000, beforeCompute: beforeInkCompute)
            inkRetryOnProgress = inkPending == nil
        }
        return false
    }
    private let beforeOpenRead: () throws -> Void
    init(directory: URL? = nil, beforeInkCompute: @escaping () throws -> Void = {},
         inkReleaseBoundary: @escaping (Bool) throws -> Void = { _ in }, documentBudget: DocumentBudget? = nil, beforeOpenRead: @escaping () throws -> Void = {}) {
        precondition(Thread.isMainThread)
        self.beforeOpenRead = beforeOpenRead
        self.documentBudget = documentBudget
        self.beforeInkCompute = beforeInkCompute; self.inkReleaseBoundary = inkReleaseBoundary
        self.directory = directory ?? FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("PreviewNotes", isDirectory: true)
        refresh()
    }
    private static func file(_ id: String, directory: URL) -> URL {
        precondition(id.range(of: "^[a-zA-Z0-9-]{1,128}$", options: .regularExpression) != nil)
        return directory.appendingPathComponent(id).appendingPathExtension("ncp")
    }
    private func request<Input, Output>(input: Input, bytes: Int64, compute: @escaping (Input) throws -> Output,
                                       rejected: () -> Void, receive: @escaping (AppleWorkResult<Output>) -> Void) {
        let admission = try! executor.submit(input: input, chargedBytes: bytes, compute: compute, receive: { _, result in
            receive(result); return .acknowledged
        })
        if case .rejected = admission { rejected() }
    }
    private var previewBudget: DocumentBudget {
        documentBudget ?? DocumentBudget(maxPages: 128, maxObjects: 5000, liveBytes: 8 * 1024 * 1024,
            journalEntries: 4096, journalBytes: 32 * 1024 * 1024, identityBytes: 4 * 1024 * 1024,
            historyEntries: 128, historyBytes: 8 * 1024 * 1024, inputBytes: 2 * 1024 * 1024)
    }
    func adopt(_ root: DocumentRoot, persisted: Bool, recovery: PagedPreviewSnapshot? = nil) throws {
        precondition(Thread.isMainThread && current == nil)
        let budget = previewBudget
        let checkpoint = try recovery ?? PagedPreviewSnapshot.companion.fromRoot(root: root)
        guard checkpoint.root === root else { throw CocoaError(.fileReadCorruptFile) }
        let opening = PagedPreviewNotebook.companion.openRecovery(snapshot: checkpoint, sessionNonce: UUID().uuidString, budget: budget,
            ownerCheck: { precondition(Thread.isMainThread) })
        guard let notebook = opening.notebook else { throw CocoaError(.fileReadCorruptFile) }
        current = notebook
        savedRoot = persisted && ledger.latest(documentId: root.id.value) == nil ? root : nil
        savedPending = savedRoot != nil ? recovery?.pending : nil
        revision = root.revision
    }
    private static func readPreview(_ encoded: String) throws -> DocumentRoot {
        let read = PreviewDocumentReader.shared.read(text: encoded)
        guard let root = read.root else { throw CocoaError(.fileReadCorruptFile) }
        return root
    }
    private static func validateRenderable(_ root: DocumentRoot) throws {
        guard PreviewDocumentReader.shared.accept(root: root).failure == nil
        else { throw CocoaError(.fileReadUnsupportedScheme) }
    }
    func create() {
        precondition(Thread.isMainThread)
        guard !ownerRetiring, current == nil else { return }
        let opening = PagedPreviewNotebook.companion.createValidated(documentId: UUID().uuidString, title: "未命名笔记",
            sessionNonce: UUID().uuidString, width: 595, height: 842, paper: .blank, budget: previewBudget,
            ownerCheck: { precondition(Thread.isMainThread) })
        guard let note = opening.notebook else { status = "新建失败，已有笔记仍保留"; return }
        navigationEpoch += 1
        current = note; savedRoot = nil; savedPending = nil; revision = note.changeVersion
        save()
    }
    private func overlay(_ disk: [LocalNote]) -> [LocalNote] {
        let retained = ledger.states().map { LocalNote(id: $0.snapshot.documentId, title: $0.snapshot.title + "（未保存）", date: Date()) }
        let ids = Set(retained.map { $0.id })
        return (disk.filter { !ids.contains($0.id) } + retained).sorted { $0.date > $1.date }
    }
    func refresh() {
        guard !ownerRetiring else { return }
        request(input: directory, bytes: 2 * 1024 * 1024, compute: { root in
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
            return try PreviewFileIO.catalog(root).map { url -> LocalNote in
                let date = (try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
                let id = url.deletingPathExtension().lastPathComponent
                let title: String
                if let encoded = try? PreviewFileIO.readHeaderUTF8(url),
                   let header = try? PreviewRecoveryCodec.shared.header(text: encoded), header.documentId == id {
                    title = header.title
                } else { title = "待恢复的笔记" }
                return LocalNote(id: id, title: title, date: date)
            }
        }, rejected: { notes = overlay(notes); catalogError = true; status = "后台队列繁忙，请重新打开列表" }) { result in
            switch result {
            case .succeeded(let disk): self.notes = self.overlay(disk); self.catalogError = false
            case .outcomeUnknown: self.notes = self.overlay(self.notes); self.catalogError = true; self.status = "列表读取失败，原文件与未保存内容仍保留"
            }
        }
    }
    func open(_ id: String) {
        guard !ownerRetiring, current == nil else { return }
        navigationEpoch += 1
        let navigation = navigationEpoch, epoch = storageEpoch
        let retained = ledger.latest(documentId: id)
        status = "正在打开…"
        request(input: (Self.file(id, directory: directory), id, retained, beforeOpenRead), bytes: PagedPreviewCodec.shared.maximumDecodingWorkByteCharge(), compute: { input in
            if let retained = input.2 as? PagedPreviewSnapshot { return PreviewFileIO.acceptRecovery(retained, documentId: input.1) }
            guard input.2 == nil else { throw CocoaError(.fileReadCorruptFile) }
            return try PreviewFileIO.readRecovery(input.0, documentId: input.1, beforeRead: input.3)
        }, rejected: { status = "后台队列繁忙，请重新打开笔记" }) { result in
            guard self.navigationEpoch == navigation && self.current == nil else { return }
            if self.storageEpoch != epoch {
                // Retry the pure read after ACK; no old notebook payload is placed on a callback queue.
                DispatchQueue.main.async { self.open(id) }; return
            }
            switch result {
            case .succeeded(let reading):
                let captured: PagedPreviewSnapshot
                switch reading {
                case .opened(let snapshot): captured = snapshot
                case .refused(let failure): self.status = failure.message; return
                }
                do { try self.adopt(captured.root, persisted: retained == nil, recovery: captured) }
                catch { self.status = "打开失败，原文件仍保留"; return }
                let note = self.current!
                self.status = note.hasPendingStroke ? "已恢复未提交笔迹，请点击保存重试提交" : (retained == nil ? "已打开 · \(note.totalStrokeCount) 笔" : "有更改尚未保存，请重试保存")
            case .outcomeUnknown: self.status = "读取结果暂无法确认，请稍后重试"
            }
        }
    }
    func changed() { revision = current?.changeVersion ?? 0; save() }
    func prepareExport() {
        guard !ownerRetiring, !transferBusy, exportDocument == nil, let note = current else { return }
        note.cancelStroke()
        guard note.retryPendingStroke() else { status = note.lastError; return }
        transferBusy = true; status = "正在准备备份…"
        let snapshot = note.capture()
        request(input: snapshot, bytes: snapshot.encodingWorkByteCharge, compute: { captured in
            let encoded = try captured.encode()
            guard encoded.utf16.count <= Int(PagedPreviewCodec.shared.maximumCharacterCount()),
                  encoded.utf8.count <= PreviewFileIO.maxReadBytes else { throw CocoaError(.fileWriteOutOfSpace) }
            return PreviewBackup(data: Data(encoded.utf8))
        }, rejected: { transferBusy = false; status = "后台队列繁忙，请重新导出" }) { result in
            self.transferBusy = false
            switch result {
            case .succeeded(let document): self.exportDocument = document
            case .outcomeUnknown: self.status = "备份生成失败，笔记仍保留，请重试"
            }
        }
    }
    func importCopy(_ url: URL) {
        guard !ownerRetiring, !transferBusy else { return }
        transferBusy = true; status = "正在校验并导入…"
        let scoped = url.startAccessingSecurityScopedResource()
        let id = UUID().uuidString, navigation = navigationEpoch
        request(input: (url, scoped, Self.file(id, directory: directory), id), bytes: PagedPreviewCodec.shared.maximumDecodingWorkByteCharge(), compute: { input in
            defer { if input.1 { input.0.stopAccessingSecurityScopedResource() } }
            let encoded = try PreviewFileIO.readUTF8(input.0)
            let source = try Self.readPreview(encoded)
            let pages = Dictionary(uniqueKeysWithValues: source.pages().map { ($0.id, PageId(value: UUID().uuidString)) })
            let layers = Dictionary(uniqueKeysWithValues: source.pages().map { ($0.layerId, LayerId(value: UUID().uuidString)) })
            let objects = Dictionary(uniqueKeysWithValues: source.scene.objects().map { ($0.id, ObjectId(value: UUID().uuidString)) })
            let root = try PagedPreviewCodec.shared.doCopyAsNew(source: source, documentId: SceneId(value: input.3),
                pageIds: pages, layerIds: layers, objectIds: objects)
            guard !FileManager.default.fileExists(atPath: input.2.path) else { throw CocoaError(.fileWriteFileExists) }
            try PreviewFileIO.writeUTF8(PagedPreviewCodec.shared.encode(root: root), to: input.2)
            return root
        }, rejected: {
            if scoped { url.stopAccessingSecurityScopedResource() }
            transferBusy = false; status = "后台队列繁忙，请重新导入"
        }) { result in
            self.transferBusy = false
            switch result {
            case .succeeded(let root):
                if self.current == nil && self.navigationEpoch == navigation {
                    self.navigationEpoch += 1
                    do { try self.adopt(root, persisted: true) }
                    catch { self.status = "副本已保存在本机，暂时无法打开，请返回列表重试"; return }
                    let note = self.current!
                    self.status = "已导入为新笔记 · \(note.totalStrokeCount) 笔"
                } else { self.status = "备份已导入为新笔记，可返回列表查看" }
                self.refresh()
            case .outcomeUnknown: self.status = "导入失败：文件无效、超过32MB或本机无法保存；已有笔记未覆盖"
            }
        }
    }
    @discardableResult func save() -> Bool {
        guard let note = current else { return true }
        _ = note.retryPendingStroke()
        revision = note.changeVersion
        let snapshot = note.captureRecovery(), previous = ledger.latest(documentId: note.documentId)
        if savedRoot === snapshot.root && savedPending === snapshot.pending && previous == nil {
            status = note.hasPendingStroke ? "未提交笔迹已保存为恢复记录，请重试提交" : "已保存到本机 · \(note.totalStrokeCount) 笔"; return true
        }
        let admission = ledger.offer(snapshot: snapshot)
        guard admission == .accepted else {
            status = admission == .versionConflict ? "保存内容发生冲突，请保留当前笔记并导出备份" : "保存队列已满或内容版本过期，请保留当前笔记并重试"; return false
        }
        if previous == nil || !previous!.sameContent(other: snapshot) { storageEpoch += 1 }
        status = "正在保存… · \(note.totalStrokeCount) 笔"
        pumpWrites(); return true
    }
    private func pumpWrites() {
        while true {
            let worker = try! executor.status()
            let dispatch = ledger.admitNextWrite(availableWorkBytes: storageWorkBytes - worker.chargedBytes,
                maximumWorkBytes: storageWorkBytes, backendAccepting: worker.accepting)
            guard let attempt = dispatch.attempt else {
                if dispatch.disposition != .idle && dispatch.disposition != .backpressure {
                    status = dispatch.disposition == .workCapacity
                        ? "保存任务超出后台容量，更改仍保留，请保留当前笔记"
                        : "保存后端暂不可用，更改仍保留，请重试"
                }
                return
            }
            let admission = try! executor.submit(input: (attempt, Self.file(attempt.snapshot.documentId, directory: directory)),
                chargedBytes: attempt.snapshot.encodingWorkByteCharge, compute: { input in
                    try PreviewFileIO.writeUTF8(input.0.snapshot.encode(), to: input.1)
                    return true
                }, receive: { input, result in
                    let success: Bool
                    if case .succeeded = result { success = true } else { success = false }
                    precondition(self.ledger.complete(attempt: input.0, success: success))
                    let snapshot = input.0.snapshot
                    if success && self.current?.documentId == snapshot.documentId {
                        self.savedRoot = (snapshot as? PagedPreviewSnapshot)?.root
                        self.savedPending = (snapshot as? PagedPreviewSnapshot)?.pending
                    }
                    if let current = self.current, current.documentId == snapshot.documentId,
                       snapshot.sameContent(other: current.captureRecovery()) {
                        self.status = success ? (current.hasPendingStroke ? "未提交笔迹已保存为恢复记录，请重试提交" : "已保存到本机 · \(current.totalStrokeCount) 笔")
                            : "保存失败，更改仍保留在本机会话，请重试"
                    } else if self.current == nil && !success { self.status = "保存失败，更改仍保留，可从列表重新打开并重试" }
                    return .acknowledged // Failure is in the owner ledger before releasing worker responsibility.
                })
            if case .rejected(let reason) = admission {
                precondition(ledger.returnUnstarted(attempt: attempt))
                if reason == .stopped { status = "保存后端暂不可用，更改仍保留，请重试" }
                return
            }
        }
    }
    func close() {
        current?.cancelStroke()
        guard save(), let note = current else { return }
        if note.hasPendingStroke {
            guard savedRoot === note.snapshot(), savedPending === note.pendingStroke(), ledger.latest(documentId: note.documentId) == nil,
                  note.retireAfterRecovery(snapshot: note.captureRecovery()) else { return }
        } else { guard note.retire() else { return } }
        retireInk()
        navigationEpoch += 1; current = nil; savedRoot = nil; savedPending = nil; refresh()
    }
    func flushForBackground() { current?.cancelStroke(); save(); retireInk() }
    func applyEdit(_ request: PreviewEdit, content: Bool = true) {
        editPage(content: content) { $0.edit(request: request).accepted }
    }
    func editPage(content: Bool = true, _ edit: (PagedPreviewNotebook) -> Bool) {
        guard let note = current else { return }
        guard edit(note) else { status = note.lastError; return }
        revision = note.changeVersion // Published even when only the selected PageId changes.
        if content { save() }
    }
}

@main
struct NoteCorePreviewApp: App {
    @StateObject private var model = PreviewModel()
    @Environment(\.scenePhase) private var scenePhase
    var body: some Scene {
        WindowGroup { PreviewRoot(model: model).preferredColorScheme(.light) }
            .onChange(of: scenePhase) { _, phase in
                if phase != .active { model.flushForBackground() } else { model.resumeFromBackground() }
            }
    }
}

struct PreviewRoot: View {
    @ObservedObject var model: PreviewModel
    @State private var color: Int32 = Int32(bitPattern: 0xff253247)
    @State private var width: Double = 2.5
    @State private var finger = true
    @State private var renaming = false
    @State private var title = ""
    @State private var importing = false
    @State private var exporting = false
    @State private var deletingPage = false
    @State private var viewportRequest = 0
    @State private var viewportRatio = 0.0
    var body: some View {
        Group {
            if let note = model.current {
                VStack(spacing: 8) {
                    HStack {
                        Button("返回") { model.close() }
                        Spacer()
                        Button(note.title) { note.cancelStroke(); title = note.title; renaming = true }.font(.headline).lineLimit(1)
                        Spacer(); Button("保存") { model.retryRetainedWrites(); model.save() }
                        Button("导出") { model.prepareExport() }.disabled(model.transferBusy)
                    }.padding(.horizontal)
                    HStack {
                        Button("撤销") { model.applyEdit(PreviewEditUndo.shared) }.disabled(!note.canUndo)
                        Button("重做") { model.applyEdit(PreviewEditRedo.shared) }.disabled(!note.canRedo)
                        Spacer(); Toggle("手指书写", isOn: $finger).fixedSize()
                    }.padding(.horizontal)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 20) {
                            ForEach([UInt32(0xff253247), 0xff3568be, 0xffce4d52, 0xff21816b], id: \.self) { raw in
                                Button { note.cancelStroke(); color = Int32(bitPattern: raw) } label: {
                                    Circle().fill(Color(uiColor: inkColor(Int32(bitPattern: raw)))).frame(width: 25, height: 25)
                                        .overlay(Circle().stroke(color == Int32(bitPattern: raw) ? .gray : .clear, lineWidth: 3))
                                }.accessibilityLabel("笔颜色")
                            }
                            ForEach([2.5, 5.0, 9.0], id: \.self) { value in
                                Button(value == 2.5 ? "细" : value == 5 ? "中" : "粗") { note.cancelStroke(); width = value }
                            }
                        }.padding(.horizontal)
                    }.frame(height: 36)
                    HStack(spacing: 20) {
                        Button("缩小") { viewportRatio = 1.0 / 1.5; viewportRequest += 1 }
                        Button("放大") { viewportRatio = 1.5; viewportRequest += 1 }
                        Button("适合页面") { viewportRatio = 0; viewportRequest += 1 }
                        Text("双指缩放").font(.caption).foregroundStyle(.secondary)
                    }.padding(.horizontal)
                    HStack(spacing: 8) {
                        Button { model.applyEdit(PreviewEditSelectPage(pageId: note.page(index: note.currentPageIndex - 1).id.value), content: false) } label: {
                            Image(systemName: "chevron.left").frame(width: 44, height: 44)
                        }.accessibilityLabel("上一页").disabled(note.currentPageIndex == 0)
                        Text("\(note.currentPageIndex + 1)/\(note.pageCount)").monospacedDigit().fixedSize()
                            .accessibilityLabel("当前页码").accessibilityValue("\(note.currentPageIndex + 1)/\(note.pageCount)")
                        Button { model.applyEdit(PreviewEditSelectPage(pageId: note.page(index: note.currentPageIndex + 1).id.value), content: false) } label: {
                            Image(systemName: "chevron.right").frame(width: 44, height: 44)
                        }.accessibilityLabel("下一页").disabled(note.currentPageIndex == note.pageCount - 1)
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 20) {
                                Button("加页") { model.applyEdit(PreviewEditInsertPage.shared) }
                                Button("复制页") { model.applyEdit(PreviewEditCopyPage.shared) }
                                Button("删页") { note.cancelStroke(); deletingPage = true }
                                Button("前移") { model.applyEdit(PreviewEditMovePage(beforePageId: note.page(index: note.currentPageIndex - 1).id.value)) }
                                    .disabled(note.currentPageIndex == 0)
                                Button("后移") { model.applyEdit(PreviewEditMovePage(beforePageId: note.currentPageIndex + 2 < note.pageCount ? note.page(index: note.currentPageIndex + 2).id.value : nil)) }.disabled(note.currentPageIndex == note.pageCount - 1)
                                Menu("纸张") {
                                    Button("空白") { model.applyEdit(PreviewEditSetPaper(width: note.currentPage.width, height: note.currentPage.height, paper: .blank)) }
                                    Button("横线") { model.applyEdit(PreviewEditSetPaper(width: note.currentPage.width, height: note.currentPage.height, paper: .ruled)) }
                                    Button("方格") { model.applyEdit(PreviewEditSetPaper(width: note.currentPage.width, height: note.currentPage.height, paper: .grid)) }
                                    Button("点阵") { model.applyEdit(PreviewEditSetPaper(width: note.currentPage.width, height: note.currentPage.height, paper: .dots)) }
                                }
                            }.padding(.trailing)
                        }
                    }.padding(.horizontal).frame(minHeight: 44)
                    Text(model.status).font(.caption).foregroundStyle(.secondary)
                    NativeInkCanvas(note: note, renderModel: model, color: color, width: width, finger: finger, revision: model.revision, viewportRequest: viewportRequest, viewportRatio: viewportRatio,
                        changed: { model.changed() }, failed: { model.status = $0 })
                        .id(note.documentId)
                        .padding(.horizontal, 12).padding(.bottom, 8)
                }.padding(.top, 8)
            } else {
                NavigationStack {
                    List {
                        Section {
                            Button("新建笔记") { model.create() }
                            Button("导入备份") { importing = true }.disabled(model.transferBusy)
                        }
                        Section("本机笔记") {
                            ForEach(model.notes) { note in
                                Button { model.open(note.id) } label: {
                                    VStack(alignment: .leading) { Text(note.title).font(.headline); Text(note.date, style: .date).font(.caption).foregroundStyle(.secondary) }
                                }
                            }
                            if model.notes.isEmpty { Text(model.catalogError ? "暂时无法读取笔记，请稍后重试。" : "还没有笔记，写下第一个想法。").foregroundStyle(.secondary) }
                        }
                        if !model.status.isEmpty { Text(model.status).font(.caption) }
                    }.navigationTitle("NoteCore").refreshable { model.refresh() }
                }
            }
        }
        .alert("笔记名称", isPresented: $renaming) {
            TextField("名称", text: $title)
            Button("取消", role: .cancel) { }
            Button("完成") { model.applyEdit(PreviewEditRename(title: title)) }
        }
        .alert("删除当前页？", isPresented: $deletingPage) {
            Button("取消", role: .cancel) { }
            Button("删除", role: .destructive) { model.applyEdit(PreviewEditDeletePage.shared) }
        } message: { Text("最后一页将清空。可以撤销恢复；关闭笔记后不保留撤销记录。") }
        .onChange(of: model.exportDocument != nil) { _, ready in if ready { exporting = true } }
        .fileExporter(isPresented: $exporting, document: model.exportDocument, contentType: .data,
            defaultFilename: "NoteCore-backup.ncp") { result in
                model.exportDocument = nil
                switch result {
                case .success: model.status = "备份已导出"
                case .failure: model.status = "导出未完成，请重试"
                }
            }
        .onChange(of: exporting) { _, showing in if !showing { model.exportDocument = nil } }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.data]) { result in
            switch result {
            case .success(let url): model.importCopy(url)
            case .failure: model.status = "导入未完成，已有笔记未更改"
            }
        }
    }
}

func inkColor(_ value: Int32) -> UIColor {
    let color = UInt32(bitPattern: value)
    return UIColor(red: CGFloat((color >> 16) & 255) / 255, green: CGFloat((color >> 8) & 255) / 255,
        blue: CGFloat(color & 255) / 255, alpha: CGFloat((color >> 24) & 255) / 255)
}
