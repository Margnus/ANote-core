# ADR-0009：有界真实线程执行与结果责任

2026-10-14；DEV-020，D3/C1候选。本次在JVM/Android共享source set实现BoundedOwnerExecutor：创建线程为owner，独立串行Thread执行任务，结果由owner事件循环有限批量接收。公共结果/身份/状态放commonMain；Java monitor/Thread仅放jvmAndAndroidMain。Apple实际执行器、平台自动唤醒和App正式存储接入仍待验证。

## 容量覆盖完整生命周期

准入同时检查条目和声明字节，默认64条/8MiB。额度覆盖QUEUED、RUNNING、READY、DELIVERING和DELIVERY_BLOCKED；执行完成或worker退出不释放额度，只有接收方正常返回ACK才移除并清空闭包/结果引用。ticket只持有独立identity和递增编号，不保留整个执行器或payload。

每个accepted条目已经保留结果槽，worker完成时只把该条目标为READY，无需另行争抢回调队列或向无界Handler投递大payload。结果滞留会阻止新任务准入，不能靠丢弃结果给新工作腾空间。拒绝前未执行compute，输入仍由调用方持有；对核心submission/resource ticket，宿主也必须保留对应责任，不能把准入失败当作已提交或已释放。

数据任务使用显式input接口：执行器持有原始不可变输入，成功、未知或未执行结果都连同同一个input交给owner，接收ACK前不释放。只有没有数据责任的控制任务可使用Unit便捷入口；命令/资源不能仅藏在compute闭包里。charge是调用方声明的输入/预期结果/临时工作逻辑峰值，不测量实际heap；lambda不得捕获无界可变状态。跨线程输入应是不可变snapshot/patch或明确独占的资源capability，不能直接操作UI/文档owner状态。生产仍需arena、真实计费、设备基线和全局预算。

## 真实owner、worker与交付

使用[Java monitor的wait/notifyAll](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Object.html)保护任务/结果状态和发布可见性；等待放在条件循环中。计算和接收回调均在锁外，worker不会持锁运行IO/owner代码。owner/worker检查比较真实Thread对象，线程名字和数字ID不是权限。DocumentCloseCoordinator新增可注入ownerCheck，公共读写先检查；公共参考测试默认无平台线程检查，真实平台必须注入。

worker FIFO执行；owner drainResults每次最多处理指定数量，默认16，不创建自动无界回调链。宿主需在自己的owner事件循环泵结果。回调正常ACK后才归还额度；返回RETRY_REQUIRED或抛异常，原结果留在FIFO头并阻止后续结果越过，需要显式retryDelivery。重试交付不重新执行compute，foreign/重复ticket不能清掉其他条目。禁止回调递归drain；回调仍可查看状态或停止准入。

若接收方先修改owner状态再抛异常，执行器不能推断它是否接管成功。接收方必须按稳定operation/receipt身份幂等处理重交付；没有“任意回调恰好执行一次”的保证。若compute在外部commit/free之后抛异常，结果分类为OutcomeUnknown，不宣称回滚，也不自动重跑。提交可按原patch/CommandId查询或幂等重放；析构必须查询是否已发生，不能直接重复free。

## 停止与关闭

stopAccepting只拒绝新任务，已接管的排队任务继续执行，ready/blocked结果继续留存。没有shutdownNow、强停Thread或clear。worker在停止且工作队列空后退出；isDrained还要求所有结果已ACK。因此“线程退出”不是“全部责任已结束”。任务自设interrupt会在下一任务边界清除，不能污染已接管的下一任务；未预期的基础设施终止报告workerFailure，并利用已预留结果槽交回责任：已经执行但未确定结果的任务为OutcomeUnknown，尚未开始的任务为NotExecuted；已有ready结果保持。接收确认前继续计费，不伪造成功。

文档beginClose停止新输入但不应过早停止后端执行器：未知提交/释放仍可能需要查询、重试及回收任务。宿主确认无需新增清理任务后，再stopAccepting，并同时等待文档CLOSED与执行器isDrained。awaitWorkerStop是测试/诊断等待，超时不取消任务，生产UI不得用join阻塞；[Thread的join语义](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Thread.html)不能代替结果ACK。进程崩溃/退出不由内存队列保证，需要正式存储ledger。

## 证据与后续

15项JVM实际线程测试覆盖：FIFO/有限接收、运行/排队/ready统一容量、Long字节边界、停止/超时不取消、交付失败/异常的结果保留、计算后异常的未知分类、自interrupt隔离、异常worker终止/未执行责任与ready结果保留、错误线程/foreign ticket、递归drain拒绝、跨真实worker/owner的原patch恢复与未知析构查询。总验证与此前单线程逻辑lane分开标记；测试计时不是触笔性能结果。

Android专用Instrumentation增加ART真实worker/owner检查：满队列拒绝、worker退出仍保留结果预算、接收重试不重复执行、错误线程不能关闭文档。它与3项多指窗口事件检查放同一独立报告，但单列executor_thread_source。完整21项App流程继续回归。iOS仅编译公共契约和宿主，没有真实Apple执行器/启动证明。

下一节点DEV-021：Apple执行器适配及统一跨平台conformance，明确Main/文档owner唤醒、停止后结果与capability生命周期；之后再接入App保存与正式存储恢复。当前预览App沿用原保存实现，不用本轮测试替代平台线程/GPU/持久化门禁，C1不冻结。
