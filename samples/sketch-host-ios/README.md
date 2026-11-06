# 独立 Apple 内存草图（未冻结）

独立项目 `SketchHost.xcodeproj` / scheme `SketchHost`，包名 `dev.notecore.sample.sketch.ios`，仅链接现有 shared-core → canvas-core 的 `NoteCanvasPrototype.framework`。不导入/复制 NoteCore App 的 Store、Model、输入View、renderer或存储执行器。

在仓库根目录，先按 `ci/package-ios-preview.sh` 构建当前 Simulator framework，再运行：

```sh
xcodebuild -project samples/sketch-host-ios/SketchHost.xcodeproj -scheme SketchHost -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' -derivedDataPath samples/sketch-host-ios/build/ordinary CODE_SIGNING_ALLOWED=NO build
```

实际专用模拟器验证与打包：

```sh
python3 tools/verify_sketch_ios_host.py --device 7F2A0226-A39E-4A86-BB26-B77DB5E3C1B0
```

脚本先核对原NoteCore App普通payload与既有全部笔记，再构建/运行独立样例的11项app-host测试，恢复独立样例自己的普通payload后启动；不会安装/清除原App。普通包在 `dist/NoteCore-SketchHost-0.1.0-ios-simulator-arm64.app.zip`。只支持此专用模拟器验证入口，非真机IPA/发布脚本。

界面明确“未保存，退出进程后内容消失”。固定单页595×842、MONOLINE1、宿主注入蓝/红工具与背景绘制回调；只读在Core输入/编辑之前拒绝，活动或pending时不能改工具/只读。拒绝批次保留Core已接受前缀，需要明确取消未完成笔；pending仍由原Notebook持有，取消不丢完成命令，重试走原Core。resize/移出window明确取消未完成contact；已提交root和同一Session可继续被新View消费。没有进程恢复/保存承诺。

`SketchSession`只是本样例的owner/工具策略，没有第二文档模型/authority/worker/pool。宿主仍持Notebook，不构成权限隔离。`offer`仅转交DOWN/MOVE/UP原结果，超批次由host在Data分配前拒绝；CANCEL走显式`cancelUnfinished`，不制造事件时间或伪Core结果。编辑只允许撤销/重做；工具通过`selectTool`。背景仅借用当次CGContext，回调不得保留context或View。

最小候选契约见[最小预览消费者契约](../../docs/最小预览消费者契约.md)。Core原owner回调/预算值构造/索引仍有程序前置条件，样例外线程拒绝先于Core调用，不等于Core本身对任意Swift线程安全。不可变checkpoint可以交外线程只读编码；mutable Notebook跨线程移交未支持。11项测试与普通CUA交互分别记录，合成UIKit不是OS/Pencil。真机/heap/时延、完整public白名单及C1均未放行。

当前源码复验可加`--case-directory build/<本轮目录>`，避免读取旧包的CUA观察；原默认目录/verify-only仍按其各自来源核对，不用新包hash重写旧UI证据。新增全列表工具配置和非允许编辑拒绝保持的用例。

画布仅在自身接受DOWN后记录原Notebook的activeInputOperationId。布局/脱离window/取消仅结束匹配的本画布笔，迟到MOVE/UP不得追加到另一画布的新笔；Session级显式取消仍是同owner操作。实际UIKit两画布回归见[输入归属报告](../../reports/sketch-host-ios-input-owner.json)，不是OS多窗口事件或物理触笔验收。

背景借用独立save/restore图形状态，并清理回调留下的current path；整个draw另保存/恢复调用者路径。回调须平衡自己的save/restore且不得保留context，完整任意恶意回调不属于支持范围。实际注入translate/clip/残留路径后蓝色中心像素与调用者状态回归见[背景隔离报告](../../reports/sketch-host-ios-background.json)。


2026-11-06补证：本次在另建且测试后删除的专用iOS27.0模拟器运行11项，原NoteCorePreview设备始终关闭、16笔记与两App payload保持。新增用例直接检查prepare非预留/状态变化重检，以及原Android worker所产pending的身份匹配、宿主测试IO确认后的退休与单次恢复，见[报告](../../reports/sdk-consumer-conformance.json)。Core本身不验证IO回执；测试中的Foundation同步写读是测试设施，未增加产品保存能力。原脚本仍要求其专用设备和来源收据/产物前置；本轮采用的隔离设备命令及源码快照在报告原始证据中。dist普通包/旧GUI继续保持旧产物范围，此结果只验证SDK/消费者测试。
