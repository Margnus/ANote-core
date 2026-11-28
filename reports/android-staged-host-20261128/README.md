# Android 原宿主分批完整帧接线

2026-11-28，DEV-036/C1 第 1、2、4 项的有限集成证据。起点 Core `e33f933` / 蓝图 `d053363`。决定：**调整后采用 Android 原 worker/pool 的分批完整帧路径**；实际预览 App 已消费。首帧约 7.3 秒，尚不满足交互成本要求，不放行 M-1、完整 S2、C1 或生产架构。

## 验收与责任

编码前执行卡要求：既有可保存的 1,900 笔/19 万点在原宿主发布完整 native 帧；保存能在分片间执行；迟到、取消、计算失败、交付阻塞及关闭不发布半帧或提前减账；独立冷分区 oracle 一致；新接受笔迹保存后由新 owner 重开，既有资料保持。本包不修改文档/codec、renderer 总样本或总预算，不切换 Apple 后端。

- 原 `BoundedOwnerExecutor` 的同一 Work/ticket 在成功分片后排回原队尾，输入/输出峰值额度和槽位直到最终 ACK 保持。没有第二 worker/队列/文档 authority。已开始后重新排队的任务在 worker 异常时属于 OutcomeUnknown，而非 NotExecuted。
- `StagedBrushPagePoc` 从规范不可变 Scene 派生临时有序索引，以 32×32 区域、单候选笔迹为分片，使用共享 swept geometry。一个私有完整 atlas 仅在所有分片成功后交付；取消/拒绝清理私有内容，外层额度仍等 ACK。
- 原 256 笔整页路径保留；Android 显式选择分片路径，仍限 renderer 4,096 对象/20 万样本、2 MiB 工作空间、1 亿总工作计数、48/64 MiB pool。额外索引规划计入预留。这里的字节/工作模型是逻辑约束，不声称精确 ART heap 或 vsync deadline。
- 原 bitmap/SurfaceControl、row stride allowance、借用/退休/fence 回执链保持；未知结果隔离、确认无输出后才能结算。保存使用同一原 worker，不覆盖其 owner retained accounting。

## 实际设备结果

Xiaomi 23046RP50C，Android API 35 / OS3.0.7.0，debug 包、合成 API 输入。固定 fixture SHA `22eea352e0ded47f34cabc0c8b543de0126a658377a6ea286cf60130a17c6725`。完整 10,000 笔/100 万点仍由 notebook 拒绝；仅明确命名的 MONOLINE 前缀通过。native 视口 1024×768；设备全屏 2880×1800 超限保持完整 Path 回退。

| 观察 | 结果与范围 |
|---|---|
| 首次完整呈现 | 原宿主 PID 407，2,845 片 / 59,934,374 工作计数；从安装 view 到观察发布 7,305,576,196 ns，含主线程/观察开销，不是纯 raster benchmark |
| 屏幕对照 | 实际 compositor 一个采样像素 `ff395284`，software fallback `ff365083`，各通道容差 8；不称全屏逐像素一致。共享层另以不同分区、无索引全遍历 oracle 全 atlas 逐字节核对 |
| 保存交错 | 下一完整帧计算时实际 writer 到达 PREFIX_WRITTEN_BEFORE_SYNC；发布数仍 1，在途 native 逻辑预留 27,284,192 bytes。保存 ACK 2,653,844,010 ns 含主动观察等待，不称正常保存延迟 |
| 新帧与释放 | 新版本完整帧再次 2,845 片 / 59,939,390 工作计数；累计 2 次发布、2 次真实释放回调，观察到有效 fence；退休后 charge=0 |
| 保存重开 | 原文件写入 5,329,960 bytes，新 NoteStore owner 完整 sameContent 重开 1,901 笔/190,002 点；新接受笔迹保留。本轮不是普通新进程重开或断电验证 |
| 原宿主回归 | PID 1760 的 surface_frames 通过：动态 alpha、zoom/resize、迟到、关闭、未知交接、延迟释放与保存继续；具体可选 false 字段见回执，不扩为已验 |
| 旧资料 | 两次最终运行及失败运行的原 3 个文件集合/摘要保持。原始用户文件只在本机备份，不入证据归档 |

