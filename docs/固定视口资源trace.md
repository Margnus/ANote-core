# DEV-034C 固定视口资源 trace

## 里程碑卡

- 依据：DEV-034A/B 的真实 Bitmap/CGImage 持有器、既有串行执行器、同一共同 renderer 与资源池；基线 Core eb5935d。
- 问题/范围：固定滚动/缩放期间，长持有旧 native reader 是否阻挡新作业、取消/迟到/关闭是否仍保留正确 charge。Android ART 与真实 iOS app-host 分别跑同一输入/步骤；不改普通 App。
- 输入：四点高亮笔、64×32、工作65536、native allowance16384、resident32768、total210000、最多2作业；三个视口 origin=(0.25,8)/(4.25,8)/(4.25,8)，pixelSize=0.5/0.5/0.25。
- 步骤：首图长持有→滚动退休→阻塞现有worker入口→第二作业排队→第三请求拒绝→取消排队作业→解除阻塞→待真实结果READY→缩放使结果过期→ACK丢弃/取消→新视口图像→关闭→依次归还新旧reader。
- 验收：双平台事件及charge一致；等待和复制分列、旧reader退休可见；关闭保留两个reader责任，最后释放后归零。计时只记录一次探索，包含受控阻塞/轮询，不能评价调度/真机性能。
- 交付：平台测试、源码/二进制绑定报告及可重跑入口；采用结论依证据更新。M-1、GPU/heap、任意变换、C1、SQLite 不在出口内。

## 实测与结论

真实 Android ART 独立测试APK共85项（新增1项trace），iOS app-host共16项（新增1项trace）通过。共同272项及原生Apple74项回归保持单列；不跨平台相加。源、测试二进制与平台报告见 [双端trace校验报告](../reports/render-viewport-trace.json)、[Android](../reports/android-brush-common-tests.json)、[iOS宿主](../reports/ios-host-tests.json)。iOS trace是保留的XCTest JSON附件，Android是instrumentation结果字段，不是手写观察记录。校验器 `python3 tools/verify_render_viewport_trace.py` 检查来源指纹、二进制与十个事件。

| 事件 | 总charge | retired | executor子集 |
|---|---:|---:|---:|
| 首图持有 |24576|0|0|
| 滚动后旧图退休 |24576|24576|0|
| 两作业/第三请求拒绝、排队取消、READY未ACK |205216|24576|180640|
| 迟到/取消结果ACK |24576|24576|0|
| 缩放新图与旧图同时持有 |49152|24576|0|
| 关闭但两个reader在途 |49152|49152|0|
| 新reader归还 |24576|24576|0|
| 旧reader最后归还 |0|0|0|

双端十个事件及上述charge完全一致，未超过210000总预算。executor的180640是同一资源池峰值预留的子集，不能重复相加。两个native创建行存储上界均为16384/份；CGImage/context可能共享，不宣称独占heap分配。

等待以submit前后时间戳夹住实际准入位置，再到worker的beforeCompute入口，给出上下界及submit本身耗时，包含handoff开销。不能将request→worker全段（含catalog准备）直接称作纯队列等待；取消排队作业的等待也包含前一作业的受控阻塞。复制记录单次create API耗时，事件时间戳用于分别算native resident/retired持有及closed→drained时间。样本为单次受控探索，不能比较双平台性能优劣，也不是帧/触笔/heap/GPU测试。

结论：**采用现有资源池、唯一native permit与回调外ACK作为后续集成约束；生产后端选择仍证据不足**。trace验证背压/长持有/关闭的责任不变量，无需新队列或第二预算authority。DEV-034A/B/C范围内实验已收集对应平台证据。App接入须沿当前document/surface owner驱动expect→submit→drain/ACK→holder→release，在同一生命周期内消费；当前普通App未消费该后端，MONOLINE限制保持。M-1物理设备基线、owner完全丢失/跨线程接管、GPU fence、任意变换、完整C1与SQLite恢复继续未验收。

XCTest后已恢复无插件普通模拟器包并核对完整文件集合/哈希，见[恢复报告](../reports/ios-preview-restored.json)。本次没有新包UI交互或启动证据，不继承旧UI-001的验证范围。
