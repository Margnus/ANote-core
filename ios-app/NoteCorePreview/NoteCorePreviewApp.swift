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
    @Published var current: PreviewNotebook?
    @Published var status = ""
    @Published var revision: Int64 = 0
    @Published var exportDocument: PreviewBackup?
    @Published var transferBusy = false
    private let directory: URL
    private let ledger = PreviewSaveLedger(maxDocuments: 8, byteBudget: 32 * 1024 * 1024)
    private var storageEpoch: Int64 = 0
    private var navigationEpoch: Int64 = 0
    private lazy var executor = try! AppleBoundedOwnerExecutor(maxEntries: 16, maxBytes: 256 * 1024 * 1024,
        onOwnerProgress: { [weak self] in self?.pumpWrites() })
    init() {
        precondition(Thread.isMainThread)
        directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("PreviewNotes", isDirectory: true)
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
    func create() {
        navigationEpoch += 1
        current = PreviewNotebook(documentId: UUID().uuidString, title: "未命名笔记")
        revision = 0; save()
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
                   let header = try? PreviewNotebook.companion.header(encodedHeader: encoded), header.documentId == id {
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
            let encoded: String
            if let captured = input.2 { encoded = captured.encode() }
            else { encoded = try PreviewFileIO.readUTF8(input.0) }
            let note = try PreviewNotebook.companion.restore(encoded: encoded)
            guard note.documentId == input.1 else { throw CocoaError(.fileReadCorruptFile) }
            return note // Exclusive transfer of newly restored mutable notebook to main owner.
        }, rejected: { status = "后台队列繁忙，请重新打开笔记" }) { result in
            guard self.navigationEpoch == navigation && self.current == nil else { return }
            if self.storageEpoch != epoch {
                // Retry the pure read after ACK; no old notebook payload is placed on a callback queue.
                DispatchQueue.main.async { self.open(id) }; return
            }
            switch result {
            case .succeeded(let note):
                self.current = note; self.revision = note.changeVersion
                self.status = retained == nil ? "已打开 · \(note.strokeCount) 笔" : "有更改尚未保存，请重试保存"
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
            let encoded = captured.encode()
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
        let id = UUID().uuidString
        request(input: (url, scoped, Self.file(id, directory: directory), id), bytes: 160 * 1024 * 1024, compute: { input in
            defer { if input.1 { input.0.stopAccessingSecurityScopedResource() } }
            let encoded = try PreviewFileIO.readUTF8(input.0)
            let note = try PreviewNotebook.companion.importAsCopy(encoded: encoded, newDocumentId: input.3)
            guard !FileManager.default.fileExists(atPath: input.2.path) else { throw CocoaError(.fileWriteFileExists) }
            try PreviewFileIO.writeUTF8(note.capture().encode(), to: input.2)
            return note
        }, rejected: {
            if scoped { url.stopAccessingSecurityScopedResource() }
            transferBusy = false; status = "后台队列繁忙，请重新导入"
        }) { result in
            self.transferBusy = false
            switch result {
            case .succeeded(let note):
                if self.current == nil {
                    self.navigationEpoch += 1; self.current = note; self.revision = note.changeVersion
                    self.status = "已导入为新笔记 · \(note.strokeCount) 笔"
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
        guard ledger.offer(snapshot: snapshot) == .accepted else {
            status = "保存队列已满或内容版本过期，请保留当前笔记并重试"; return false
        }
        if previous?.version != snapshot.version { storageEpoch += 1 }
        status = "正在保存… · \(note.strokeCount) 笔"
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
                    if self.current?.documentId == snapshot.documentId && self.current?.changeVersion == snapshot.version {
                        self.status = success ? "已保存到本机 · \(self.current?.strokeCount ?? 0) 笔" : "保存失败，更改仍保留在本机会话，请重试"
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
        guard save() else { return }
        navigationEpoch += 1; current = nil; refresh()
    }
    func flushForBackground() { current?.cancelStroke(); save() }
}

@main
struct NoteCorePreviewApp: App {
    @StateObject private var model = PreviewModel()
    @Environment(\.scenePhase) private var scenePhase
    var body: some Scene {
        WindowGroup { PreviewRoot(model: model).preferredColorScheme(.light) }
            .onChange(of: scenePhase) { _, phase in if phase != .active { model.flushForBackground() } }
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
                        Button("撤销") { note.cancelStroke(); if note.undo() { model.changed() } }.disabled(!note.canUndo)
                        Button("重做") { note.cancelStroke(); if note.redo() { model.changed() } }.disabled(!note.canRedo)
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
                    Text(model.status).font(.caption).foregroundStyle(.secondary)
                    NativeInkCanvas(note: note, color: color, width: width, finger: finger, revision: model.revision, viewportRequest: viewportRequest, viewportRatio: viewportRatio,
                        changed: { model.changed() }, failed: { model.status = $0 })
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
            Button("完成") { model.current?.rename(value: title); model.changed() }
        }
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
