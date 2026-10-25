# owner交接与失败责任矩阵（C1候选）

本表区分现有行为与尚未支持的交接，不批准稳定SDK、生产存储或C1。原Notebook及App服务仍只有创建线程上的同一个owner；不可变checkpoint交付不转移可变authority。

| 事件 | 当前支持/拒绝 | 内容与资源责任、恢复入口 |
|---|---|---|
| 同一owner换View或Activity重建 | 独立双端消费者已验证 | 保持原Session/Notebook/root；明确取消未完成输入，不重建文档authority |
| 同Notebook多个原App View交错输入 | 原双端View用原已受理DOWN的operation身份核对归属 | 空闲兄弟配置/取消不影响原前缀；竞争DOWN在改变共享笔配置前拒绝。App显式取消后旧View的迟到MOVE/UP/CANCEL/拆除不能改变后来输入；仅拥有当前输入的View显示其活动Path |
| Android多个编辑窗口中一个返回资料库 | 原Store登记最多8个窗口身份，非最后窗口离开只解除自己的登记 | 保持同一Notebook/authority，另一个窗口的活动输入仍可完成；最后显式退出先把完整内容交原ledger，pending必须等真实写入确认后才能retireAfterRecovery |
| Android编辑Activity销毁/配置重建 | onDestroy仅解除旧呈现端，新onCreate经原get/open/adopt复用同一Notebook | 真实Activity.recreate已验证同对象/root，原服务仍持未保存内容。窗口身份每实例唯一，无Activity强引用；不是进程重启恢复或工具状态完整验收 |
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

## 多窗口与线程边界的事实

ENG-04的现行验收要求worker只读快照、多个窗口共享同一文档authority的提交序列，没有要求把可变authority迁移到另一个线程。候选实现继续以同一主owner串行提交，拒绝外线程可变调用；是否批准此支持边界仍需C1，不以“拒绝迁移”代替owner错误恢复或完整多窗口验收。

原Apple App在App级只有一个StateObject Model，WindowGroup注入同一对象；实际普通包生成的UIApplicationSupportsMultipleScenes为true。原Android MainActivity从NoteStore.get取得同一app级服务，构造器private，isolated为内部验证入口。正常入口没有每窗口创建Store/Model的路径；这不构成防止任意内部代码为同目录新建第二服务的注册/锁协议。当前仍只有一个activeNotebook，生产多文档/窗口导航及保存路由需后续设计与实际多窗口验收。

Android原Store现登记最多8个、每个最长64 UTF16单位的窗口身份，仅为原owner的呈现归属字段，不保存文档、快照或Activity。重复登记同身份幂等，第9个/坏身份明确拒绝；尚有编辑端时不允许全服务退休/直接releaseActive。一个窗口退出沿原saveOwned准入后只移除自己，另一个窗口仍用原Notebook。最后退出遇pending时，必须原savedRoot/savedPending确认匹配且ledger已结算，否则保留窗口和会话让用户重试；真实目录失败→修复→原retry→恢复记录写入→退出→原reader重开→单次提交已验。

编辑窗口存在时，新建或打开另一文档在UI明确拒绝，不触发内部check，也不替换原authority；这仍是单活动文档预览限制，不能作为生产多文档能力。没有编辑端时，切换前先将旧会话完整内容交原ledger，pending仍等确认；读取损坏/不支持文件本身不退休旧会话，只有有效内容的adoptForEditor才准备切换。并发异文档读取返回时也重新检查原owner，而不是信任发起时的空闲状态。

saveOwned使用原准入/worker/ledger，不创建接管队列；它没有UI receiver，不能用空回调覆盖同一已受理内容的现有UI receiver。对已被新内容替代的旧receiver仍仅解除呈现引用，正文责任归原ledger；所有窗口保存状态的广播尚未实现。实际Activity用例通过测试反射暂时替换原get的单例为独立cache Store，正常onCreate/open/editor、“返回”按钮回调及真实recreate均执行，最后恢复原单例；它不是普通系统触摸或全部多窗口验收。Apple根视图仍共享导航状态，其按窗口离开语义另待接入。来源见[报告](../reports/editor-attachments.json)。

新activeInputOperationId只在原input存在时返回原strokeCommand.value，拒绝不变，取消/完成/退休为空，后续DOWN身份不同。View只保留自己DOWN受理后的身份引用，所有校验与续写在原主owner同步进行，没有另建token序列、authority或队列。它不是内容revision、跨线程许可、持久化回执，也不保证平台字符串桥接/活动Path的物理heap额度。App级显式cancelStroke仍可取消当前输入，pending已完成命令的责任不变。

双端原View的合成UIKit/MotionEvent回调证明输入隔离；这不等于系统同时向多个窗口投递触摸、完整窗口导航/配置隔离、所有surface渲染释放或真机触笔验收。来源与原件保护见[报告](../reports/surface-input.json)。
