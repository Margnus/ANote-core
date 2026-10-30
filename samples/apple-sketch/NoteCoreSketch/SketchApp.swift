import UIKit
import NoteCanvasPrototype

struct SketchTool {
    let title: String
    let argb: Int32
    let width: Double
}

/// Host policy only. The original notebook remains the sole document authority.
final class SketchSession {
    let notebook: PagedPreviewNotebook
    let tools: [SketchTool]
    let background: (CGContext, CGSize) -> Void
    private(set) var toolIndex = 0

    init(tools: [SketchTool], background: @escaping (CGContext, CGSize) -> Void) {
        precondition(Thread.isMainThread && !tools.isEmpty)
        self.tools = tools
        self.background = background
        let result = PagedPreviewNotebook.companion.createValidated(
            documentId: UUID().uuidString, title: "内存草图", sessionNonce: UUID().uuidString,
            width: 595, height: 842, paper: .blank,
            budget: DocumentBudget(maxPages: 128, maxObjects: 5000, liveBytes: 8 * 1024 * 1024,
                journalEntries: 128, journalBytes: 8 * 1024 * 1024, identityBytes: 2 * 1024 * 1024,
                historyEntries: 128, historyBytes: 8 * 1024 * 1024, inputBytes: 2 * 1024 * 1024),
            ownerCheck: { precondition(Thread.isMainThread) })
        precondition(result.failure == nil && result.notebook != nil)
        notebook = result.notebook!
        precondition(selectTool(0))
    }

    @discardableResult func selectTool(_ index: Int) -> Bool {
        precondition(Thread.isMainThread)
        guard tools.indices.contains(index), !notebook.isReadOnly,
              !notebook.hasActiveStroke, !notebook.hasPendingStroke else { return false }
        let tool = tools[index]
        guard notebook.edit(request: PreviewEditSelectPen(argb: tool.argb, width: tool.width)).accepted else { return false }
        toolIndex = index
        return true
    }

    static func standard() -> SketchSession {
        SketchSession(tools: [SketchTool(title: "蓝笔", argb: Int32(bitPattern: 0xff2563eb), width: 4),
                              SketchTool(title: "红笔", argb: Int32(bitPattern: 0xffdc2626), width: 7)]) { context, size in
            context.setFillColor(UIColor(red: 1, green: 0.984, blue: 0.922, alpha: 1).cgColor)
            context.fill(CGRect(origin: .zero, size: size))
            context.setStrokeColor(UIColor.lightGray.cgColor)
            context.setLineWidth(1)
            for y in stride(from: CGFloat(32), to: size.height, by: 32) {
                context.move(to: CGPoint(x: 0, y: y)); context.addLine(to: CGPoint(x: size.width, y: y))
            }
            context.strokePath()
        }
    }
}

final class SketchController: UIViewController {
    let session = SketchSession.standard()
    private(set) lazy var canvas = SketchCanvas(session: session)
    private let status = UILabel()

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        let warning = UILabel()
        warning.text = "内存草图 · 未保存，退出进程后内容消失"
        warning.numberOfLines = 0
        let actions = UIStackView()
        actions.axis = .horizontal; actions.distribution = .fillEqually
        for (index, tool) in session.tools.enumerated() {
            actions.addArrangedSubview(button(tool.title) { [weak self] in
                guard let self else { return }; self.update(self.session.selectTool(index))
            })
        }
        actions.addArrangedSubview(button("只读") { [weak self] in
            guard let self else { return }
            self.update(self.session.notebook.setReadOnly(value: !self.session.notebook.isReadOnly))
        })
        actions.addArrangedSubview(button("撤销") { [weak self] in
            guard let self else { return }; self.update(self.session.notebook.edit(request: PreviewEditUndo.shared).accepted)
        })
        actions.addArrangedSubview(button("重做") { [weak self] in
            guard let self else { return }; self.update(self.session.notebook.edit(request: PreviewEditRedo.shared).accepted)
        })
        let recovery = UIStackView()
        recovery.distribution = .fillEqually
        recovery.addArrangedSubview(button("取消未完成笔") { [weak self] in self?.canvas.cancelUnfinished() })
        recovery.addArrangedSubview(button("重试已完成笔") { [weak self] in
            guard let self else { return }; self.update(self.session.notebook.retryPendingStroke())
        })
        let stack = UIStackView(arrangedSubviews: [warning, actions, recovery, status, canvas])
        stack.axis = .vertical; stack.spacing = 8; stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 12),
            stack.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -12),
            stack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 8),
            stack.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -8)])
        canvas.changed = { [weak self] message in self?.refresh(message) }
        refresh("")
        NotificationCenter.default.addObserver(self, selector: #selector(suspendInput),
            name: UIApplication.willResignActiveNotification, object: nil)
    }

    private func button(_ title: String, action: @escaping () -> Void) -> UIButton {
        let button = UIButton(type: .system)
        button.setTitle(title, for: .normal); button.accessibilityIdentifier = title
        button.addAction(UIAction { _ in action() }, for: .touchUpInside)
        return button
    }
    private func update(_ accepted: Bool) { refresh(accepted ? "" : session.notebook.lastError) }
    private func refresh(_ message: String) {
        status.text = "\(session.notebook.strokeCount) 笔 · \(session.notebook.isReadOnly ? "只读" : session.tools[session.toolIndex].title) \(message)"
        status.accessibilityIdentifier = "草图状态"
        canvas.setNeedsDisplay()
    }
    @objc private func suspendInput() { canvas.cancelUnfinished() }
    override func viewWillDisappear(_ animated: Bool) { super.viewWillDisappear(animated); canvas.cancelUnfinished() }
    deinit { NotificationCenter.default.removeObserver(self) }
}

@main final class SketchApp: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    func application(_ application: UIApplication, didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = SketchController(); window.makeKeyAndVisible(); self.window = window
        return true
    }
}
