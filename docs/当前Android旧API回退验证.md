# 当前Android API26/32回退消费者验证

2026-11-10，C1第5项的当前源码支持证据。复用原 `ViewportInstrumentation` 的 `input_batches / storage_retention / owner_retirement / minimum_api`，以及原 `InkCanvasView / NoteStore / PreviewFileIO`。没有改业务源码、minSdk、Manifest、后端或生产格式。

## 环境与数据边界

旧 `NoteCoreMin26`、`NoteCorePath32` 的配置与系统镜像只读使用；两个新的可写 AVD 数据目录分别位于 Core `build/android-avd-min26-current`、`build/android-avd-path32-current`。旧 AVD 的 userdata、快照和笔记没有复制或启动。本次结果独立生成，不重绑旧 API 报告。

端点保持原验证入口允许的 API26/5582/NoteCoreMin26 和 API32/5586/NoteCorePath32。两份镜像归档的 SHA1 与原官方包记录一致，配置来源/本次配置摘要、系统 fingerprint 见各 environment/report；WHPX/SwiftShader 属于模拟器条件，不是设备性能。

首次安装当前 App/仪器 APK 后，使用既有 `fixtures/transfer/android-paged-v2.ncp` 作为非空保留基线。它来自旧自动合成触摸轨迹，三页三笔，不是用户笔记；按原 documentId 存放并验证原字节。运行原检查前后及普通进程启动后，该一份文件的集合/摘要保持。不能将此结果称为非空真实用户库保护、完整生产迁移或耐久保存验收。

## 实际结果

| 原消费者范围 | API26 | API32 | 证明范围 |
|---|---|---|---|
| 原View真实MotionEvent回调检查 | 10组通过 | 10组通过 | 批次/坐标/历史/容量拒绝与恢复；事件为合成对象，不是OS触笔 |
| 原Store保存与恢复 | 11组通过 | 11组通过 | 独立cache实际IO、typed拒绝、失败保留重试、pending恢复及同revision身份反例 |
| 原Store owner退休 | 9组通过 | 9组通过 | 线程/配置准入、活动输入/picker、未完成读取、ledger满及失败重试；突然owner丢失仍未验 |
| 原Path回退像素 | 通过 | 通过 | 线段/点笔、undo/redo、空页及选回原页像素，Surface实验费用为0 |
| 普通App进程启动 | 通过 | 通过 | 显式force-stop后新进程启动、资料库显示固定合成笔记；未发送普通窗口手势 |

两端点均绑定104项当前输入（95字节精确、9仅CRLF归一），安装的App和测试APK均与本轮本地产物摘要一致；源码从构建前到运行后保持。工具中的唯一cache目录隔离各组真实IO，既有输入/保存/owner检查集合完整比对，失败不产生“部分通过”报告。

API26第一次抓屏处于启动过渡，仅作为诊断保留。之后在原Activity窗口存在时另取稳定画面；API26/32两张稳定资料库截图已查看，不将截图或`am start`成功当完整普通UI旅程。没有执行真实Provider交互、普通手指书写/系统多pointer、硬件Canvas/SurfaceControl、真实长期GPU阻塞、旋转/分屏或物理延迟/heap测试。

证据：[API26](../reports/current-api26-fallback/report.json)、[26来源及产物](../reports/current-api26-fallback/verification.json)、[API32](../reports/current-api32-fallback/report.json)、[32来源及产物](../reports/current-api32-fallback/verification.json)。保留原构建、安装、四组仪器日志、普通进程启动、过渡和稳定截图、窗口状态与环境摘要。原用户wrapper摘要保持且未提交。

## 采用与下一出口

采用当前源码在两个旧API端点上的原Path/输入/Store失败及owner子矩阵，关闭“这些入口只有旧源码证据”的缺口。它不是全部26–32 OS/厂商/设备、普通GUI或全部公开API白名单适用证明，也不覆盖iOS或生产资源/存储。各需求原完成条件、M-1与整体C1/DEV-041门禁保持；不因这一平台子范围通过提前替换SQLite或扩大预览功能。

后续沿真实受影响入口处理普通窗口/硬件/厂商与物理门禁，或其他已授权的原消费者缺陷；不扩相同OS的重复样本。复验入口为 `tools/verify_android_fallback_current.py --adb <adb绝对路径> --api 26或32 --output <新的证据目录>`，先确认对应专用AVD已经启动。工具拒绝覆盖证据目录，完整重新构建/安装、原合成文件校验与源码绑定，不读取旧报告作为新结果。
