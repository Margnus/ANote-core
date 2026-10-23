# 原输入分块arena与逻辑预留

2026-10-22总预算卡增量：原notebook在DOWN用原authority的剩余live正文额度、待分配实际object/layer头及格式上限决定本笔maxRealSamples，arena预留随该容量收紧。正文满额时零接收、不创建pending且不消耗身份，可撤销恢复；共同API在后续整批超额时保留已接收点并允许合法重试/空UP。这里不是journal/history/identity/保存/渲染总预留，平台不可恢复输入拒绝仍有原显式取消/提示策略。结果以[当前状态](当前开发状态.md)和[输入报告](../reports/preview-input-contract.json)的对应版本范围为准。

DEV-040M，2026-10-22。直接用于原InputReplaySession/原notebook，仍为未冻结预览路径，不是生产heap保证。

## 存储与规范输出

原active的逐点InputSample列表替换为StrokeSamples内部Builder。每块最多256点，私有Double x/y、Float pressure、Long micros数组；末块不超过配置总容量。原InputBatch及真实点仍由调用方按既有契约复制/准入，不保留平台事件、Data/NSData或预测点到arena。一次批次先完整校验时间/容量，之后才分配新块和写入；拒绝不扩块、不追加有效前缀，也不推进原序列。

UP把私有块一次复制到准确长度的4个规范数组，直接交给既有StrokeSamples私有构造器；不再先创建临时数组再调用copyOf进行第二份完整复制。Builder不返回可变数组，完成后清空块引用并关闭，旧Builder不能再次append/finish。规范StrokeSamples的公共copyOf仍对外部数组防御复制，格式、坐标/压力/时间、brush target与ID/revision语义均不变。

块是输入容器细节，与派生几何/索引的分段无关，不写入文件。取消/close只清未完成arena，已完成规范输出由原pending/command/root继续持有；不把清引用或logical charge归零当作系统GC/物理内存已释放。

## 预留模型

| 项目 | 声明logical bytes |
|---|---|
| 当前arena | 256 + 128 × 已分配块数 + 28 × 已分配点容量 |
| 完成输出 | 128 + 28 × 实际规范点数 |
| 最大输入预留 | 512 + 128 × ceil(maxRealSamples / 256) + 56 × maxRealSamples |

28来自两Double、一Float和一Long的原始载荷；固定/块overhead为声明额度，不是JVM/Native sizeof。预留覆盖私有arena与完成数组同时存在，且块容量始终不超过maxRealSamples。10点预留1200，预览20000点预留1130624；旧536只算单份点载荷，不能承诺相同峰值。原DocumentCloseCoordinator使用该函数，在开session/分配arena前拒绝不足的预算；旧536预算明确拒绝10点，不通过调低charge维持接受。

InputReplaySession.reservedByteCharge是配置的峰值额度，activePointByteCharge才描述当前arena逻辑持有，不表示实际heap。原PagedPreviewNotebook.inputReservedByteCharge受原owner守护：active返回完整峰值预留，pending完成笔返回规范样本logical charge，取消不能清掉pending。成功规范提交后该输入责任归零，正文/历史/保存预算继续负责已接收规范内容；这不是总内存归零。

## 采用与未决

采用原累加器内的分块primitive存储和单次规范完成；没有新队列/worker/authority或另一笔刷算法。追加工作与新点数成正比，完成仍O(N)；没有宣称物理延迟改善或峰值heap达标。

pending command/ID等非点元数据尚须汇入整体预算，不包含在pending规范样本的logical charge内。调用方的InputBatch/预测列表、原生采集/跨语言复制、正文/历史/保存/渲染、平台隐藏分配及GC延后不包含在该预留内。原预览每批1024、每笔20000、全文200000的准入不变；完整生产point arena/producer预算、整体背压与设备heap仍需后续收敛，不能凭该公式关闭ENG-07/C1。

当前公共块边界/冻结/拒绝/预测/跨笔反例纳入原JVM及ART库runner，原View补验active预留/完成归零，pending预留由原notebook回归覆盖。Apple必须在Mac重建framework/App及相关宿主，不转用旧Native报告。真实结果见[输入arena报告](../reports/input-arena.json)。


## 原notebook消费输入额度（2026-10-23）

映射ENG-06/ENG-07/ENG-14，基线6021028；已先整合远端live字节准入。目标是在原双端notebook而非旁路模型消费arena预留：DocumentBudget.inputBytes为每个notebook的活动输入逻辑额度（默认2MiB），DOWN前取点上限与额度同时满足的最大容量；预算连1点都不足或首批超限时，在session/身份分配前零接收。活动笔固定已预留容量，后续整批拒绝保持原前缀/序号，调用者可缩小批次重试或明确取消。取消释放未完成输入；完成失败的pending规范点继续持有责任。

验收：不足预算不分配身份；精确预算/256→257块边界；超限批不部分接收且较小重试可提交/格式往返；关闭/取消与pending拒绝保留；原Android View回调预算拒绝与恢复。共享JVM及原Android宿主实际执行，Apple当前编译/宿主仍待Mac。默认20k点预算不变。此字段只约束原输入组件，平台原始事件/临时批数组、文档/历史/render/保存等仍有独立责任，不能声称总heap或C1完成。


## 2026-10-23：原producer标量直接进入同一arena

映射ENG-06/ENG-07，基线Corec1fd413/蓝图f574ca1。原notebook.offerRealBatch在长度/值/容量/身份前检后不再构建逐点InputSample及InputBatch复制列表；原InputReplaySession.consumeReal同步消费标量视图，写入同一私有primitive arena。旧InputBatch/预测入口保留且共用状态机，不增加内容owner/队列。标量视图仅活在当前调用栈，数组不能被保留；调用方必须在同一owner同步调用期间不改数组。Builder在分配/写入前验证整个源，完成仍一次展平原StrokeSamples。

验收：新旧入口跨256块逐字节/压力bit/时间/target等价、caller数组事后改变不能改已接受点；坏尾点/shape/时间/负envelope/超容量整批拒绝不变前缀/序号，较小合法重试与空UP正常；旧预测与指针/导航规则保持。共同JVM+ART库与原App View输入/Store回归；Apple包含新反例但须实际Mac重建。此减少既有明确临时复制，不是测得heap/延迟或跨语言零复制，原生producer数组/Data与整体峰值仍待验。


实际采用结果：新5项与原JVM共370项通过；实际ART132（原127+5）及原App View8组/Store-Surface宿主通过，原规范1份hash/集合保持。验证了257点跨块后修改全部caller数组不改变已接受点、Float负零bit/目标固定、坏尾点/时间/shape/负envelope/容量零接收与合法重试、空UP/取消/混合旧预测/导航保持。原notebook不再构造InputSample点列表或InputBatch复制列表；legacy列表API仍可用，共享状态机只有一套。标量视图不进入Active/完成输出/返回Preview，Builder分配与写入前完成全源验证。

独立审查保留旧有效输入的event/pointer/target/预测与取消顺序；raw特有shape/负envelope返回typed拒绝。所有数组仅同步读取，调用期间并发修改属于调用方违规，未承诺并发快照或任意OOM恢复。Apple collector源码测试集合精确期望116项（原111+5），未在Mac编译/运行，不改旧Apple报告。当前跨语言Data/原生数组、元数据、整个输入/历史/render/save叠加heap/帧/时延仍待真机，不能据此关闭C1。
