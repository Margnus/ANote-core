# 独立 Android 内存草图样例（未冻结）

独立包名 `dev.notecore.sample.sketch`，直接依赖 shared-core → canvas-core。没有 NoteCore App/NoteStore 依赖、文件权限或保存能力。界面明确显示“未保存，退出进程后内容消失”。Activity 配置重建保留同一主线程会话；进程恢复不在本样例范围内。

在项目根目录设置 JDK17、Android SDK 后构建：

```sh
./gradlew -PenableAndroidTargets=true -PenableSampleHost=true :samples:sketch-host:verifySketchHost
```

产物为 `samples/sketch-host/build/outputs/apk/debug/sketch-host-debug.apk`。安装后打开“NoteCore 内存草图”；蓝/红工具和横线背景由宿主注入。只读阻止输入/编辑；活动笔或已完成 pending 存在时不切换工具/只读。输入拒绝保留已接受前缀，明确取消才放弃未完成笔；已完成 pending 不被取消，原 Notebook 的显式重试保留原命令。样例不是对恶意宿主的权限隔离：宿主本身仍拥有 Notebook。

实际专用模拟器仪器复验入口 `tools/run_sketch_host.py` 会先核对 NoteCore 原包及66份文件基线，再安装独立样例/仪器包；只用于已有 NoteCorePreview / emulator-5554，不是通用真机运行脚本。`tools/verify_sketch_host.py` 核对编译输入戳、APK类、依赖、实际事件标量/资源像素/Activity重建结果与CUA观察。当前证据见 `reports/second-host-android.json`。

只采用为 ENG-19 的可运行 Android 第二消费者子范围；MONOLINE、固定单页、普通 canonical Path。背景回调只借用本帧 Canvas，不得保留 Canvas/Activity。规范 Path/Matrix 按 root revision 缓存，活动 Path 只是派生呈现。没有第二文档模型、worker 或原生图像池。未冻结 SDK，真机、完整资源生命周期/heap/触笔、Apple 样例及 C1 仍待验。
