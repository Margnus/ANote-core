import SwiftUI
import NoteCanvasPrototype

struct LocalNote: Identifiable { let id: String; let title: String; let date: Date }

final class PreviewModel: ObservableObject {
    @Published var notes: [LocalNote] = []
    @Published var current: PreviewNotebook?
    @Published var status = ""
    @Published var revision: Int64 = 0
    private let io = DispatchQueue(label: "dev.notecore.preview.storage")
    private let directory: URL
    private var pending: PreviewNotebookSnapshot?
    private var saveScheduled = false
    private var failed: [String: PreviewNotebookSnapshot] = [:]
    init() {
        directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("PreviewNotes", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        refresh()
    }
    private func file(_ id: String) -> URL { directory.appendingPathComponent(id).appendingPathExtension("ncp") }
    func create() {
        current = PreviewNotebook(documentId: UUID().uuidString, title: "未命名笔记")
        revision = 0; save()
    }
    func refresh() {
        io.async {
            let files = (try? FileManager.default.contentsOfDirectory(at: self.directory, includingPropertiesForKeys: [.contentModificationDateKey])) ?? []
            var notes = files.filter { $0.pathExtension == "ncp" }.map { url -> LocalNote in
                let date = (try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
                guard let data = try? String(contentsOf: url, encoding: .utf8),
                      let note = try? PreviewNotebook.companion.restore(encoded: data) else {
                    return LocalNote(id: url.deletingPathExtension().lastPathComponent, title: "待恢复的笔记", date: date)
                }
                return LocalNote(id: note.documentId, title: note.title, date: date)
            }
            for (id, retained) in self.failed {
                notes.removeAll { $0.id == id }
                notes.append(LocalNote(id: id, title: retained.title + "（未保存）", date: Date()))
            }
            notes.sort { $0.date > $1.date }
            let result = notes
            DispatchQueue.main.async { self.notes = result }
        }
    }
    func open(_ id: String) {
        status = "正在打开…"
        io.async {
            do {
                let retained = self.failed[id]
                let data: String
                if let retained = retained { data = retained.encode() }
                else { data = try String(contentsOf: self.file(id), encoding: .utf8) }
                guard data.utf8.count <= 32 * 1024 * 1024 else { throw CocoaError(.fileReadTooLarge) }
                let note = try PreviewNotebook.companion.restore(encoded: data)
                guard note.documentId == id else { throw CocoaError(.fileReadCorruptFile) }
                DispatchQueue.main.async {
                    self.current = note; self.revision = note.changeVersion
                    self.status = retained == nil ? "已打开 · \(note.strokeCount) 笔" : "有更改尚未保存，请重试保存"
                }
            } catch { DispatchQueue.main.async { self.status = "读取失败，原文件已保留" } }
        }
    }
    func changed() { revision = current?.changeVersion ?? 0; save() }
    func save() {
        guard let note = current else { return }
        guard note.retryPendingStroke() else { status = note.lastError; return }
        let snapshot = note.capture()
        pending = snapshot
        status = "正在保存… · \(note.strokeCount) 笔"
        if saveScheduled { return }
        saveScheduled = true
        // Debounce on the owner; one immutable latest snapshot is sent to serial IO.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) {
            self.saveScheduled = false
            guard let latest = self.pending else { return }; self.pending = nil
            self.write(latest)
        }
    }
    private func write(_ snapshot: PreviewNotebookSnapshot) {
        io.async {
            do {
                try snapshot.encode().write(to: self.file(snapshot.documentId), atomically: true, encoding: .utf8)
                self.failed.removeValue(forKey: snapshot.documentId)
                DispatchQueue.main.async {
                    if self.current?.documentId == snapshot.documentId && self.current?.changeVersion == snapshot.version {
                        self.status = "已保存到本机 · \(self.current?.strokeCount ?? 0) 笔"
                    } else if self.current == nil { self.status = "笔记已保存到本机" }
                }
            } catch {
                self.failed[snapshot.documentId] = snapshot
                DispatchQueue.main.async { self.status = "保存失败，更改仍保留在当前会话，请重试" }
            }
        }
    }
    func close() {
        current?.cancelStroke()
        if let note = current { write(note.capture()) }
        pending = nil; current = nil; refresh()
    }
    func flushForBackground() {
        current?.cancelStroke()
        if let note = current { write(note.capture()) }
        pending = nil
    }
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
    var body: some View {
        Group {
            if let note = model.current {
                VStack(spacing: 8) {
                    HStack {
                        Button("返回") { model.close() }
                        Spacer()
                        Button(note.title) { note.cancelStroke(); title = note.title; renaming = true }.font(.headline).lineLimit(1)
                        Spacer(); Button("保存") { model.save() }
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
                    Text(model.status).font(.caption).foregroundStyle(.secondary)
                    NativeInkCanvas(note: note, color: color, width: width, finger: finger, revision: model.revision,
                        changed: { model.changed() }, failed: { model.status = $0 })
                        .padding(.horizontal, 12).padding(.bottom, 8)
                }.padding(.top, 8)
            } else {
                NavigationStack {
                    List {
                        Section { Button("新建笔记") { model.create() } }
                        Section("本机笔记") {
                            ForEach(model.notes) { note in
                                Button { model.open(note.id) } label: {
                                    VStack(alignment: .leading) { Text(note.title).font(.headline); Text(note.date, style: .date).font(.caption).foregroundStyle(.secondary) }
                                }
                            }
                            if model.notes.isEmpty { Text("还没有笔记，写下第一个想法。").foregroundStyle(.secondary) }
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
    }
}

func inkColor(_ value: Int32) -> UIColor {
    let color = UInt32(bitPattern: value)
    return UIColor(red: CGFloat((color >> 16) & 255) / 255, green: CGFloat((color >> 8) & 255) / 255,
        blue: CGFloat(color & 255) / 255, alpha: CGFloat((color >> 24) & 255) / 255)
}
