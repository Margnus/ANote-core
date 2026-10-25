import SwiftUI
import UIKit
import NoteCanvasPrototype

final class SketchController: UIViewController {
    let session: SketchSession
    private(set) var canvas: SketchCanvas!
    private var status: UILabel!
    init(session: SketchSession) { self.session = session; super.init(nibName: nil, bundle: nil) }
    required init?(coder: NSCoder) { fatalError("Use init(session:)") }
    override func loadView() {
        let root = UIView(); root.backgroundColor = .systemBackground; view = root
        let stack = UIStackView(); stack.axis = .vertical; stack.spacing = 8; stack.translatesAutoresizingMaskIntoConstraints = false
        root.addSubview(stack)
        NSLayoutConstraint.activate([stack.leadingAnchor.constraint(equalTo: root.safeAreaLayoutGuide.leadingAnchor, constant: 12),
            stack.trailingAnchor.constraint(equalTo: root.safeAreaLayoutGuide.trailingAnchor, constant: -12),
            stack.topAnchor.constraint(equalTo: root.safeAreaLayoutGuide.topAnchor, constant: 8),
            stack.bottomAnchor.constraint(equalTo: root.safeAreaLayoutGuide.bottomAnchor, constant: -8)])
        let title = UILabel(); title.text = "独立 Apple 内存草图"; title.font = .preferredFont(forTextStyle: .headline); stack.addArrangedSubview(title)
        let warning = UILabel(); warning.text = "未保存，退出进程后内容消失"; warning.textColor = .secondaryLabel; stack.addArrangedSubview(warning)
        status = UILabel(); status.numberOfLines = 2; status.text = "未保存 · 0笔"; stack.addArrangedSubview(status)
        let bar = UIStackView(); bar.spacing = 8; bar.distribution = .fillEqually
        func button(_ text: String, _ action: @escaping (SketchController) -> Void) {
            let item = UIButton(type: .system); item.setTitle(text, for: .normal)
            item.addAction(UIAction { [weak self] _ in if let self { action(self) } }, for: .touchUpInside)
            item.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true; bar.addArrangedSubview(item)
        }
        for (index, tool) in session.tools.enumerated() { button(tool.label) { $0.apply($0.session.selectTool(index)) } }
        button("只读/编辑") { $0.apply($0.session.setReadOnly(!$0.session.readOnly)) }
        button("撤销") { $0.apply($0.session.edit(PreviewEditUndo.shared)) }
        stack.addArrangedSubview(bar)
        let cancel = UIButton(type: .system); cancel.setTitle("取消未完成笔", for: .normal)
        cancel.addAction(UIAction { [weak self] _ in self?.canvas.cancelUnfinished(); self?.apply(nil) }, for: .touchUpInside)
        stack.addArrangedSubview(cancel)
        let retry = UIButton(type: .system); retry.setTitle("重试提交", for: .normal)
        retry.addAction(UIAction { [weak self] _ in if let self { self.apply(self.session.retryPending()) } }, for: .touchUpInside)
        stack.addArrangedSubview(retry)
        canvas = SketchCanvas(session: session); canvas.changed = { [weak self] text in self?.status.text = text }
        stack.addArrangedSubview(canvas)
    }
    private func apply(_ refusal: SketchRefusal?) {
        if refusal == nil && !session.notebook.hasActiveStroke && !session.notebook.hasPendingStroke { canvas.cancelUnfinished() }
        status.text = refusal == nil ? "\(session.readOnly ? "只读" : "未保存") · \(session.notebook.strokeCount)笔" : "操作未接受，当前笔迹保留"
        canvas.setNeedsDisplay()
    }
}
private struct SketchContainer: UIViewControllerRepresentable {
    let session: SketchSession
    func makeUIViewController(context: Context) -> SketchController { SketchController(session: session) }
    func updateUIViewController(_ controller: SketchController, context: Context) { }
}
@main
struct SketchHostApp: App {
    @StateObject private var session = SketchSession.standard()
    var body: some Scene { WindowGroup { SketchContainer(session: session) } }
}
