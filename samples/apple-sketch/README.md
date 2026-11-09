# 独立 Apple 内存草图（未冻结）

ENG-19 的 UIKit 第二消费者，独立包 `dev.notecore.sample.sketch.ios`。工程只编译自身两个宿主 Swift 文件，并链接 shared-core → canvas-core framework；不导入 NoteCorePreview、PreviewModel、FileIO 或实验渲染池。界面明确显示“未保存，退出进程后内容消失”，无保存与恢复服务。

在 arm64 Mac、JDK17、Xcode16.4 及已安装 iOS Simulator 上：

```sh
./gradlew -PenableAppleTargets=true :shared-core:linkDebugFrameworkIosSimulatorArm64
xcodebuild -project samples/apple-sketch/NoteCoreSketch.xcodeproj -scheme NoteCoreSketch -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPad Pro 11-inch (M4)' CODE_SIGNING_ALLOWED=NO build
```

也可用 Xcode 打开该工程，选择已有 Simulator 运行。工程按 SDK 选择 `iosSimulatorArm64/debugFramework` 或 `iosArm64/debugFramework`，App 与宿主测试继承同一 `NOTECORE_FRAMEWORK_DIR`，不修改 framework 内容。

设备编译使用已有 Kotlin device target：

```sh
./gradlew -PenableAppleTargets=true :shared-core:linkDebugFrameworkIosArm64
xcodebuild -project samples/apple-sketch/NoteCoreSketch.xcodeproj -scheme NoteCoreSketch -configuration Debug -sdk iphoneos -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build
```

上述命令仅无签名编译，不安装、不发布。实际设备运行需要在 Xcode 为个人环境选择签名 Team 和设备；仓库不写入 Team/证书。CI 单独记录设备二进制的平台与 SHA，不把设备编译或模拟器运行等同真机可运行验收。Release 配置目前同样链接未冻结的 debug framework，不用于发布。

工具列表与借用的 CGContext 背景由宿主注入。Notebook 是唯一文档 authority，直接复用原只读/活动/pending/撤销与重做语义。只读是可显式切换的编辑模式，不是宿主 ACL。背景不得保留 CGContext；调用边界恢复变换、裁剪与当前 path。规范 MONOLINE 样本按原对象变换绘制，无新笔刷、内容模型、调度器或资源账本。

UIKit 合并真实点整批通过原 ApplePreviewInput；不重复追加主触点或消费预测。准入拒绝保持已有前缀，并阻止后续终点提交部分笔，用户可明确取消未完成笔；已完成 pending 只通过原重试入口处理。尺寸、窗口移除、前后台责任调用原取消入口，不能取消已完成 pending。当前为单接触内存样例，无缩放/多指/Pencil 全矩阵或生产渲染成本承诺。

独立 CI `.github/workflows/apple-sketch.yml` 在全新托管 Mac 和唯一 Simulator 构建当前 framework/宿主，执行 5 项 App-host 检查和 1 条普通 UI 链，保留原日志、测试树、截图、源码与二进制指纹。结果以具体运行的报告为准；写入工程或测试代码本身不计编译/运行通过。真机、完整资源生命周期/heap、稳定 SDK 与整体 C1 保持待验。

2026-10-30/31实际修复见[本地分阶段报告](../../reports/apple-sketch-local.json)。旧iOS18.5诊断CI41035025084仍为4通过/1失败：像素用例在原始CGContext未建立UIKit向下坐标，读到白色行；本地实际附件显示正确颜色位于y737…746。只为测试调用者设置UIKit变换后，严格中心RGBA断言保持，目标色88像素移至y95…104；SketchCanvas渲染实现未改。

本地iOS27另实际暴露无Scene生命周期导致的启动trap。当前使用生成的Scene manifest、UIWindowScene/Scene delegate；Scene失活和断开均走原cancelUnfinished，不把已完成pending作为取消释放。原生命周期用例沿实际App-host Scene delegate核对未完成笔取消、输入预留归零、已提交root保持；最终5项宿主全部通过，无跳过。此处直接调用实际delegate回调，不冒充OS前后台事件或物理触笔投递验证。

Scene连接修复后的早期普通包经CUA真实绘制蓝/红两笔、只读拒绝第三笔、撤销1笔/重做2笔，截图中两色可见。新增Scene取消回调后的最终包另有完整普通payload/noXCTest和启动核对；不继承早期包的整条GUI观察。旧CI失败与本地5项、另一[sketch-host-ios](../sketch-host-ios/README.md)的7项证据互不替代；两套样例使用同一bundle ID，在设备上选择安装其中一个，不能称同时并存的消费者。原Preview16文件与完整普通包保持；跨日中断后模拟器已关机，恢复原磁盘并启动新PID，不宣称原进程连续。
