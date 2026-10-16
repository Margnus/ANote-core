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

## DEV-036A：同一作业内的完整墨迹区域（2026-10-16）

基线Core 966a6bc。现有BrushRenderJob/Owner/Executor增加 `admitPage`/`submitPage`，Apple最小桥接和同一Swift dispatch/ACK路径跟进；没有新队列或另一规范模型。输入直接持有不可变SceneSnapshot，只合成捕获layer的MONOLINE v1，依规范Scene顺序先做每笔覆盖union再作RGBA8预乘source-over。每笔catalog在原worker逐个建立/消费，外层输出只在所有目标笔成功后返回。空目标layer输出全透明，其他layer不参与混合；失败、取消或预算不足没有半张可发布结果。完成后仍须原executor drain返回再ACK，旧帧和关闭照原规则退休。

这是**完整请求网格内的透明墨迹层**，不是完整尺寸页面/多tile帧，也没有背景纸张合成。网格仍最多128×128，整Scene最多4096对象、200000点，目标layer最多256笔；超限明确拒绝且规范正文不改。派发前charge包含整个Scene保留样本的28字节/点、8字节/对象引用声明、working、RGBA与native allowance，其他layer虽不绘制也计入持有输入。目录/索引对象头、GC和平台隐藏分配未被这些逻辑值覆盖，不是heap硬上限。工作区为逐笔catalog声明载荷及render临时载荷，合成目标属于最终输出；同一maxEdgeTests在各笔光栅与像素混合间扣减，不按每笔重置。

固定正确性样本是同位置红/蓝各alpha128的两点笔，源顺序得到预乘(64,0,128,192)，反序(128,0,64,192)，额外另一layer绿笔不得污染。后笔光栅工作超限以及首笔已完成后的后笔取消都使完整结果失败；空目标layer、未支持笔刷、过期帧/错误layer及总输入上限有反例。测试手动驱动的owner责任并非App导航关闭实现。

验证入口仍为ci/verify.sh、verify_android_brush_common.py、verify_apple_brush_common.py与verify_ios_host.py，不建立第二套执行环境。JVM277、ART91（共同72、真实执行器6、Bitmap5、持有器7、trace1）、Native78（共同72、测试线程3、CoreGraphics3）、app-host17单列；Swift7项渲染宿主中的新增1项属于A，旧6项不改称新范围。ART/Swift均实际消费新合成像素，READY/阻塞重送保持peak，ACK后CPU8192+native16384=24576；关闭后held reader仍24576，确认释放后0。

首轮共享失败是测试使用切帧前的token去验证容量拒绝，修正为当前token；首轮Swift失败是新v2夹具头写1笔但实际2笔，修正计数，不放宽像素/alpha断言。两次设备状态问题均先核实实际状态：已停的专用模拟器恢复原设备，仍活的Android进程只重连既有5581端口，未wipe/卸载/清数据。XCTest后恢复普通包及核对全部payload，包SHA 66262aa5…；不继承UI-001，不声称新包UI已验。

结论：采用此路径作为DEV-036后续完整帧的原作业增量。**DEV-036整体与普通App接入仍未完成**：尚缺多tile整体完整性/全屏预算、真正View目标与引用生命周期、Model在View消失后有限批量drain/ACK及UNKNOWN责任持有。不能把“区域完整”改称“全页面完成”，不放开笔刷/格式、不用离屏像素证明GPU、物理帧/heap、完整C1或SQLite恢复。下一步仍在原owner/执行器内补完整帧和真实呈现目标，按现行C1清单验收。

## DEV-036B：单作业内多tile完整墨迹帧（2026-10-16）

基线Core17ad1a5。将只承载坐标/尺寸的输出网格与128×128光栅网格区分为同一sealed类型族，不放宽旧BrushTileGrid的限制。完整帧允许单轴1–4096、总像素至多8388608，并检查最后一个tile的origin仍在原光栅坐标范围。Apple导出边界按同一规则拒绝；不抛未声明的Kotlin构造异常给Swift。

一个原BrushRenderJob先预留**整个输出**RGBA、native副本额度、保留Scene输入和声明工作区，在原worker按规范笔序、每笔按不重叠tile依次生成mask，写入私有完整RGBA。末尾不足128的行/列按实际尺寸生成，所有tile继承同一世界采样格点；不能把一个完整帧拆成多个独立可见的发布结果。任一tile失败或取消，整帧结果没有pixels，原owner只在executor回调外ACK后结算。没有新帧队列、tile结果pool、内容revision或发布authority。输出仍是透明墨迹，纸张/页面裁剪由宿主原路径负责；不是GPU资源生命周期承诺。

共同验证用130×130跨横纵128边界的两笔半透明线，与独立64×64分块生成的参考逐像素对照，含边缘和末尾2像素行/列；验证后tile超工作上限时前tile不可发布，旧128网格限制保持，4096×4096超总像素拒绝。完整native输出130×130的RGBA67600、native allowance135200，ACK后202800；关闭held reader仍202800，确认释放后0。ART Bitmap和Swift CGImage实际复制/绘制跨缝九点，预乘(64,0,128,192)，不存在逐tile重复混合。

此节点验证共同278、ART93、Native79、Swift宿主18，单列不相加。源码/产物见原报告入口；XCTest后恢复普通包并核对payload，不将UI-001转移到新包。本轮没有真机/heap/帧指标，不据130×130验证宣称8百万像素上限均可在交互帧预算内完成。maxEdgeTests/资源上限是可拒绝的工作准入，不是已测帧率；普通App依旧未消费新后端。

下一步在原宿主owner接入有限批量drain/ACK/UNKNOWN保留与真实View呈现；完整静态帧与动态笔交替、导航/后台/重开尚未验收，DEV-036整体保持进行中。A中的“只128×128请求区域”是其历史边界，B扩大了有界输出能力，未开放笔刷或冻结C1。
