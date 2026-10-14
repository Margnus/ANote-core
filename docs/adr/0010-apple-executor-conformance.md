# ADR-0010：Apple有界执行器与跨平台对照

2026-10-14；DEV-021，候选适配器。Apple实现位于独立Swift Foundation文件，不引入UIKit/笔记业务；iOS工程编译同一文件，macOS命令行运行同一实现的真实线程检查。它承接ADR-0009的责任契约，尚未冻结为公共SDK或接入App保存。

## 调度与所有权

一个专属Foundation Thread串行执行任务，NSCondition保护条目、状态和结果可见性，等待置于条件循环；计算和owner接收回调均在锁外。遵循[NSCondition等待规则](https://developer.apple.com/documentation/foundation/nscondition/wait%28%29)。与为每个任务向DispatchQueue投递闭包相比，worker仅从有界条目中取任务，结果仍留在同一槽位。

owner绑定显式串行DispatchQueue，构造/准入/查询/交付/重试/停止均检查[queue-specific上下文](https://developer.apple.com/documentation/dispatch/dispatchqueue/setspecific%28key%3Avalue%3A%29)；main owner也允许主线程入口。传入queue必须为串行，不能从另一执行器借用标签作为权限。闭包不得跨线程操作owner可变状态。命令/资源必须通过显式不可变input或独占capability传入。

64条/8MiB默认逻辑额度覆盖QUEUED/RUNNING/READY/DELIVERING/DELIVERY_BLOCKED；结果接收ACK前输入与结果仍保留。ticket持独立UUID/编号，不能保留执行器/payload。满队列拒绝不执行compute；停止只拒绝新任务，排队任务继续，worker停止后仍计费未ACK结果。

owner唤醒最多一个无payload异步块待执行，每次最多交付16项，剩余ready结果再预约一个wake；blocked队头不重复泵、不让后续结果越过，显式retryDelivery才再次交付。接收抛错视为责任不确定，保持原输入/结果并阻塞FIFO；重交付不重跑计算，接收方仍须幂等。计算抛错为OutcomeUnknown，不能宣称副作用已回滚。

executor必须保留到stopAccepting且isDrained；worker退出清除Thread引用，ACK清除输入/结果闭包。awaitWorkerStop只用于测试/诊断，生产UI不得等待线程。应用生命周期不应在未知提交/释放仍需查询时过早停止后端。自动wake可关闭以供宿主手动泵或受控测试。

## 可比较范围和差异

9项协议场景通过Python收集器与JVM真实XML对照：FIFO有限批次、全状态容量/停止、字节溢出、blocked原身份重交付、回调部分应用后抛错、计算后未知、owner/worker隔离、递归drain、foreign ticket。Apple另有ACK释放payload、40项自动wake、blocked显式retry，共12项实际线程检查。JVM原有15项、Android ART独立检查仍为各自证据；不能将不同平台测试数相加称为公共测试数。

Swift可恢复throw被保留为未知结果；fatalError、Objective-C异常、VM/进程死亡不由该内存适配器恢复，没有模拟成JVM的NotExecuted基础设施终止能力。当前Swift 5语言模式与项目一致；Sendable/Swift 6全面迁移、原生资源/GPU、真实heap峰值和全局预算仍未验收。对照证明列出的协议行为，不证明平台实现全面等价。

报告reports/apple-executor-conformance.json记录编译器、Swift输入、工具、实际JVM XML与verification报告hash。工具先拒绝过期核心验证，再编译执行Apple测试；测试有3秒barrier与60秒外层超时，不将计时解释为笔迹性能。macOS执行不替代iOS启动：本机没有iOS模拟器runtime，iOS宿主仅编译通过。

## 下一节点

DEV-022：接入双端App保存后端，先明确pending最新全量snapshot、in-flight与失败恢复责任/额度；清除后台读取owner集合的竞态，有界读取在分配前执行；保存成功后才显示“已保存”，列表/打开与在途最新snapshot一致。新增故障测试后再推进分页，仍不将预览文本格式当正式SQLite/崩溃恢复。
