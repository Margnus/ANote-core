# 独立 Apple 内存草图（未冻结）

ENG-19 的 UIKit 第二消费者，独立包 `dev.notecore.sample.sketch.ios`。工程只编译自身两个宿主 Swift 文件，并链接 shared-core → canvas-core framework；不导入 NoteCorePreview、PreviewModel、FileIO 或实验渲染池。界面明确显示“未保存，退出进程后内容消失”，无保存与恢复服务。

在 arm64 Mac、JDK17、Xcode16.4 及已安装 iOS Simulator 上：

```sh
./gradlew -PenableAppleTargets=true :shared-core:linkDebugFrameworkIosSimulatorArm64
xcodebuild -project samples/apple-sketch/NoteCoreSketch.xcodeproj -scheme NoteCoreSketch -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPad Pro 11-inch (M4)' CODE_SIGNING_ALLOWED=NO build
```

也可用 Xcode 打开该工程，选择已有 Simulator 运行。当前 framework 路径是 `iosSimulatorArm64/debugFramework`，本样例尚未提供真机签名或设备 framework 切换；不把模拟器编译等同真机可运行验收。

工具列表与借用的 CGContext 背景由宿主注入。Notebook 是唯一文档 authority，直接复用原只读/活动/pending/撤销与重做语义。只读是可显式切换的编辑模式，不是宿主 ACL。背景不得保留 CGContext；调用边界恢复变换、裁剪与当前 path。规范 MONOLINE 样本按原对象变换绘制，无新笔刷、内容模型、调度器或资源账本。

UIKit 合并真实点整批通过原 ApplePreviewInput；不重复追加主触点或消费预测。准入拒绝保持已有前缀，并阻止后续终点提交部分笔，用户可明确取消未完成笔；已完成 pending 只通过原重试入口处理。尺寸、窗口移除、前后台责任调用原取消入口，不能取消已完成 pending。当前为单接触内存样例，无缩放/多指/Pencil 全矩阵或生产渲染成本承诺。

独立 CI `.github/workflows/apple-sketch.yml` 在全新托管 Mac 和唯一 Simulator 构建当前 framework/宿主，执行 5 项 App-host 检查和 1 条普通 UI 链，保留原日志、测试树、截图、源码与二进制指纹。结果以具体运行的报告为准；写入工程或测试代码本身不计编译/运行通过。真机、完整资源生命周期/heap、稳定 SDK 与整体 C1 保持待验。
