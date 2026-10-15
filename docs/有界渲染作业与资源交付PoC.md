# DEV-033：实际渲染作业、峰值预留与资源交付

2026-10-15。将 DEV-030 renderer、DEV-032 RGBA 输出、既有 `BoundedOwnerExecutor` 和 `ResourceLeasePool` 接成可执行闭环，不是增加一套空接口。仍为 internal 原型，普通 App 未切换后端，C1 未冻结。

## 数据流与预算

owner 在派发前准入不可变 catalog、目标 FrameToken/layer/grid、RGB 及工作预算。现有执行器独占排队/运行/结果队列；`BrushRenderOwnerPoc` 只保留有界的准入与交付账本，不再另建调度队列。JVM/Android 使用现有真实串行 worker，在该线程执行分段查询、几何、mask 和 RGBA 转换，worker 不访问 owner 的资源池或可变 gate。

每个作业保守预留：`规范样本与分段 bounds 载荷 + renderer 工作上限 + RGBA 输出 + native 副本预留`。规范样本为 28 字节/点，bounds 为 32 字节/段；共享输入按作业重复计费，宁可多预留，未把它当实际复制量。索引对象/引用、对象头、GC、线程栈、GPU/驱动及平台隐藏分配没有伪装成已测内存。作业数与资源/读者数量另有上限。

资源池新增峰值预留与结算：worker peak 可大于 resident 上限，但必须占用 total 预算；保留输出上限必须能放入 resident。未结算的 peak reservation 不能直接 publish。执行器清除输入/结果引用并 ACK 后，owner 才结算为 `RGBA + native allowance`；结算使旧 reservation 失效。不能因为工作者算完、发出了取消、回调开始或等待超时就提前缩减。

结果回调失败会保留同一结果/charge，显式重送而不重算。纯渲染拒绝/取消、不执行结果也要 ACK 后归还；执行异常的 UNKNOWN 结果隔离，JVM/Android 适配器必须确认 worker 停止后才能显式协调“无外逸输出”，不自动重试。原始规范点、已接受文档命令和正文 revision 均不改变。

## 取消、切页与关闭

JVM/Android 使用 AtomicBoolean 协作取消，不 interrupt worker。已排队任务仍由原执行器负责交付取消/失败结果。FrameToken/layer 在 admission 与最终 ACK 两次检查；回调已到但 ACK 前换视口也不能发布旧结果。过时的已分配输出直接退休，不替换较新的 resident。

视口变更退休旧 resident，旧读者继续计费；重复 expect 相同帧/layer 为无操作。读者展示前必须经过 `canPublish`，旧读者可以继续安全读取但不能再展示。关闭停止准入/发布，保留所有排队、运行、待确认、未知和读者责任，直到明确结束才 drained。

## 原生副本与唯一许可

普通 CPU 读者没有 native copy allowance。每份输出只能借出一个 `borrowNative` 许可，该许可可覆盖一个声明范围内的 staging/native 图像组；不能每个读者各复制一份却只计费一次。native 许可不能用普通 returnReader 归还，必须在原生释放尝试后报告结果。NOT_RELEASED 保留许可和 charge；UNKNOWN 隔离，后续普通 RELEASED 不能绕过协调；明确协调已释放后才能归还。这不是 GPU fence 或实际系统驻留证明。

Android 测试走完整链路：既有 worker 生成 → owner ACK → native reader → ByteBuffer/Bitmap → close → recycle → 确认归还。Apple 测试在真实 NSThread 生成，由 owner 借 native reader、写入 bitmap context 并创建 CGImage，CGImage/context release 后确认归还。测试适配器实测/API 行存储检查在预留内；没有把原生指针藏进 commonMain。

固定四点样本、64×32 输出、64 KiB 工作上限的账本如下；数值为逻辑准入，不能当作实际进程峰值：

| 阶段 | 计费字节 | 实际责任 |
|---|---:|---|
| 派发/运行/READY/接收但未 ACK | 90,320 | 输入/bounds 208 + 工作 65,536 + RGBA 8,192 + native allowance 16,384 |
| ACK 后输出驻留 | 24,576 | RGBA 8,192 + native allowance 16,384 |
| 关闭但 native reader 尚持有 | 24,576 | 保留同一资源；禁止发布，未 drained |
| 原生释放确认且最后读者归还 | 0 | CPU 责任已结束，不声明 GC/GPU 回收时间 |

Android 实际 Bitmap allocationByteCount 8,192，加 staging 8,192，落在 16,384 allowance 内。Apple image/context 各 256 字节/行、32 行，保守行存储上界 16,384；snapshot 可能共享，不能称为唯一 heap 实测。多个输出和运行作业共同受同一 total 预算约束。

## 实际验证与范围

- 9 项共同测试：实际 mask/RGBA 对照、ACK 前后预算、取消/切帧/关闭、作业与总量背压、旧读者、错误/UNKNOWN、跨 owner、溢出与未结算发布、独占 native 许可/未知释放。
- 5 项共享 JVM/Android 测试在真实现有 worker 上运行：FIFO 阶段/容量、ACK 后结算、接收重送不重算、过时结果退休、UNKNOWN 等待 worker 停止。
- Android 另有真实 Bitmap/native reader 关闭释放闭环，与既有颜色/滤波回归合并，共 78 项（68 共同、5 真实执行器、5 Bitmap）。
- Apple 共 74 项（68 共同、3 CoreGraphics、3 NSThread/CGImage 交付），其中 NSThread 使用测试专用单槽 handoff。**尚未接入 App 的 Swift AppleBoundedOwnerExecutor**，不把这项写成通过；后续不能重新造一套生产队列替代它。
- 标准 Gradle 共 272 项 JVM 测试，0 失败/错误/跳过，源码依赖与 UTF-8/LF 检查通过。结果与源码/测试二进制绑定见 [JVM](../reports/verification.json)、[Android](../reports/android-brush-common-tests.json)、[Apple](../reports/apple-brush-common-tests.json)。历史 App 包、离线/成本报告保留各自提交范围。

新增 `jvmAndAndroidTest` 让同一套执行器集成测试在 JVM/ART 执行，独立离线编译器也已加入该源码目录并实际通过 272 项；[离线报告](../reports/offline-jvm-verification.json)本次未编译 KLIB、不替代 Gradle/原生运行。实际线程测试与普通共同逻辑测试分开计数，不把跨平台重复通过相加成一个“完成分数”。执行器自己的 chargedBytes 是全局资源池预留中尚未 ACK 的子集，不重复相加。

## 下一节点 DEV-034

将已验证 native reader/副本预算规则落实为平台图像持有适配器，补 owner 丢失、未知释放及超预留的责任交接；接入 Apple 既有 Swift 有界执行器，复用相同作业/ACK 规则，不新增另一调度权威。用固定滚动/缩放 trace 比较队列等待、拒绝、复制、驻留/退休与关闭，不先扩展 App 功能。当前仍无 M-1 真机帧/触笔/heap 验收，缩小/旋转/色彩管理、C1、SQLite 和生产闭环未完成。Mac 锁屏依赖交互继续延期，独立开发继续。
