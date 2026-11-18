import SwiftUI
import UniformTypeIdentifiers
import NoteCanvasPrototype

struct PreviewBackup: FileDocument {
    static var readableContentTypes: [UTType] { [.data] }
    let data: Data
    init(data: Data) { self.data = data }
    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents else { throw CocoaError(.fileReadCorruptFile) }
        self.data = data
    }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: data) }
}

private enum PreparedCatalog { case complete([LocalNote]), capacity }

private enum PreparedExport { case ready(PreviewBackup), capacity }

private enum NotebookAdoptionFailure: Error {
    case inputCapacity, documentCapacity

    static func error(for failure: PreviewOpenFailure?) -> Error {
        switch failure {
        case .inputCapacity: return Self.inputCapacity
        case .documentCapacity: return Self.documentCapacity
        default: return CocoaError(.fileReadCorruptFile)
        }
    }

    var message: String {
        switch self {
        case .inputCapacity: return "未提交笔迹超过当前恢复容量，原文件仍保留"
        case .documentCapacity: return "笔记超过当前编辑容量，原文件仍保留"
        }
    }
}

struct LocalNote: Identifiable { let id: String; let title: String; let date: Date }
enum PreviewOwnerRetirementRequest { case started, alreadyRetiring, retired, inputPending, transferPending, saveRejected, windowPending }
enum PreviewEditorDeparture { case detached, documentClosed, retryRequired, stale }
enum PreviewOwnerPhase { case active, retiring, retired }
struct PreviewOwnerRetirementStatus {
    let phase: PreviewOwnerPhase
    let pendingWrites: Int
    let inFlightWrites: Int
    let retryRequired: Int
    let renderBytes: Int64
    let workerStopped: Bool
}

