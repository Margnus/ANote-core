# DEV-034B Apple 渲染桥接（未冻结）

## 里程碑卡

- 编号/依据：DEV-034B，DEV-033 owner/ACK 与 DEV-034A native permit；基线 Core 958ff6e（执行规范后续79b00d9）。
- 关键风险：真实 Kotlin/Native 渲染能否在既有 Swift worker 上交付；结果重送与平台持有期间是否错误归还预算。
- 范围/依赖：专用 ARM64 iOS Simulator app-host、现有执行器与共同 renderer。普通 App 后端及笔刷支持不扩展。
- 验收：共同分段/mask/RGBA 真实运行并消费像素；ACK 外结算；同一结果重送不重算；取消/旧帧/关闭/UNKNOWN 不提前释放；唯一 native reader；复制失败有责任对象；已确认清理归零。
- 验证：共享 ci/verify.sh；package-ios-preview.sh 绑定源码/framework；verify_ios_host.py 专用 UDID；XCTest 后恢复无插件普通包并核对全部 payload。
- 调整条件：CG 隐藏分配、GPU fence、owner 丢失不能由本实验证明，保留门禁，不把逻辑预算当 heap。
- 交付/退出：桥接、Swift adapter/持有器、宿主用例、源码/二进制报告；证据通过才决定作为后续 trace 的实验基础，生产采用仍依赖 M-1/C1。

## 边界与责任

桥接仅位于 iosMain，public 可见性只为 Kotlin/Native Objective-C 导出，非生产 API 承诺。Swift 传入 canonical StrokeObject 与 FrameToken，Kotlin 创建共同分段 catalog、作业和资源池账本。既有 AppleBoundedOwnerExecutor 是唯一排队/运行/结果权威；Swift adapter 禁用自动 pump，调用原 drain 后逐一结算 ACK，不在 receive 内释放预留。桥接不扩展普通 App 支持笔刷。

作业执行只接收线程安全的取消读取闭包；owner 方法只在构造时指定的串行 owner 调用。结果包装仅创建一次，重送同一包装，不重算。执行失败由 Swift 传 UNKNOWN；ACK 只隔离，停止 worker 后显式核实本作业没有外逸 CPU/native 输出才可协调。取消、切帧、关闭不提前释放峰值预算。adapter 和图像持有器须保留到 drained；完全丢失 owner 的自动回收/跨线程移交不在本实验范围。

图像由真实 bitmap CGContext 复制 RGBA 并 snapshot 成 CGImage，不外借原始 CGImage。创建前预留 context+snapshot 两份行存储；检查实际 bytesPerRow。context 在返回前释放。持有器强持有 adapter 和唯一 native reader。绘制仅接受有 CPU data 的 bitmap context，检查 frame/layer/关闭状态；没有 GPU 生命周期承诺。close 清除本持有器的 ARC 图像引用后才归还许可，此证据是已结束的受控 CPU 引用责任，不是 CG/GPU/heap 物理回收证据。释放边界抛错记 UNKNOWN，禁止破坏性重试；只有引用已清空可只读协调。复制失败先清理；未知时返回 CleanupRequired 持有器。调用者必须持续保留责任对象。

## 验证与决策

专用 ARM64 iOS Simulator `7F2A0226-A39E-4A86-BB26-B77DB5E3C1B0` 实际 app-host 15 项通过，其中 6 项是本阶段新增：真实 Swift worker 运行共同分段/mask/RGBA 与原生颜色消费；同一结果重送/接收抛错不重算；取消排队作业、旧帧、关闭；UNKNOWN compute 明确隔离至 worker 停止并协调无外逸输出；唯一 native reader、预算不足与失败复制清理；清理确认丢失返回责任持有器，close 不做破坏性重试，已清空引用只读协调归还；非 owner 线程无法释放。无效重复执行在 Kotlin 边界转 UNKNOWN，不以 Swift catch 假装能捕获未导出 Kotlin 异常。

固定样本四点、64×32、65536 工作预算：READY/接收未 ACK 保持 90320 字节；drain 返回后 24576；关闭且图像持有时仍 24576，并阻挡 total=100000 下新的 90320 作业；受控引用释放确认且最后 reader 归还后 0。CGImage/context 行存储上界是 16384，CPU RGBA 是8192；非 heap/GPU 实测。

证据：[宿主源码/framework/二进制报告](../reports/ios-host-tests.json)、[普通包构建](../reports/ios-preview-build.json)、[XCTest 后普通包恢复/payload 核对](../reports/ios-preview-restored.json)。后者只证明安装内容，无 UI 或新包交互回归。旧 UI-001 保留其包 SHA，不转移到此包。74 项 Apple 共同/CoreGraphics/测试 NSThread 回归另列 [原生报告](../reports/apple-brush-common-tests.json)，不是 Swift 接入证据；272 项共享 JVM 也另列。

结论：**采用为 DEV-034C 的未冻结实验基础，生产采用证据不足**。已排除“另建生产 worker”方案；App 接入路径是通过同一 owner 在现有视口生命周期准入/取消、驱动 drain 后 ACK、UI 持有同一平台 holder 到明确释放。普通 App 当前未调用此 backend，继续只支持 MONOLINE。先用固定视口 trace 补队列等待/拒绝、复制和长持有成本，再依据 M-1 与 C1 决定生产后端。owner 完全丢失/跨线程接管、物理触笔/heap、硬件 GPU fence、任意变换、SQLite 恢复均未验收。
