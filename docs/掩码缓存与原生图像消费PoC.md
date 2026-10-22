# DEV-031：掩码缓存、读者预算与 Android 图像消费

2026-10-15，接续 DEV-030。落地 CPU 缓存及实际图像消费，仍是 internal 实验，不冻结 C1，不改变普通预览 App 的绘制和文件格式。

## 缓存与发布责任

`BrushTileCachePoc` 复用 `ResourceLeasePool` 的准入、LRU 和 lease 生命周期，不另建资源账本。新生成前预留 `width × height × 4` 字节；驻留和总量预算分别约束，已淘汰但被旧读者持有的掩码继续计费。总量不足显式拒绝，保留现有驻留结果。最后读者归还后 CPU 池移除引用；这只证明逻辑责任结束，不证明 GC、原生或 GPU 内存已回收。

像素内容的身份和展示帧分开。键包括 catalog 的不透明身份、完整世界像素网格（原点、尺寸、pixelSize、子采样）和容差；catalog 固定源笔迹、brush、transform、layer、分段策略及规则。仅同 scene、根 revision、session 可复用。viewport、surface、request 改变可以命中，但每位读者使用本次请求的 FrameToken，通过 gate 后才可展示，不能拿生产者旧 token 冒充当前帧。

catalog 重建即使 IDs 相同也不命中，这是保守策略。不透明键不保留 catalog 或规范样本数组，目前需要调用方按需持有并复用 catalog。后续跨 catalog 或无关对象改动复用，必须先证明不可变源身份与修订规则，不能直接删掉检查。

读者只提供只读采样接口，不暴露数组；归还后访问失败。外部不得复制资源引用绕过 lease。关闭拒绝新请求，保留已有读者责任；渲染期间关闭后迟到结果直接退休。取消/失败的私有 job 不进入缓存。当前为同步单 owner 实验，未接入 worker 调度、线程断言、GPU fence 或 native Bitmap 生命周期。

## 测量与反例

合成十万点荧光笔、40 次视口请求，驻留预算 8,192 字节、总预算 12,288 字节：22 次命中、18 次生成；与无缓存渲染逐像素比较 20,480 个 coverage 值，完全相同；每次 acquire 返回后观测到的最大 charge 为 8,192 字节。这不是生成期间真实峰值，更不是 heap 测量。时间仅保留单次 JVM 探索记录，不给通用加速倍数。

旧读者压力反例：驻留限一块、总限两块，两代资源被持有时计费 4,096 字节；第三块明确 `cache-total_limit`。归还退休读者后降至 2,048 字节，重试成功。关闭并归还全部读者后 drained，规范十万点不变。见[缓存轨迹与源码指纹](../reports/brush-tile-cache-costs.json)。

计费仅 packed coverage IntArray 逻辑载荷。规范样本、catalog/index、对象头、job 临时几何、GC 延迟，以及 Bitmap/CGImage/纹理都须另计，不能把这里预算当整页内存上限。job 工作预算由 DEV-030 renderer 独立控制；并发集成还必须建立所有 in-flight job 的总预算。

## 原生发现与图像消费

首次 iOS Kotlin/Native 执行触发 `ConcurrentModificationException`：`ResourceLeasePool.publish` 删除最老条目后继续访问旧 Map.Entry，JVM/ART 没暴露该行为。现改为修改前捕获 key/value，同步修复 `WeightedLruCache`，并将原有资源池 15 项与 LRU 6 项纳入两端实际执行。新缓存 8 项反例覆盖退休读者、跨页面/修订/session、网格/容差、预算、取消、关闭和迟到结果。Apple 脚本现先保存运行日志再报告非零退出。

Android 两项测试直接执行 ART 共同算法、借用缓存读者并创建真实 premultiplied Bitmap，再由 Canvas 消费：

- 2,048 像素验证输入 alpha 与白底输出颜色，允许整数预乘的单通道 1 级舍入差；含部分及完全覆盖。原始 alpha 204 乘荧光笔 0.25 后为 51，回折/分段不重复加深。
- 相邻 tile 用 nearest 放大两倍，与整块图像的 8,192 输出像素完全一致，包含接缝两侧。

不宣称线性滤波、任意变换、色彩管理或 gutter 正确；与 DEV-028 原生路径 AA 开关测试是不同范围。测试专用 APK 不进入普通 App。

## 门禁与后续

- 标准 Gradle：254 项 JVM 测试，0 失败/错误/跳过；依赖边界、UTF-8/LF、原型演示通过。
- Android：55 项共同几何/缓存/资源测试与 2 项 Bitmap/Canvas 测试，共 57 项，在 API 36 专用模拟器执行。
- iOS：55 项共同测试由 Kotlin/Native 编译并在现有 ARM64 模拟器执行；不是新 mask 的 CoreGraphics 图像消费证明。
- 源码/二进制绑定见 [Android](../reports/android-brush-common-tests.json)、[Apple](../reports/apple-brush-common-tests.json)、[Gradle](../reports/verification.json)。旧离线和 App 包报告保留历史源码范围。

DEV-032：补 Apple 原生 mask 图像消费；验证双端滤波/缩放和 gutter；将 CPU 掩码与原生图像的独立分配、复制、释放成本纳入同一轨迹。再评估有界异步调度和平台缓存集成。M-1 真机帧/触笔/内存、C1、SQLite 与生产闭环仍待完成。Mac 锁屏依赖交互继续延期，不阻挡离屏工作。

复现：`bash ci/verify.sh`、`:canvas-core:runBrushTileCacheCosts`、`tools/verify_android_brush_common.py --serial emulator-5580 --adb <adb>`、`tools/verify_apple_brush_common.py --device 7F2A0226-A39E-4A86-BB26-B77DB5E3C1B0`；环境沿用构建说明。
