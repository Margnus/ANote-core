# DEV-034A：Android 平台图像持有适配

DEV-036E最新增量：原holder硬件拒绝保持，真实RenderNode RecordingCanvas反例及原InkCanvasView app-host硬件目标已验，ART94项；普通包仍未消费新后端。64份原文件保留，最新APK仅新增无内容诊断，不能继承旧UI。Bitmap/View释放契约仍不足，SurfaceControl releaseCallback/fence仅为待证明候选；具体决策和同owner/实际层级接入门禁见[既有ADR-0020](adr/0020-read-only-legacy-and-measurement-first.md)、[C1清单](C1未决事项.md)与[Android分范围报告](../reports/android-render-integration.json)。以下DEV-034A范围/旧测试数保留历史口径。

2026-10-15。DEV-034 的第一个可验收子阶段，不代表 Swift 接入、固定视口 trace 或整个 DEV-034 完成。

## 实现与责任

新增 `canvas-core/src/androidMain/.../AndroidBrushImagePoc.kt`，只在 Android source set 使用 Bitmap/Canvas。共同模型、资源池、既有 worker 和单 owner 不增加依赖或调度层。适配器不向调用方返回原始 Bitmap；软件 Canvas 绘制前检查 owner 线程、当前 frame/layer 和持有器状态。过时/关闭/已释放/未知释放/硬件 Canvas 均不展示。

创建前检查 `staging RGBA + ARGB_8888 图像` 至少两份逻辑字节的 native allowance，再借唯一 native reader。真实分配后核对 allocationByteCount/rowBytes，异常布局显式拒绝并清理，不将其发布为预算内图像。这是准入/实测行存储检查，不保证厂商内部隐藏分配或整个进程 peak heap；意外布局的首次 API 分配仍可能超过预估，生产后端选型前须补设备基线。

成功图像持有原 executor 和 reader 的强引用；界面脱离后可在同一 owner 保留/移交此持有器，不重新建立 authority。调用方仍须保留持有器直至确认释放，尚未实现 owner 彻底丢失时的自动回收或跨线程接管。原生许可留在已有资源池，持有器不维护第二套预算。

`close` 在 recycle 且 isRecycled 确认后归还 native reader；确认后重复 close 为幂等。释放抛出或返回但图像仍存活时标 UNKNOWN，保持同一许可/CPU 输出预算，禁止再次破坏性重试。`reconcileReleased` 只读 isRecycled 确认后协调归还；超时和对象不再显示不是释放证据。

创建/像素复制失败时先清理；若清理结果未知，返回 `CleanupRequired(holder, reason)`，不能只返回错误而丢掉责任对象。注入点仅在像素复制/释放调用边界，图像始终由真实 Bitmap API 分配。失败注入不代表测得真实系统故障率。

## 实际测试

新增 6 项 Android ART 测试：

1. 真实像素绘制后变更视口，旧图像拒绝展示；24,576 字节持续计费，在 total=100,000 时阻止新 90,320 字节作业准入，关闭/释放后归零。
2. native allowance 不足时不占用唯一许可。
3. 复制失败明确 recycle，同一 CPU 输出可重新创建持有器，无需重算 renderer；同时禁止第二份 native 许可。
4. 释放前失败，重复 close 不再调用释放，未 recycled 时不可协调，独立清理事实确认后才归还。
5. 复制失败且 recycle 后确认丢失，返回 CleanupRequired；关闭仍计费，只读平台事实协调后归零。
6. 非 owner 线程不能释放、读取持有状态或绘制。

与既有覆盖/颜色/滤波/执行器测试一起，独立库测试 APK 共 84 项（68 共同、5 真实执行器、5 Bitmap、6 持有器），见 [Android 报告](../reports/android-brush-common-tests.json)。报告指纹新增 androidMain，避免平台实现变化未使证据过期。共享 JVM 272 项与架构/UTF-8/LF 验证保持单列。

## 下一步

DEV-034B 接 Apple 已有 Swift 有界执行器及 CGImage 持有，明确 Kotlin internal 作业与 Swift 的实验边界、回调外 ACK 结算和取消/未知结果；DEV-034C 固定滚动/缩放 trace。owner 丢失/平台资源接管、厂商行存储、GPU fence、真实 heap、任意变换、M-1、C1和 SQLite 均未完成。普通 App 和既有可运行包没有切换新后端。

## DEV-036F增量：原App SurfaceControl呈现与真实回执（2026-10-17）

上文“普通App未切换”属于DEV-034历史。当前API33+普通Android包代码已接入原宿主层级，实际app-host消费完整静态帧；普通包UI仍待验。新增受限opt-in AndroidInkFrameExperiment，不导出原始Bitmap/像素/reader/池；原AndroidBrushImagePoc硬件拒绝不变，只在CPU composite中调用。原NoteStore共享executor/主Handler结算drain外ACK并保留关闭责任，View仅借完整帧；无旁路调度或资源权威。

单个ImageWriter/Reader的RGBA上传核对pixelStride=4、rowStride及原native allowance，完整纸张/裁剪/墨迹一起交付HardwareBuffer。入站Image.getFence的独立句柄交给setBuffer；原ViewRoot事务协调上层Path撤下。静态child在SurfaceView下层，active在原View上层。旧帧/页面/viewport/尺寸变化、API不支持、预算拒绝或失败都保持完整规范Path。

close不把Transaction返回当作完成。真实releaseCallback保留单个SyncFence回执，主owner非阻塞await(Duration.ZERO)确认有效fence完成后才关闭HardwareBuffer/Image/writer/reader和CPU source，归还同一native许可。缺回执/未signal保持charge；歧义或UNKNOWN保留同一对象，禁止破坏性重试/超时归零。post只用于唤醒原owner，有限控制poll不携带第二份数据队列。64MiB总量/48MiB resident及复制预留不是厂商内部heap的实测上限。

[现有报告](../reports/android-render-integration.json)绑定最终2cfe9f5c…包、API36真实compositor及有效release fence。原层级像素/active-static alpha、空页/缩放/resize、等待回退、View移除后迟到ACK、共享存储保存及新NoteStore文件重开2页3笔、UNKNOWN保留48,906,240字节后只读协调为0均通过；原64文件未变。ART95与共享279单列，真实RecordingCanvas拒绝仍通过。普通CUA UI/普通PID新后端消费、长时未signal/歧义交付恢复、API26–32/厂商矩阵、真机heap/M-1/完全owner丢失/C1/SQLite仍待门禁。
