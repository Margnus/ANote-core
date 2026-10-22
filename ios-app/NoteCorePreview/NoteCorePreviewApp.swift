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

final class PreviewModel: ObservableObject {
    @Published var notes: [LocalNote] = []
    @Published var catalogError = false
    @Published var current: PagedPreviewNotebook?
    @Published var status = ""
    @Published var revision: Int64 = 0
    @Published var exportDocument: PreviewBackup?
    @Published var transferBusy = false
    private let directory: URL
    private let beforeInkCompute: () throws -> Void
    private let inkReleaseBoundary: (Bool) throws -> Void
    private let ledger = PreviewSaveLedger(maxDocuments: 8, byteBudget: 32 * 1024 * 1024)
    private var savedRoot: DocumentRoot?
    private var storageEpoch: Int64 = 0
    private var navigationEpoch: Int64 = 0
    private lazy var executor = try! AppleBoundedOwnerExecutor(maxEntries: 16, maxBytes: 256 * 1024 * 1024,
        onOwnerProgress: { [weak self] in self?.inkOwnerProgress(); self?.pumpWrites() })
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
        inkImage = nil; inkOwnerProgress(); return true
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
    init(directory: URL? = nil, beforeInkCompute: @escaping () throws -> Void = {},
         inkReleaseBoundary: @escaping (Bool) throws -> Void = { _ in }) {
        precondition(Thread.isMainThread)
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
    private func adopt(_ root: DocumentRoot, persisted: Bool) throws {
        precondition(Thread.isMainThread && current == nil)
        let budget = DocumentBudget(maxPages: 128, maxObjects: 5000, liveBytes: 8 * 1024 * 1024,
            journalEntries: 4096, journalBytes: 32 * 1024 * 1024, identityBytes: 4 * 1024 * 1024,
            historyEntries: 128, historyBytes: 8 * 1024 * 1024)
        current = try PagedPreviewNotebook(initial: root, sessionNonce: UUID().uuidString, budget: budget,
            ownerCheck: { precondition(Thread.isMainThread) })
        savedRoot = persisted && ledger.latest(documentId: root.id.value) == nil ? root : nil
        revision = root.revision
    }
    private static func validateRenderable(_ root: DocumentRoot) throws {
        for stroke in root.scene.objects() {
            let m = stroke.transform
            guard stroke.brush.kind == .monoline, stroke.brush.algorithmVersion == 1,
                  [m.a, m.b, m.c, m.d, m.tx, m.ty].allSatisfy({ $0.isFinite && abs($0) <= 1e12 })
            else { throw CocoaError(.fileReadUnsupportedScheme) }
        }
    }
    func create() {
        guard current == nil else { return }
        navigationEpoch += 1
        let nonce = UUID().uuidString
        let root = DocumentRoot.companion.empty(id: SceneId(value: UUID().uuidString), title: "未命名笔记",
            firstPage: PageDescriptor(id: PageId(value: nonce+"-page"), layerId: LayerId(value: nonce+"-layer"),
                width: 595, height: 842, paper: .blank, contentRevision: 0))
        do { try adopt(root, persisted: false); save() }
        catch { status = "新建失败，已有笔记仍保留" }
    }
    private func overlay(_ disk: [LocalNote]) -> [LocalNote] {
        let retained = ledger.states().map { LocalNote(id: $0.snapshot.documentId, title: $0.snapshot.title + "（未保存）", date: Date()) }
        let ids = Set(retained.map { $0.id })
        return (disk.filter { !ids.contains($0.id) } + retained).sorted { $0.date > $1.date }
    }
    func refresh() {
        request(input: directory, bytes: 2 * 1024 * 1024, compute: { root in
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
            return try PreviewFileIO.catalog(root).map { url -> LocalNote in
                let date = (try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
                let id = url.deletingPathExtension().lastPathComponent
                let title: String
                if let encoded = try? PreviewFileIO.readHeaderUTF8(url),
                   let header = try? PagedPreviewCodec.shared.header(text: encoded), header.documentId == id {
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
        guard current == nil else { return }
        navigationEpoch += 1
        let navigation = navigationEpoch, epoch = storageEpoch
        let retained = ledger.latest(documentId: id)
        status = "正在打开…"
        request(input: (Self.file(id, directory: directory), id, retained), bytes: 160 * 1024 * 1024, compute: { input in
            let root: DocumentRoot
            if let captured = input.2 as? PagedPreviewSnapshot { root = captured.root }
            else {
                guard input.2 == nil else { throw CocoaError(.fileReadCorruptFile) }
                root = try PagedPreviewCodec.shared.decode(text: PreviewFileIO.readUTF8(input.0))
            }
            guard root.id.value == input.1 else { throw CocoaError(.fileReadCorruptFile) }
            try Self.validateRenderable(root)
            return root // Immutable only. The main owner creates the mutable session after navigation checks.
        }, rejected: { status = "后台队列繁忙，请重新打开笔记" }) { result in
            guard self.navigationEpoch == navigation && self.current == nil else { return }
            if self.storageEpoch != epoch {
                // Retry the pure read after ACK; no old notebook payload is placed on a callback queue.
                DispatchQueue.main.async { self.open(id) }; return
            }
            switch result {
            case .succeeded(let root):
                do { try self.adopt(root, persisted: retained == nil) }
                catch { self.status = "打开失败，原文件仍保留"; return }
                let note = self.current!
                self.status = retained == nil ? "已打开 · \(note.totalStrokeCount) 笔" : "有更改尚未保存，请重试保存"
            case .outcomeUnknown: self.status = "读取失败，原文件已保留"
            }
        }
    }
    func changed() { revision = current?.changeVersion ?? 0; save() }
    func prepareExport() {
        guard !transferBusy, exportDocument == nil, let note = current else { return }
        note.cancelStroke()
        guard note.retryPendingStroke() else { status = note.lastError; return }
        transferBusy = true; status = "正在准备备份…"
        let snapshot = note.capture()
        request(input: snapshot, bytes: snapshot.encodingWorkByteCharge, compute: { captured in
            let encoded = try captured.encode()
            guard encoded.utf16.count <= 16_000_000 else { throw CocoaError(.fileWriteOutOfSpace) }
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
        guard !transferBusy else { return }
        transferBusy = true; status = "正在校验并导入…"
        let scoped = url.startAccessingSecurityScopedResource()
        let id = UUID().uuidString, navigation = navigationEpoch
        request(input: (url, scoped, Self.file(id, directory: directory), id), bytes: 160 * 1024 * 1024, compute: { input in
            defer { if input.1 { input.0.stopAccessingSecurityScopedResource() } }
            let encoded = try PreviewFileIO.readUTF8(input.0)
            let source = try PagedPreviewCodec.shared.decode(text: encoded)
            try Self.validateRenderable(source)
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
        guard note.retryPendingStroke() else { status = note.lastError; return false }
        let snapshot = note.capture(), previous = ledger.latest(documentId: note.documentId)
        if savedRoot === snapshot.root && previous == nil {
            status = "已保存到本机 · \(note.totalStrokeCount) 笔"; return true
        }
        let admission = ledger.offer(snapshot: snapshot)
        guard admission == .accepted else {
            status = admission == .versionConflict ? "保存内容发生冲突，请保留当前笔记并导出备份" : "保存队列已满或内容版本过期，请保留当前笔记并重试"; return false
        }
        if previous?.version != snapshot.version { storageEpoch += 1 }
        status = "正在保存… · \(note.totalStrokeCount) 笔"
        pumpWrites(); return true
    }
    private func pumpWrites() {
        while let attempt = ledger.nextWrite() {
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
                    }
                    if self.current?.documentId == snapshot.documentId && self.current?.changeVersion == snapshot.version {
                        if let current = self.current, current.hasPendingStroke { self.status = current.lastError }
                        else if let current = self.current, !snapshot.sameContent(other: current.capture()) {
                            self.status = "保存内容发生冲突，请保留当前笔记并导出备份"
                        } else { self.status = success ? "已保存到本机 · \(self.current?.totalStrokeCount ?? 0) 笔" : "保存失败，更改仍保留在本机会话，请重试" }
                    } else if self.current == nil && !success { self.status = "保存失败，更改仍保留，可从列表重新打开并重试" }
                    return .acknowledged // Failure is in the owner ledger before releasing worker responsibility.
                })
            if case .rejected = admission {
                precondition(ledger.returnUnstarted(attempt: attempt))
                if attempt.snapshot.encodingWorkByteCharge > 256 * 1024 * 1024 { status = "保存后端暂不可用，更改仍保留，请重试" }
                return
            }
        }
    }
    func close() {
        current?.cancelStroke()
        guard save(), current?.retire() == true else { return }
        retireInk()
        navigationEpoch += 1; current = nil; savedRoot = nil; refresh()
    }
    func flushForBackground() { current?.cancelStroke(); save(); retireInk() }
    func editPage(content: Bool = true, _ edit: (PagedPreviewNotebook) -> Bool) {
        guard let note = current else { return }
        note.cancelStroke()
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
                        Spacer(); Button("保存") { model.save() }
                        Button("导出") { model.prepareExport() }.disabled(model.transferBusy)
                    }.padding(.horizontal)
                    HStack {
                        Button("撤销") { model.editPage { $0.undo() } }.disabled(!note.canUndo)
                        Button("重做") { model.editPage { $0.redo() } }.disabled(!note.canRedo)
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
                        Button { model.editPage(content: false) { $0.selectPage(id: $0.page(index: $0.currentPageIndex - 1).id) } } label: {
                            Image(systemName: "chevron.left").frame(width: 44, height: 44)
                        }.accessibilityLabel("上一页").disabled(note.currentPageIndex == 0)
                        Text("\(note.currentPageIndex + 1)/\(note.pageCount)").monospacedDigit().fixedSize()
                            .accessibilityLabel("当前页码").accessibilityValue("\(note.currentPageIndex + 1)/\(note.pageCount)")
                        Button { model.editPage(content: false) { $0.selectPage(id: $0.page(index: $0.currentPageIndex + 1).id) } } label: {
                            Image(systemName: "chevron.right").frame(width: 44, height: 44)
                        }.accessibilityLabel("下一页").disabled(note.currentPageIndex == note.pageCount - 1)
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 20) {
                                Button("加页") { model.editPage { $0.insertPage() } }
                                Button("复制页") { model.editPage { $0.doCopyCurrentPage() } }
                                Button("删页") { note.cancelStroke(); deletingPage = true }
                                Button("前移") { model.editPage { $0.moveCurrentPage(before: $0.page(index: $0.currentPageIndex - 1).id) } }
                                    .disabled(note.currentPageIndex == 0)
                                Button("后移") { model.editPage {
                                    $0.moveCurrentPage(before: $0.currentPageIndex + 2 < $0.pageCount ? $0.page(index: $0.currentPageIndex + 2).id : nil)
                                } }.disabled(note.currentPageIndex == note.pageCount - 1)
                                Menu("纸张") {
                                    Button("空白") { model.editPage { $0.setPaper(width: $0.currentPage.width, height: $0.currentPage.height, paper: .blank) } }
                                    Button("横线") { model.editPage { $0.setPaper(width: $0.currentPage.width, height: $0.currentPage.height, paper: .ruled) } }
                                    Button("方格") { model.editPage { $0.setPaper(width: $0.currentPage.width, height: $0.currentPage.height, paper: .grid) } }
                                    Button("点阵") { model.editPage { $0.setPaper(width: $0.currentPage.width, height: $0.currentPage.height, paper: .dots) } }
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
            Button("完成") { model.editPage { $0.rename(value: title) } }
        }
        .alert("删除当前页？", isPresented: $deletingPage) {
            Button("取消", role: .cancel) { }
            Button("删除", role: .destructive) { model.editPage { $0.deleteCurrentPage() } }
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
