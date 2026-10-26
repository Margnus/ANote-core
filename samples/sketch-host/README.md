# 独立 Android 内存草图样例（未冻结）

独立包名 `dev.notecore.sample.sketch`，直接依赖 shared-core → canvas-core。没有 NoteCore App/NoteStore 依赖、文件权限或保存能力。界面明确显示“未保存，退出进程后内容消失”。Activity 配置重建保留同一主线程会话；进程恢复不在本样例范围内。

在项目根目录设置 JDK17、Android SDK 后构建：

```sh
./gradlew -PenableAndroidTargets=true -PenableSampleHost=true :samples:sketch-host:verifySketchHost
```

产物为 `samples/sketch-host/build/outputs/apk/debug/sketch-host-debug.apk`。安装后打开“NoteCore 内存草图”；蓝/红工具和横线背景由宿主注入。只读阻止输入/编辑；活动笔或已完成 pending 存在时不切换工具/只读。输入拒绝保留已接受前缀，明确取消才放弃未完成笔；已完成 pending 不被取消，原 Notebook 的显式重试保留原命令。样例不是对恶意宿主的权限隔离：宿主本身仍拥有 Notebook。

实际专用模拟器仪器复验入口 `tools/run_sketch_host.py` 先记录现场 NoteCore 原包及笔记集合/hash，再安装独立样例/仪器包并比较原件。可指定 `--adb <SDK>/platform-tools/adb --serial emulator-5580 --avd NoteCoreWindows`；默认设备为原 NoteCorePreview / emulator-5554，只允许这两个既有专用 AVD。使用 `--case-directory build/<本轮目录>` 保留不同版本证据，不能覆盖旧baseline来冒称保持，也不套用另一设备的文件数。`tools/verify_sketch_host.py` 保留原API36、66文件及CUA观察的历史固定包核验范围，不能用于宣称新包或本机API35普通UI通过。最初普通CUA证据见 `reports/second-host-android.json`，当前producer子范围单列 `reports/producer-input-budget.json`；本轮无普通UI或真机证据。

只采用为 ENG-19 的可运行 Android 第二消费者子范围；MONOLINE、固定单页、普通 canonical Path。背景回调只借用本帧 Canvas，不得保留 Canvas/Activity。规范 Path/Matrix 按 root revision 缓存，活动 Path 只是派生呈现。没有第二文档模型、worker 或原生图像池。未冻结 SDK，真机、完整资源生命周期/heap/触笔、Apple 样例及 C1 仍待验。

当前样例直接使用 `PagedPreviewNotebook.createValidated` 返回的原会话，显式传主线程 ownerCheck；没有临时 Notebook → snapshot → 第二次 adopt。DocumentBudget/enum 是已验证值；宿主依旧对 owner 实现负责，不将默认空检查解释为线程安全。
