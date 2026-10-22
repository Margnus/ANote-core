# DEV-032：双端原生图像、滤波接缝与成本边界

2026-10-15，接续 DEV-031。新 mask 已由实际 ART 和 Kotlin/Native 生成并分别进入 Bitmap/Canvas、CGImage/CoreGraphics。仍是 internal 证据实现，普通 App 未切换后端，C1 未冻结。

## 共同输出与责任

`BrushTilePixelsPoc` 将持有 lease 的读者转换为独立不可变 RGBA8 预乘载荷。调用方只给 RGB，alpha 来自已完成同笔 union 的 mask，不会再乘原始 alpha 或荧光笔透明度。整数预乘使用 `(channel × alpha + 127) / 255`。转换前校验单次输出字节预算，中途取消不发布部分结果；失败/成功都不替调用方归还 mask 读者。转换成功的 payload 与 mask 分别拥有存储，归还 mask 后 payload 仍有效。

FrameToken、layer、网格随输出传递，派生像素不是第二份文档正文。当前只演示同步、单 owner 输出；像素载荷没有被偷偷加入 CPU mask 缓存，原生副本也没有混入 mask 的逻辑预算。正式 pipeline 必须在转换前同时预留作业、输出及原生副本的总量，不能用每个 job 各自没超额替代全局上限。

四项新增共同测试覆盖预乘/一次 alpha、归还后独立所有权、准入/取消、真实邻域格点以及边界不钳制。`withGutter(1)` 扩大世界网格并正常渲染真实邻域，不能用复制边缘像素替代。超过当前 128 像素实验限制或坐标限制时显式返回不可准入，不能缩小 gutter 冒充有效结果。

## 接缝规则与实际发现

双端用相同的三种笔刷、带部分覆盖/折角/回折的输入，比较整块与左右分块。缩放为 1.5、2.0，平移为 3.25 设备像素。Android 使用 Bitmap 双线性滤波，Apple 使用 CoreGraphics low interpolation；每个平台与其自己的整块结果对照，不要求两个平台的滤波器位级相同。

首次 Apple 缩放测试失败：直接在分数坐标上裁剪左右 core，会引入裁剪边界的混合，nearest 也出现差异，滤波样本最大通道差达到 61。保持原输入和严格阈值，改为设备像素中心的唯一归属：axis-aligned core `[left, right)` 对应整数 scissor `[ceil(left − 0.5), ceil(right − 0.5))`，y 同理；图像采样位置不取整。这样只对呈现区域划分所有权，不移动正文坐标或原始点。

最终结果：两端 nearest 分块与整块完全一致；六组滤波对照（三笔刷 × 两缩放）的内部区域含接缝，最大预乘通道差均为 0，验收上限保留 1 级舍入。无 gutter 对照分别出现 Android 183、Apple 199 个通道差异，证明样本确实暴露了缺少邻域的问题，而非只测全透明或均匀填充。

此规则只验证正向、轴对齐、上述放大倍率及两种指定滤波器。内部对照没有验收设备最外侧裁剪 AA、任意旋转/透视、缩小、高阶滤波或 GPU 纹理 atlas；不能把一像素 gutter 宣传成所有后端的通用半径。正式适配器须按采样核/变换确定邻域，并定义设备像素 core 的无缝归属。

## 存储与复制成本

64×32 core 的 mask 与 RGBA 各为 8,192 字节。扩大一圈到 66×34 后各为 8,976 字节（+9.57%）；两个 32×32 core 各扩到 34×34，总载荷为 9,248 字节（+12.89%）。这些是格式逻辑载荷，不含目录、对象头、工作几何、GC 或原生复制。

Android 真实 `allocationByteCount`：输入 Bitmap 8,192、输出 Bitmap 8,192，rowBytes=256。当前测试适配器还用独立 8,192 字节 ByteBuffer 复制 RGBA，不是假定零拷贝。Apple 输入 CGImage、输出 bitmap context 均报告每行 256 字节、32 行；CGImage snapshot 可共享/延迟复制，所以不能把两者简单相加宣称唯一 heap 分配。

两端报告分别记录本次转换、复制/创建、绘制、释放 API 的纳秒耗时。单次探索包含首次调用和后端初始化噪声，不据此比较平台快慢或给 P95/P99；release/recycle 返回不代表 GC、驱动/GPU 或系统驻留已全部回收。当前没有真机、进程峰值内存或泄漏稳定性测量。

## 可复现验证

- 标准 Gradle：258 项 JVM 测试、0 失败/错误/跳过；依赖/可移植性检查通过。
- Android：59 项共同测试加 4 项 Bitmap/Canvas 测试，共 63 项；[源码/测试 APK/观测报告](../reports/android-brush-common-tests.json)。
- Apple：59 项共同测试加 3 项 CoreGraphics 测试，共 62 项；[源码/原生二进制/观测报告](../reports/apple-brush-common-tests.json)。CoreGraphics 测试位于 `iosSimulatorArm64Test`，没有污染 commonMain，也不依赖 JVM 预生成 mask 或新增 SDK 诊断桥。
- [Gradle 报告](../reports/verification.json)不冒充原生运行；普通 App 包及早期成本报告保留各自历史源码口径。

复现沿用 `bash ci/verify.sh`、`tools/verify_android_brush_common.py --serial emulator-5580 --adb <adb>`、`tools/verify_apple_brush_common.py --device 7F2A0226-A39E-4A86-BB26-B77DB5E3C1B0`。

## 下一交付 DEV-033

把已验证 renderer 与现有有界执行器/资源池接成实际作业闭环：总量预留在派发前完成，输入/工作/输出/原生副本分开计费，视口替换与取消不能提前释放工作者责任，迟到结果必须退休。先用真实执行与固定视口轨迹验证并发上限、背压、关闭和读者长持有，再考虑平台缓存/App 接入；不再新增只定义接口的旁路层。补缩小/旋转等后端能力矩阵后，结合 M-1 真机基线评审 C1。正式色彩管理、生产 SQLite 和完整功能仍待完成，Mac 锁屏依赖交互继续延期。
