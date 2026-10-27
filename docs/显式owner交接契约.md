# 显式 owner 交接契约（未冻结）

2026-10-27，C1第4项、ENG-04/ENG-14的有限采用决策。复用原PagedPreviewNotebook、不可变PagedPreviewSnapshot及原ownerCheck，不新增会话authority、线程调度器或存储协议。实际证据见[原交接报告](../reports/release-handoff.json)与[原JVM owner消费者](../shared-core/src/jvmTest/kotlin/dev/notecore/document/DocumentOwnerThreadTest.kt)。

## 可以采用的路径

1. 原 owner 在自己的线程完成/显式取消活动输入。正文由原保存责任方准入；若有完成但未提交的 pending，必须保留完整 checkpoint，不能用 capture 的正文部分替代。
2. 原 owner 捕获不可变 checkpoint；普通正文按原 retire 契约退休。pending 只在宿主确认同一 checkpoint 的真实 IO 后调用 retireAfterRecovery。引用比较守卫不是磁盘成功证明，回执由宿主负责。
3. 通过明确同步边界移交不可变 checkpoint。原可变 Notebook/authority 不能换线程继续使用；新 owner 提供自己的 ownerCheck 和新 sessionNonce，用原 openRecovery 创建一个新会话，原旧会话已退休。
4. 新会话不会自动提交 pending。用户或原恢复流程显式 retryPendingStroke 后仅提交一次；再次调用不生成第二笔。旧会话及调用者仍持有的不可变 root/checkpoint 不会被修改或自动回收。

新会话的历史/身份账本重新建立，不宣称跨会话保留 undo journal。规范内容、对象身份和已完成真实点保持；新 owner 只能继续自己线程上的操作。需要历史持久化或并发会话的生产协议不由这条预览路径提供。

## 实际验证及明确边界

原DocumentOwnerThreadTest从3项增至5项，完整JVM434项通过。新增两项用实际Thread/FutureTask同步和join确认旧线程终止后再启动新owner，旧Notebook在外线程的snapshot/提交仍抛owner前置条件。第一项在相同不可变root上继续编辑，原checkpoint编码保持。第二项制造原完成pending，拒绝普通退休和错误checkpoint，真实临时文件FileChannel.force(true)并逐字节读回后退休，另一线程读文件、恢复和显式单次提交，源文件不变。

第二项是主机临时文件IO，未实现或验收生产原子替换、平台断电耐久或Android/Apple owner接管。普通正文测试的内存移交也不是磁盘耐久回执。Thread/FutureTask仅用于测试消费者，不是新App后台worker。

以下均未由本次验证：原owner/线程突然消失且尚未导出checkpoint、未完成输入的自动恢复、实时迁移可变会话、并发写者、UI/Store/worker/native资源pool跨线程迁移、Native/Swift内存模型、普通GUI、物理GPU未signal、断电。缺少旧owner退休与宿主责任交接时，不能把这条协作路径当自动接管授权。完整C1、M-1、ENG整项验收和DEV-041生产存储门禁保持。