写入重叠时采样 PSS 242,216 KiB、Java used 25,851,896 bytes、native allocated 25,357,408 bytes；不相加、不当进程峰值或统一总预算。runtimeStats 是该进程累计计数，保留完整原始分阶段数据。

## 失败、修复与审查

初次共享测试因夹具名称不存在而编译失败，修正测试引用后通过。初次设备 30 秒观察窗口内未发布，诊断轮到第 456 片仍在计算，累计 ART allocated 3,072,306,736 bytes / GC 215 次。不能把观察超时写成 worker 已取消/终止或预算拒绝；随后仪器终止进程，失败日志保留。

源码检查定位 raster 热循环的 `Function1<Int, Double>` 泛型装箱。改为 primitive 参数/返回值接口并缓存单边坐标，不改几何/绕数/覆盖或工作计数；javap 确认 `(I)D` 调用。最终完整帧前累计 allocated 205,770,968 bytes / GC 12 次；同轮 full-scope GC 请求后累计 94,560,008 / GC 2 次。不同 PID、不同完成点并非受控跑分，不宣称固定百分比收益。采用此针对原调用链的修复，保留耗时仍慢的事实。

从失败责任重新审查了：输入单所有权、分片排队槽位与额度不间断、停止接受后排空、partial receive 拒绝、异常后隔离、迟到帧与资源退休、保存与 native 共存、内容修订和 frame token 分离。新增测试覆盖真实串行 worker 中保存插队、半帧关闭后交付阻塞、第二片前异常且不重试；原宿主套件覆盖实际平台回执。未发现需要扩大额度才能通过的问题。

## 验证、产物与复现

公共 JVM canvas 275 / shared 556、Android 单元 48 项零失败；相关 Gradle 测试任务实际执行。Android App/仪器 APK、lint、verifyPreviewAndroid 通过，lint 0 errors / 17 issues。架构依赖与可移植输入检查通过；具体范围见 [verification.json](verification.json)，不从 JVM 推断 Apple 编译/运行或物理性能。

可运行产物 `dist/NoteCore-0.1.0-preview-android-staged-20261128.apk`，SHA `e6efe2f20777dbff773ab0376146fa9fd7757aed66ad68b34655f488220ed820`。仪器 APK SHA `99771f9cecdf428e9c26e26d14abbb8d9e0b413ad87b3f90e369bc4189066157`。这两个摘要与真机安装包一致。包在本机 dist，不发布应用；历史全局构建回执保持，当前构建 stamp 单独归档。

[证据归档](evidence.zip)保留最终与失败运行、源输入 UTF-8/LF 摘要、真实字节构建 stamp、测试 XML/构建日志及两个本机 runner；摘要绑定在回执内。原始用户笔记和 Activity 清单未归档。运行入口仍为原仪器：

```text
adb shell am instrument -w -r -e notecore_case dense_admission dev.notecore.preview.test/dev.notecore.app.ViewportInstrumentation
adb shell am instrument -w -r -e notecore_case surface_frames dev.notecore.preview.test/dev.notecore.app.ViewportInstrumentation
```

dense 需要上述固定 fixture 放入 `cache/dense-admission-S2.bin`。若厂商阻止仪器后台 Activity 启动，以正常 am start 唤起原 App；不更改设备安全策略。runner 有本机 SDK 路径/serial，其他设备应显式适配。

## 剩余门禁与下一动作

本包收口原 Android 分片完整帧的有限接线；下一步针对约 7.3 秒首帧取得原 renderSwept 几何生成/栅格/索引/分片开销的阶段归因，用相同输入、预算与 cold oracle 决定是否在原 job 内复用不可变几何。不能先新增 authority 缓存或更多旁路 renderer。后续另需完整 S2 的文档/格式/保存与真实混合资源预算契约及 Apple 原消费者对齐；不靠增加常量代替设计。

首帧/物理触笔延迟、固定刷新标准负载、完整进程/GPU峰值、长未信号 fence、启动无瞬态空白、当前 Apple 编译与真实 Pencil 仍待；M-1 五份无效 trace 的拒绝结论保持。本包不重复同配置采集，不修改蓝图需求整项状态。
