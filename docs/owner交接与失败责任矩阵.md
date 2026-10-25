# owner交接与失败责任矩阵（C1候选）

本表区分现有行为与尚未支持的交接，不批准稳定SDK、生产存储或C1。原Notebook及App服务仍只有创建线程上的同一个owner；不可变checkpoint交付不转移可变authority。

| 事件 | 当前支持/拒绝 | 内容与资源责任、恢复入口 |
|---|---|---|
| 同一owner换View或Activity重建 | 独立双端消费者已验证 | 保持原Session/Notebook/root；明确取消未完成输入，不重建文档authority |
| 外线程调用可变host | 独立样例在访问Core前返回wrongOwner | 回到原owner操作；Core本身仍以显式ownerCheck作为前置条件，不承诺任意导出调用都可恢复 |
| immutable checkpoint交外线程只读编码 | Apple独立消费者已验证非空checkpoint | 接收方只持快照与独立编码String；不携带Notebook/reader/owner回调 |
| Apple原Model外部强引用先消失 | 原服务从初始化自持，尚未请求退休也保留责任 | 既有失败账本、当前authority与native holder留在同一Model。调用者须保留能找回原owner的句柄；weak句柄恢复操作已实测。不创建替代Model处理旧责任 |
| 原owner显式退休 | 活动/待提交输入、未完成导入导出、保存拒绝分别阻止退休 | 先完成或明确处理原操作，再向同一owner重试退休；不能以ARC、GC或超时替代 |
| 已受理写入真实IO失败 | 原ledger保留完整快照及retryRequired | 修复目录后调用同一owner的retryRetainedWrites；真实写入完成才结算，随后原worker停止并解除自持 |
| 外部CGDataProvider跨退休继续持有 | 同一Model/native holder继续计费 | 真实releaseData回调后由原主owner结算；unknown需既有只读reconcile，不重复破坏性释放，不另开pool |
| 所有外部句柄丢失但主线程仍活 | 不提供自动查找/接管 | 自持保留责任，也会持续占用内存与worker；这是未完成退休的服务，不是可恢复完成。App必须保留服务入口；当前单App级StateObject满足正常生命周期 |
| owner线程退出、主线程停摆、进程终止 | 不支持内存owner接管 | 自持不能提供进程耐久；未经确认IO、替换中断/断电和生产回执协议仍待 |
| mutable Notebook/worker/reader迁移线程或重复Model打开同一目录 | 未定义为受支持交接 | 不通过忽略ownerCheck、复制authority、旁路队列或第二pool实现接管；多窗口/跨线程交接仍属C1未决 |

Apple原Model的服务生命周期以init开始、beginOwnerRetirement受理后实际drain结束。关闭当前文档或失去外部引用均不等于服务退休。长生命周期服务的自持是明确保留责任，会增加未正确退休调用者的保留时间；不承诺自动回收、内存峰值或物理heap结论。

本轮新增证据只覆盖原Apple App的两条引用丢失路径，双端独立样例和既有退休证据按各自报告源码/产物范围有效。完整public白名单、所有owner失败边界、真实设备M-1/heap/触笔与IO耐久仍待。