final class PreviewModel: ObservableObject {
    @Published private(set) var notes: [LocalNote] = []
    @Published var catalogError = false
    @Published private(set) var current: PagedPreviewNotebook?
    @Published var status = ""
    @Published var revision: Int64 = 0
    @Published private(set) var exportDocument: PreviewBackup? {
        didSet {
            if oldValue != nil && exportDocument == nil {
                pumpWrites(); retryInkAfterCapacityChange()
            }
        }
    }
    private let catalogResultByteLimit: Int64
    var catalogRetainedByteCharge: Int64 {
        precondition(Thread.isMainThread)
        return PreviewCatalogBudget.shared.resultByteCharge(rows: Int32(notes.count),
            textCodeUnits: notes.reduce(0) { $0 + Int64($1.id.utf16.count + $1.title.utf16.count) })
    }
    private let exportResultByteLimit: Int
    var exportRetainedByteCharge: Int {
        precondition(Thread.isMainThread)
        return exportDocument.map { 512 + $0.data.count } ?? 0
    }
    func finishExport() {
        precondition(Thread.isMainThread)
        let cancelledRequest = exportRequest != nil
        exportEditor = nil; exportRequest = nil
        exportDocument = nil
        // Sheet dismissal may have no result callback. Do not leave a finished export "preparing".
        // A matching success/failure callback supplies its own outcome after this cleanup.
        if cancelledRequest { status = "导出已取消，原笔记仍保留" }
    }
    func finishEditorExport(_ identity: UUID, request: UUID?) {
        precondition(Thread.isMainThread)
        if let request, exportEditor == identity, exportRequest == request { finishExport() }
    }
    @Published var transferBusy = false
    private let directory: URL
    private let beforeInkCompute: () throws -> Void
    private let inkReleaseBoundary: (Bool) throws -> Void
    private let ledger = PreviewSaveLedger(maxDocuments: 8, byteBudget: 32 * 1024 * 1024,
        ownerCheck: { precondition(Thread.isMainThread) })
    private let storageWorkBytes: Int64
    // Identity-only saved shortcut; the authority/ledger own content while it is needed.
    private weak var savedRoot: DocumentRoot?
    private weak var savedPending: DocumentCommandCanvas?
    private let documentBudget: DocumentBudget?
    private var navigationEpoch: Int64 = 0
    private lazy var executor = try! AppleBoundedOwnerExecutor(maxEntries: 16, maxBytes: storageWorkBytes,
        retainedOwnerBytes: { [weak self] in Int64(self?.exportRetainedByteCharge ?? 0) },
        onOwnerProgress: { [weak self] in self?.inkOwnerProgress(); self?.pumpWrites(); self?.advanceOwnerRetirement() })
    private var ownerRetiring = false
    private var ownerRetired = false
    private var ownerStopRequested = false
    private var editors = Set<UUID>()
    var editorCount: Int { precondition(Thread.isMainThread); return editors.count }
    private(set) var exportEditor: UUID?
    private(set) var exportRequest: UUID?
    // Explicit service lifetime: ARC reference loss is not a close/release receipt.
    // The caller must retain a route to this owner and explicitly retire it.
    private var ownerKeepAlive: PreviewModel?
    private var retirementObserver: ((PreviewOwnerRetirementStatus) -> Void)?
    var ownerRetirementStatus: PreviewOwnerRetirementStatus {
        precondition(Thread.isMainThread)
        let saves = ledger.states()
        return PreviewOwnerRetirementStatus(phase: ownerRetired ? .retired : ownerRetiring ? .retiring : .active,
            pendingWrites: saves.count, inFlightWrites: saves.filter { $0.inFlight }.count,
            retryRequired: saves.filter { $0.retryRequired }.count,
            renderBytes: inkChargedBytes, workerStopped: (try! executor.status()).workerStopped)
    }
    @discardableResult func beginOwnerRetirement(
        onRetired: ((PreviewOwnerRetirementStatus) -> Void)? = nil
    ) -> PreviewOwnerRetirementRequest {
        precondition(Thread.isMainThread)
        if ownerRetired { return .retired }
        if ownerRetiring { return .alreadyRetiring }
        if !editors.isEmpty { return .windowPending }
        if current?.hasActiveStroke == true || current?.hasPendingStroke == true { return .inputPending }
        if transferBusy || exportDocument != nil { return .transferPending }
        guard save() else { return .saveRejected }
        // No more document/transfer admission; accepted IO still uses the same worker and ledger.
        ownerRetiring = true; retirementObserver = onRetired
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
        notes = []
        let observer = retirementObserver; retirementObserver = nil
        observer?(ownerRetirementStatus)
        ownerKeepAlive = nil
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
        retryInkAfterCapacityChange()
    }
    private func retryInkAfterCapacityChange() {
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
    private let observeWriteBoundary: (PreviewWriteBoundary) -> Void
    private let beforeOpenRead: () throws -> Void
    init(directory: URL? = nil, beforeInkCompute: @escaping () throws -> Void = {},
         inkReleaseBoundary: @escaping (Bool) throws -> Void = { _ in }, documentBudget: DocumentBudget? = nil,
         storageWorkBytes: Int64 = 256 * 1024 * 1024, exportResultByteLimit: Int = 32 * 1024 * 1024, catalogResultByteLimit: Int64 = 2 * 1024 * 1024, beforeOpenRead: @escaping () throws -> Void = {}, observeWriteBoundary: @escaping (PreviewWriteBoundary) -> Void = { _ in }) {
        precondition(Thread.isMainThread)
        precondition(storageWorkBytes > 0)
        self.storageWorkBytes = storageWorkBytes
        precondition(exportResultByteLimit >= 0)
        precondition(catalogResultByteLimit >= 0)
        self.catalogResultByteLimit = catalogResultByteLimit
        self.exportResultByteLimit = exportResultByteLimit
        self.beforeOpenRead = beforeOpenRead
        self.observeWriteBoundary = observeWriteBoundary
        self.documentBudget = documentBudget
        self.beforeInkCompute = beforeInkCompute; self.inkReleaseBoundary = inkReleaseBoundary
        self.directory = directory ?? FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("PreviewNotes", isDirectory: true)
        ownerKeepAlive = self
        refresh()
    }
    private static func file(_ id: String, directory: URL) -> URL {
        precondition(PreviewFileIO.isLocalDocumentId(id))
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
        documentBudget ?? DocumentBudget.companion.validated(maxPages: 128, maxObjects: 5000, liveBytes: 8 * 1024 * 1024,
            journalEntries: 4096, journalBytes: 32 * 1024 * 1024, identityBytes: 4 * 1024 * 1024,
            historyEntries: 128, historyBytes: 8 * 1024 * 1024, inputBytes: 2 * 1024 * 1024).budget! // Trusted fixed policy; raw callers inspect failure.
    }
    func adopt(_ root: DocumentRoot, persisted: Bool, recovery: PagedPreviewSnapshot? = nil) throws {
        precondition(Thread.isMainThread && current == nil)
        let notebook = try prepareNotebook(root, recovery: recovery)
        installNotebook(notebook, root: root, persisted: persisted, recovery: recovery)
    }
    private func prepareNotebook(_ root: DocumentRoot, recovery: PagedPreviewSnapshot?) throws -> PagedPreviewNotebook {
        let budget = previewBudget
        let checkpoint = try recovery ?? PagedPreviewSnapshot.companion.fromRoot(root: root)
        guard checkpoint.root === root else { throw CocoaError(.fileReadCorruptFile) }
        let opening = PagedPreviewNotebook.companion.openRecovery(snapshot: checkpoint, sessionNonce: UUID().uuidString, budget: budget,
            ownerCheck: { precondition(Thread.isMainThread) })
        guard let notebook = opening.notebook else { throw NotebookAdoptionFailure.error(for: opening.failure) }
        return notebook
    }
    private func installNotebook(_ notebook: PagedPreviewNotebook, root: DocumentRoot, persisted: Bool, recovery: PagedPreviewSnapshot?) {
        precondition(current == nil)
        current = notebook
        savedRoot = persisted && ledger.latest(documentId: root.id.value) == nil ? root : nil
        savedPending = savedRoot != nil ? recovery?.pending : nil
        revision = root.revision
    }
    func attachEditor(_ identity: UUID, documentId: String) -> Bool {
        precondition(Thread.isMainThread)
        guard !ownerRetiring, current?.documentId == documentId,
              editors.contains(identity) || editors.count < 8 else {
            status = "编辑窗口已满或会话已切换，请返回后重试"; return false
        }
        editors.insert(identity); return true
    }
    func hasEditor(_ identity: UUID) -> Bool { precondition(Thread.isMainThread); return editors.contains(identity) }
    func forgetEditor(_ identity: UUID) { precondition(Thread.isMainThread); editors.remove(identity) }
    func leaveEditor(_ identity: UUID, documentId: String) -> PreviewEditorDeparture {
        precondition(Thread.isMainThread)
        guard current?.documentId == documentId, editors.contains(identity) else { return .stale }
        if editors.count > 1 {
            guard save() else { return .retryRequired }
            editors.remove(identity); return .detached
        }
        guard !transferBusy, exportDocument == nil else { status = "请先完成或取消文件操作"; return .retryRequired }
        editors.remove(identity)
        guard prepareCurrentClose(cancelInput: false) else {
            editors.insert(identity); status = "原笔记尚待保存，请保存重试后返回"; return .retryRequired
        }
        retireCurrent(); return .documentClosed
    }
    func createForEditor() -> PagedPreviewNotebook? {
        precondition(Thread.isMainThread)
        guard !ownerRetiring, editors.isEmpty, !transferBusy, exportDocument == nil,
              prepareCurrentClose(cancelInput: false) else {
            status = "其他窗口正在编辑，或原笔记尚待保存，请完成后重试"; return nil
        }
        if current != nil { retireCurrent() }
        create(); return current
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
    private var catalogFailureStatus: String?
    private func showCatalogFailure(_ message: String) {
        catalogError = true; catalogFailureStatus = message; status = message
    }
    private func installCatalog(_ disk: [LocalNote], failure: String? = nil) {
        precondition(Thread.isMainThread)
        let retained = ledger.states().map { LocalNote(id: $0.snapshot.documentId, title: $0.snapshot.title + "（未保存）", date: Date()) }
        let ids = Set(retained.map { $0.id })
        var rows = retained.count
        var text = retained.reduce(Int64(0)) { $0 + Int64($1.id.utf16.count + $1.title.utf16.count) }
        var valid = retained.allSatisfy { (1...128).contains($0.id.utf16.count) && $0.title.utf16.count <= 125 }
        for row in disk where !ids.contains(row.id) {
            rows += 1; text += Int64(row.id.utf16.count + row.title.utf16.count)
            valid = valid && (1...128).contains(row.id.utf16.count) && row.title.utf16.count <= 125
        }
        guard valid, rows <= Int(PreviewCatalogBudget.shared.maxOwnerRows),
              PreviewCatalogBudget.shared.resultByteCharge(rows: Int32(rows), textCodeUnits: text) <= catalogResultByteLimit else {
            showCatalogFailure("列表超过预览元数据容量，原列表与文件仍保留"); return
        }
        notes = (disk.filter { !ids.contains($0.id) } + retained).sorted { $0.date > $1.date }
        if let failure { showCatalogFailure(failure) }
        else {
            if status == catalogFailureStatus { status = "" }
            catalogError = false; catalogFailureStatus = nil
        }
    }
    func refresh() {
        guard !ownerRetiring else { return }
        let pathUnits = directory.path.utf16.count
        guard pathUnits <= Int32.max else { showCatalogFailure("列表路径超过预览容量，原列表仍保留"); return }
        request(input: directory, bytes: PreviewCatalogBudget.shared.workByteCharge(directoryPathCodeUnits: Int32(pathUnits)), compute: { root -> PreparedCatalog in
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
            let urls: [URL]
            do { urls = try PreviewFileIO.catalog(root) }
            catch is CatalogCapacityRefusal { return .capacity }
            return .complete(urls.map { url -> LocalNote in
                let date = (try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
                let id = url.deletingPathExtension().lastPathComponent
                let title: String
                if let encoded = try? PreviewFileIO.readHeaderUTF8(url),
                   let header = try? PreviewRecoveryCodec.shared.header(text: encoded), header.documentId == id {
                    title = header.title
                } else { title = "待恢复的笔记" }
                return LocalNote(id: id, title: title, date: date)
            })
        }, rejected: { installCatalog(notes, failure: "后台队列繁忙，请重试读取笔记") }) { result in
            switch result {
            case .succeeded(let prepared):
                switch prepared {
                case .complete(let disk): self.installCatalog(disk)
                case .capacity: self.installCatalog(self.notes, failure: "列表超过预览记录容量，原列表与文件仍保留")
                }
            case .outcomeUnknown: self.installCatalog(self.notes, failure: "列表读取失败，原文件与未保存内容仍保留")
            }
        }
    }
    func open(_ id: String) {
        openDocument(id, replacingIdle: false, isRequested: { true }, opened: nil)
    }
    func openForEditor(_ id: String, isRequested: @escaping () -> Bool = { true },
                       opened: @escaping (PagedPreviewNotebook) -> Void) {
        precondition(Thread.isMainThread)
        guard !ownerRetiring, isRequested() else { return }
        if let note = current, note.documentId == id { opened(note); return }
        guard editors.isEmpty, !transferBusy, exportDocument == nil else {
            status = "其他窗口正在编辑或有文件操作，请完成后重试"; return
        }
        openDocument(id, replacingIdle: true, isRequested: isRequested, opened: opened)
    }
    private func openDocument(_ id: String, replacingIdle: Bool, isRequested: @escaping () -> Bool,
                              opened: ((PagedPreviewNotebook) -> Void)?) {
        guard !ownerRetiring, isRequested(), current == nil || (replacingIdle && editors.isEmpty) else { return }
        guard PreviewFileIO.isLocalDocumentId(id) else { status = LocalReadFailure.invalidLocalIdentity.message; return }
        let previous = current
        navigationEpoch += 1
        let navigation = navigationEpoch
        let retained = ledger.latest(documentId: id)
        let workBytes = ApplePreviewEditorAdmission.shared.openWorkByteCharge(retained: retained)
        status = "正在打开…"
        request(input: (Self.file(id, directory: directory), id, retained, beforeOpenRead), bytes: workBytes, compute: { input in
            if let retained = input.2 as? PagedPreviewSnapshot { return PreviewFileIO.acceptRecovery(retained, documentId: input.1) }
            guard input.2 == nil else { throw CocoaError(.fileReadCorruptFile) }
            return try PreviewFileIO.readRecovery(input.0, documentId: input.1, beforeRead: input.3)
        }, rejected: { status = "后台队列繁忙，请重新打开笔记" }) { result in
            guard self.navigationEpoch == navigation, self.current === previous, isRequested() else { return }
            let latest = self.ledger.latest(documentId: id)
            // A later same-note write cannot ACK ahead of this read on the serial worker.
            if let latest, latest !== retained {
                // Retry the pure read after ACK; no old notebook payload is placed on a callback queue.
                DispatchQueue.main.async { [weak self, weak previous] in
                    guard let self, self.navigationEpoch == navigation, self.current === previous else { return }
                    self.openDocument(id, replacingIdle: replacingIdle, isRequested: isRequested, opened: opened)
                }
                return
            }
            switch result {
            case .succeeded(let reading):
                let captured: PagedPreviewSnapshot
                switch reading {
                case .opened(let snapshot): captured = snapshot
                case .refused(let failure): self.status = failure.message; return
                }
                guard self.editors.isEmpty, (!replacingIdle || (!self.transferBusy && self.exportDocument == nil)),
                      self.current?.hasActiveStroke != true else {
                    self.status = "其他窗口正在编辑，或原笔记尚待保存，请完成后重试"; return
                }
                // A preceding write may ACK this exact retained snapshot before delivery.
                let persisted = latest == nil
                do {
                    // Saving can commit the current pending stroke. Check target admission before that side effect.
                    if self.current != nil,
                       let failure = ApplePreviewEditorAdmission.shared.failure(snapshot: captured, budget: self.previewBudget) {
                        throw NotebookAdoptionFailure.error(for: failure)
                    }
                    guard self.prepareCurrentClose(cancelInput: false) else {
                        self.status = "其他窗口正在编辑，或原笔记尚待保存，请完成后重试"; return
                    }
                    let notebook = try self.prepareNotebook(captured.root, recovery: captured)
                    if self.current != nil { self.retireCurrent() }
                    self.installNotebook(notebook, root: captured.root, persisted: persisted, recovery: captured)
                }
                catch let failure as NotebookAdoptionFailure { self.status = failure.message; return }
                catch { self.status = "打开失败，原文件仍保留"; return }
                let note = self.current!
                self.status = note.hasPendingStroke ? "已恢复未提交笔迹，请点击保存重试提交" : (persisted ? "已打开 · \(note.totalStrokeCount) 笔" : "有更改尚未保存，请重试保存")
                opened?(note)
            case .outcomeUnknown: self.status = "读取结果暂无法确认，请稍后重试"
            }
        }
    }
    func changed() { revision = current?.changeVersion ?? 0; save() }
    func prepareExport(editor: UUID? = nil) {
        guard !ownerRetiring, !transferBusy, exportDocument == nil, let note = current else { return }
        if let editor {
            guard editors.contains(editor), !note.hasActiveStroke else {
                status = "其他窗口正在输入，请完成后再导出"; return
            }
        }
        note.cancelStroke()
        guard note.retryPendingStroke() else { status = previewFailureMessage(note.lastFailure); return }
        let ticket = UUID()
        exportEditor = editor; exportRequest = ticket
        transferBusy = true; status = "正在准备备份…"
        let snapshot = note.capture(), resultLimit = exportResultByteLimit
        let navigation = navigationEpoch
        request(input: snapshot, bytes: snapshot.encodingWorkByteCharge, compute: { captured -> PreparedExport in
            let encoded = try captured.encode()
            // Count UTF8 without constructing Data; the owner keeps this result after worker ACK.
            guard encoded.utf16.count <= Int(PagedPreviewCodec.shared.maximumCharacterCount()), resultLimit >= 512,
                  encoded.utf8.count <= PreviewFileIO.maxReadBytes,
                  encoded.utf8.count <= resultLimit - 512 else { return .capacity }
            return .ready(PreviewBackup(data: Data(encoded.utf8)))
        }, rejected: { transferBusy = false; exportEditor = nil; exportRequest = nil; status = "后台队列繁忙，请重新导出" }) { result in
            self.transferBusy = false
            guard self.exportRequest == ticket else { return }
            guard self.navigationEpoch == navigation else {
                self.exportEditor = nil; self.exportRequest = nil; return
            }
            switch result {
            case .succeeded(let prepared):
                switch prepared {
                case .ready(let document): self.exportDocument = document
                case .capacity: self.exportEditor = nil; self.exportRequest = nil; self.status = "备份超过预览导出容量，原笔记仍保留"
                }
            case .outcomeUnknown: self.exportEditor = nil; self.exportRequest = nil; self.status = "备份生成失败，笔记仍保留，请重试"
            }
        }
    }
    func importCopy(_ url: URL, isRequested: @escaping () -> Bool = { true },
                    opened: ((PagedPreviewNotebook) -> Void)? = nil) {
        guard !ownerRetiring, !transferBusy, exportDocument == nil else { return }
        transferBusy = true; status = "正在校验并导入…"
        let scoped = url.startAccessingSecurityScopedResource()
        let id = UUID().uuidString, navigation = navigationEpoch
        request(input: (url, scoped, Self.file(id, directory: directory), id), bytes: PagedPreviewCodec.shared.maximumDecodingWorkByteCharge(), compute: { input -> Result<DocumentRoot, LocalReadFailure> in
            defer { if input.1 { input.0.stopAccessingSecurityScopedResource() } }
            let source: DocumentRoot
            switch try PreviewFileIO.readImport(input.0) {
            case .success(let root): source = root
            case .failure(let reason): return .failure(reason)
            }
            let pages = Dictionary(uniqueKeysWithValues: source.pages().map { ($0.id, PageId(value: UUID().uuidString)) })
            let layers = Dictionary(uniqueKeysWithValues: source.pages().map { ($0.layerId, LayerId(value: UUID().uuidString)) })
            let objects = Dictionary(uniqueKeysWithValues: source.scene.objects().map { ($0.id, ObjectId(value: UUID().uuidString)) })
            let root = try PagedPreviewCodec.shared.doCopyAsNew(source: source, documentId: SceneId(value: input.3),
                pageIds: pages, layerIds: layers, objectIds: objects)
            guard !FileManager.default.fileExists(atPath: input.2.path) else { throw CocoaError(.fileWriteFileExists) }
            try PreviewFileIO.writeUTF8(PagedPreviewCodec.shared.encode(root: root), to: input.2)
            return .success(root)
        }, rejected: {
            if scoped { url.stopAccessingSecurityScopedResource() }
            transferBusy = false; status = "后台队列繁忙，请重新导入"
        }) { result in
            self.transferBusy = false
            switch result {
            case .succeeded(.success(let root)):
                // An accepted import saves the copy even after its window leaves.
                if self.current == nil, self.navigationEpoch == navigation, isRequested() {
                    self.navigationEpoch += 1
                    do { try self.adopt(root, persisted: true) }
                    catch { self.status = "副本已保存在本机，暂时无法打开，请返回列表重试"; return }
                    let note = self.current!
                    self.status = "已导入为新笔记 · \(note.totalStrokeCount) 笔"
                    opened?(note)
                } else { self.status = "备份已导入为新笔记，可返回列表查看" }
                self.refresh()
            case .succeeded(.failure(let reason)): self.status = "导入失败：\(reason.message)"
            case .outcomeUnknown: self.status = "导入结果暂无法确认，请先返回列表查看"
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
        status = "正在保存… · \(note.totalStrokeCount) 笔"
        pumpWrites(); return true
    }
    private func pumpWrites() {
        while true {
            let worker = try! executor.status()
            let dispatch = ledger.admitNextWrite(availableWorkBytes: worker.availableBytes,
                maximumWorkBytes: storageWorkBytes, backendAccepting: worker.accepting)
            guard let attempt = dispatch.attempt else {
                if dispatch.disposition != .idle && dispatch.disposition != .backpressure {
                    status = dispatch.disposition == .workCapacity
                        ? "保存任务超出后台容量，更改仍保留，请保留当前笔记"
                        : "保存后端暂不可用，更改仍保留，请重试"
                }
                return
            }
            let admission = try! executor.submit(input: (attempt, Self.file(attempt.snapshot.documentId, directory: directory), observeWriteBoundary),
                chargedBytes: attempt.snapshot.encodingWorkByteCharge, compute: { input in
                    try PreviewFileIO.writeSnapshot(input.0.snapshot, to: input.1, observeBoundary: input.2)
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
        precondition(Thread.isMainThread)
        guard editors.isEmpty else { status = "其他窗口仍在编辑，请先退出各编辑窗口"; return }
        guard prepareCurrentClose(cancelInput: true) else { return }
        if current != nil { retireCurrent() }
    }
    private func prepareCurrentClose(cancelInput: Bool) -> Bool {
        guard let note = current else { return true }
        if cancelInput { note.cancelStroke() }
        guard !note.hasActiveStroke, save() else { return false }
        if note.hasPendingStroke {
            guard savedRoot === note.snapshot(), savedPending === note.pendingStroke(), ledger.latest(documentId: note.documentId) == nil,
                  !transferBusy, exportDocument == nil else { return false }
        }
        return true
    }
    private func retireCurrent() {
        guard let note = current else { return }
        precondition(editors.isEmpty && !note.hasActiveStroke)
        precondition(note.hasPendingStroke ? note.retireAfterRecovery(snapshot: note.captureRecovery()) : note.retire())
        retireInk()
        navigationEpoch += 1; current = nil; savedRoot = nil; savedPending = nil
        finishExport(); refresh()
    }
    func flushForBackground() { current?.cancelStroke(); save(); retireInk() }
    func applyEdit(_ request: PreviewEdit, content: Bool = true) {
        guard let note = current else { return }
        let result = note.edit(request: request)
        guard result.accepted else { status = previewFailureMessage(result.failure); return }
        revision = result.rootRevision // Published even when only the selected PageId changes.
        if content { save() }
    }
}

/// Per-window presentation only. The original Model remains the single content/storage owner.
final class PreviewWindowState: ObservableObject {
    let identity = UUID()
    let model: PreviewModel
    @Published private(set) var documentId: String?
    private var visible = false
    private var request: UUID?
    private weak var inputView: InkUIView?
    private var inputOperation: String?
    init(model: PreviewModel) {
        precondition(Thread.isMainThread)
        self.model = model; documentId = model.current?.documentId
    }
    var current: PagedPreviewNotebook? {
        precondition(Thread.isMainThread)
        guard visible, model.hasEditor(identity), model.current?.documentId == documentId else { return nil }
        return model.current
    }
    func appear() {
        precondition(Thread.isMainThread)
        visible = true
        if let id = documentId, !model.attachEditor(identity, documentId: id) { documentId = nil }
        else { objectWillChange.send() }
    }
    func disappear() {
        precondition(Thread.isMainThread)
        request = nil; cancelInput()
        if current != nil { model.save() }
        model.finishEditorExport(identity, request: model.exportRequest); model.forgetEditor(identity)
        visible = false; inputView = nil
    }
    func bindInput(_ view: InkUIView) { precondition(Thread.isMainThread); inputView = view }
    func acceptedInput(from view: InkUIView) {
        precondition(Thread.isMainThread)
        guard inputView === view, current === view.note else { return }
        inputOperation = view.note.activeInputOperationId
    }
    func cancelInput() {
        precondition(Thread.isMainThread)
        inputView?.cancelInk()
        // Explicit window cancellation also works if UIKit has already dropped its View.
        // A stale window can never cancel a later stroke accepted by another surface.
        if let operation = inputOperation, let note = current, note.activeInputOperationId == operation { note.cancelStroke() }
        inputOperation = nil
    }
    private func join(_ note: PagedPreviewNotebook) {
        guard visible, model.attachEditor(identity, documentId: note.documentId) else { return }
        documentId = note.documentId
    }
    func create() {
        precondition(Thread.isMainThread)
        request = nil
        if let note = model.createForEditor() { join(note) }
    }
    func open(_ id: String) {
        precondition(Thread.isMainThread)
        let token = UUID(); request = token
        model.openForEditor(id, isRequested: { [weak self] in
            guard let self else { return false }
            return self.visible && self.request == token
        }) { [weak self] note in
            guard let self, self.visible, self.request == token else { return }
            self.request = nil; self.join(note)
        }
    }
    func importCopy(_ url: URL) {
        precondition(Thread.isMainThread)
        let token = UUID(); request = token
        model.importCopy(url, isRequested: { [weak self] in
            guard let self else { return false }
            return self.visible && self.request == token
        }) { [weak self] note in
            guard let self, self.visible, self.request == token else { return }
            self.request = nil; self.join(note)
        }
    }
    @discardableResult func leave() -> PreviewEditorDeparture {
        precondition(Thread.isMainThread)
        request = nil; cancelInput()
        guard let id = documentId else { return .stale }
        let result = model.leaveEditor(identity, documentId: id)
        if result == .detached || result == .documentClosed || result == .stale { documentId = nil; inputView = nil }
        return result
    }
    func prepareExport() { if current != nil { cancelInput(); model.prepareExport(editor: identity) } }
    var ownsExport: Bool { model.exportEditor == identity }
    var exportRequest: UUID? { ownsExport ? model.exportRequest : nil }
    func isExportRequest(_ request: UUID) -> Bool { ownsExport && model.exportRequest == request }
    func finishExport(request: UUID? = nil) { model.finishEditorExport(identity, request: request ?? exportRequest) }
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
    @StateObject private var window: PreviewWindowState
    init(model: PreviewModel, window: PreviewWindowState? = nil) {
        self.model = model
        _window = StateObject(wrappedValue: window ?? PreviewWindowState(model: model))
    }
    @State private var color: Int32 = Int32(bitPattern: 0xff253247)
    @State private var width: Double = 2.5
    @State private var kind: BrushKind = .monoline
    @State private var tool: InkTool = .pen
    @State private var finger = true
    @State private var renaming = false
    @State private var title = ""
    @State private var importing = false
    @State private var exporting = false
    @State private var exportingRequest: UUID?
    @State private var deletingPage = false
    @State private var viewportRequest = 0
    @State private var viewportRatio = 0.0
    var body: some View {
        let exportAtRender = exportingRequest
        return ZStack {
            if let note = window.current {
                VStack(spacing: 8) {
                    HStack {
                        Button("返回") { window.leave() }
                        Spacer()
                        Button(note.title) { window.cancelInput(); title = note.title; renaming = true }.font(.headline).lineLimit(1).accessibilityIdentifier("note-title")
                        Spacer(); Button("保存") { model.retryRetainedWrites(); model.save() }
                        Button("导出") { window.prepareExport() }.disabled(model.transferBusy)
                    }.padding(.horizontal)
                    HStack {
                        Button("撤销") { model.applyEdit(PreviewEditUndo.shared) }.disabled(!note.canUndo)
                        Button("重做") { model.applyEdit(PreviewEditRedo.shared) }.disabled(!note.canRedo)
                        Spacer(); Toggle("手指书写", isOn: $finger).fixedSize()
                    }.padding(.horizontal)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 20) {
                            ForEach([UInt32(0xff253247), 0xff3568be, 0xffce4d52, 0xff21816b], id: \.self) { raw in
                                Button { window.cancelInput(); color = Int32(bitPattern: raw) } label: {
                                    Circle().fill(Color(uiColor: inkColor(Int32(bitPattern: raw)))).frame(width: 25, height: 25)
                                        .overlay(Circle().stroke(color == Int32(bitPattern: raw) ? .gray : .clear, lineWidth: 3))
                                }.accessibilityLabel("笔颜色")
                            }
                            ForEach([2.5, 5.0, 9.0], id: \.self) { value in
                                Button(value == 2.5 ? "细" : value == 5 ? "中" : "粗") { window.cancelInput(); width = value }
                            }
                            Button(kind == .highlighter ? "荧光✓" : "荧光") {
                                window.cancelInput()
                                kind = kind == .highlighter ? .monoline : .highlighter
                            }.accessibilityLabel("荧光笔")
                            Button(tool == .pen ? "钢笔✓" : "钢笔") { window.cancelInput(); tool = .pen }.accessibilityLabel("钢笔工具")
                            Button(tool == .eraser ? "橡皮✓" : "橡皮") { window.cancelInput(); tool = .eraser }.accessibilityLabel("橡皮工具")
                            Button(tool == .lasso ? "套索✓" : "套索") { window.cancelInput(); tool = .lasso }.accessibilityLabel("套索工具")
                            Button("删选") { model.applyEdit(PreviewEditDeleteSelection.shared) }.accessibilityLabel("删除选区")
                        }.padding(.horizontal)
                    }.frame(height: 36)
                    HStack(spacing: 20) {
                        Button("缩小") { viewportRatio = 1.0 / 1.5; viewportRequest += 1 }
                        Button("放大") { viewportRatio = 1.5; viewportRequest += 1 }
                        Button("适合页面") { viewportRatio = 0; viewportRequest += 1 }
                        Text("双指缩放").font(.caption).foregroundStyle(.secondary)
                    }.padding(.horizontal)
                    HStack(spacing: 8) {
                        Button {
                            if let page = note.pageAt(index: note.currentPageIndex - 1) {
                                model.applyEdit(PreviewEditSelectPage(pageId: page.id.value), content: false)
                            }
                        } label: {
                            Image(systemName: "chevron.left").frame(width: 44, height: 44)
                        }.accessibilityLabel("上一页").disabled(note.currentPageIndex == 0)
                        Text("\(note.currentPageIndex + 1)/\(note.pageCount)").monospacedDigit().fixedSize()
                            .accessibilityLabel("当前页码").accessibilityValue("\(note.currentPageIndex + 1)/\(note.pageCount)")
                        Button {
                            if let page = note.pageAt(index: note.currentPageIndex + 1) {
                                model.applyEdit(PreviewEditSelectPage(pageId: page.id.value), content: false)
                            }
                        } label: {
                            Image(systemName: "chevron.right").frame(width: 44, height: 44)
                        }.accessibilityLabel("下一页").disabled(note.currentPageIndex == note.pageCount - 1)
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 20) {
                                Button("加页") { model.applyEdit(PreviewEditInsertPage.shared) }
                                Button("复制页") { model.applyEdit(PreviewEditCopyPage.shared) }
                                Button("删页") { window.cancelInput(); deletingPage = true }
                                Button("前移") {
                                    if let page = note.pageAt(index: note.currentPageIndex - 1) {
                                        model.applyEdit(PreviewEditMovePage(beforePageId: page.id.value))
                                    }
                                }
                                    .disabled(note.currentPageIndex == 0)
                                Button("后移") {
                                    let index = note.currentPageIndex
                                    if note.pageAt(index: index + 1) != nil {
                                        model.applyEdit(PreviewEditMovePage(beforePageId: note.pageAt(index: index + 2)?.id.value))
                                    }
                                }.disabled(note.currentPageIndex == note.pageCount - 1)
                                Menu("纸张") {
                                    Button("空白") { model.applyEdit(PreviewEditSetPaper(width: note.currentPage.width, height: note.currentPage.height, paper: .blank)) }
                                    Button("横线") { model.applyEdit(PreviewEditSetPaper(width: note.currentPage.width, height: note.currentPage.height, paper: .ruled)) }
                                    Button("方格") { model.applyEdit(PreviewEditSetPaper(width: note.currentPage.width, height: note.currentPage.height, paper: .grid)) }
                                    Button("点阵") { model.applyEdit(PreviewEditSetPaper(width: note.currentPage.width, height: note.currentPage.height, paper: .dots)) }
                                }
                            }.padding(.trailing)
                        }
                    }.padding(.horizontal).frame(minHeight: 44)
                    Text(model.status).font(.caption).foregroundStyle(.secondary).accessibilityIdentifier("note-status")
                    NativeInkCanvas(note: note, renderModel: model, color: color, width: width, kind: kind, tool: tool, finger: finger, revision: model.revision, viewportRequest: viewportRequest, viewportRatio: viewportRatio,
                        changed: { model.changed() }, failed: { model.status = $0 }, inputOwner: window)
                        .id(note.documentId)
                        .padding(.horizontal, 12).padding(.bottom, 8)
                }.padding(.top, 8)
            } else {
                NavigationStack {
                    List {
                        Section {
                            Button("新建笔记") { window.create() }
                            Button("导入备份") { importing = true }.disabled(model.transferBusy || model.exportDocument != nil)
                        }
                        Section("本机笔记") {
                            if model.catalogError { Button("重新读取笔记") { model.refresh() } }
                            ForEach(model.notes) { note in
                                Button { window.open(note.id) } label: {
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
        .onAppear { window.appear() }
        .onDisappear { window.disappear() }
        .onChange(of: model.exportDocument != nil) { _, ready in
            if ready && window.ownsExport { exportingRequest = window.exportRequest; exporting = true }
        }
        .fileExporter(isPresented: $exporting, document: model.exportDocument, contentType: .data,
            defaultFilename: "NoteCore-backup.ncp") { result in
                guard let ticket = exportAtRender, window.isExportRequest(ticket) else { return }
                window.finishExport(request: ticket)
                switch result {
                case .success: model.status = "备份已导出"
                case .failure: model.status = "导出未完成，请重试"
                }
            }
        .onChange(of: exporting) { _, showing in if !showing, let ticket = exportAtRender { window.finishExport(request: ticket) } }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.data]) { result in
            switch result {
            case .success(let url): window.importCopy(url)
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
