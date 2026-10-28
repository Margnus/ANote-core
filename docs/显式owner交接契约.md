# 显式 owner 交接契约（未冻结）

2026-10-27，C1第4项、ENG-04/ENG-14的有限采用决策。复用原PagedPreviewNotebook、不可变PagedPreviewSnapshot及原ownerCheck，不新增会话authority、线程调度器或存储协议。实际证据见[原交接报告](../reports/release-handoff.json)与[原JVM owner消费者](../shared-core/src/jvmTest/kotlin/dev/notecore/document/DocumentOwnerThreadTest.kt)。

## 2026-10-28 补充：文件已可见，owner回调尚未交付

[API32原报告](../reports/android-path-api32-host-tests.json)的`undelivered_completion`与下文已确认回执场景分开记录：原owner在已有1笔3点/版本2上加入第二笔，原Store save准入，断言ledger仍in-flight、回调0，随后在同一owner action等待外部终止。原worker继续写入，主机只在新版本完整文件hash可见且原命令/PID仍活着时force-stop。旧PID10751消失，文件保持；PID10836用原Store重开，2笔5点/版本3/无pending，编码、坐标、压感和时间保持。宿主没有手工写入正文，没有退役旧owner或停止worker。

采用：回调缺失不能等同保存失败。整个旧进程已确认终止后，新进程先完整读取/验证磁盘版本，再建立唯一新authority；不得仅因旧UI没收到成功就重放旧输入/另造对象或补发旧副作用。当前预览没有跨崩溃操作日志，本结果不证明任意opId跨崩溃幂等。旧进程是否终止未知时仍拒绝并行写者。

本次只覆盖完整新文件已经可见但owner结果未知；没有在半写/rename中途选点，也没有worker成功回执或断电证明。阻塞仅在有限测试宿主，不进入产品UI，不是新生产调度器。连同已确认checkpoint的进程链，本有限消费者验证收口；当前Apple、普通GUI、活动arena、Native/GPU长期未signal和物理门禁仍保持待验。

## 2026-10-28 补充：确认写入后整个旧进程终止（API32子范围）

与下文协作退休分开采用：当原完整checkpoint已由原Store worker实际写入并确认，且OS已经确认整个旧进程终止，新进程可从原文件经完整读取/能力/额度验证后，用新Store、新owner和新nonce采用。该路径不迁移旧Notebook/ledger/worker，也不依赖已终止owner再发退休回执。旧进程是否终止未知时，不能依据弱引用、超时或View消失启动并行写者。

[API32原宿主报告](../reports/android-path-api32-host-tests.json)的`confirmed_checkpoint_process_recovery`记录两次force-stop及三个PID：8913持有完成pending，原worker确认信封写入后整体终止；9001读盘后显式提交两次仍只一笔，确认规范替换后整体终止；9085重开保持1笔3点/版本2/无pending及同一规范文件hash。两个就绪点没有releaseActive或stopTestBackend，等待期间强持有原Store/Notebook并在主owner检查active/pending/空ledger。主机在kill前核对就绪PID确实存活、命令尚运行，kill后核对进程不存在和文件字节。新文件由原worker生成，没有手工seed；synthetic公开输入/owner故障仅构造合法pending，不宣称自然UI崩溃。

采用的是已确认写入后的进程死亡恢复，不是进程内另一线程接管；新Store的程序调用不是普通UI自动恢复验收。原文件仍须完整解码，pending不得只取base。新会话历史重新建立，显式重试只提交一次。未确认IO、原子替换窗口、断电、活动arena自动恢复、未知旧进程、Native长期未signal/GC/物理heap与普通GUI仍未验证，完整C1及生产SQLite仍待。

## 可以采用的路径（原协作范围）

1. 原 owner 在自己的线程完成/显式取消活动输入。正文由原保存责任方准入；若有完成但未提交的 pending，必须保留完整 checkpoint，不能用 capture 的正文部分替代。
2. 原 owner 捕获不可变 checkpoint；普通正文按原 retire 契约退休。pending 只在宿主确认同一 checkpoint 的真实 IO 后调用 retireAfterRecovery。引用比较守卫不是磁盘成功证明，回执由宿主负责。
3. 通过明确同步边界移交不可变 checkpoint。原可变 Notebook/authority 不能换线程继续使用；新 owner 提供自己的 ownerCheck 和新 sessionNonce，用原 openRecovery 创建一个新会话，原旧会话已退休。
4. 新会话不会自动提交 pending。用户或原恢复流程显式 retryPendingStroke 后仅提交一次；再次调用不生成第二笔。旧会话及调用者仍持有的不可变 root/checkpoint 不会被修改或自动回收。

新会话的历史/身份账本重新建立，不宣称跨会话保留 undo journal。规范内容、对象身份和已完成真实点保持；新 owner 只能继续自己线程上的操作。需要历史持久化或并发会话的生产协议不由这条预览路径提供。

## 实际验证及明确边界

原DocumentOwnerThreadTest从3项增至5项，完整JVM434项通过。新增两项用实际Thread/FutureTask同步和join确认旧线程终止后再启动新owner，旧Notebook在外线程的snapshot/提交仍抛owner前置条件。第一项在相同不可变root上继续编辑，原checkpoint编码保持。第二项制造原完成pending，拒绝普通退休和错误checkpoint，真实临时文件FileChannel.force(true)并逐字节读回后退休，另一线程读文件、恢复和显式单次提交，源文件不变。

第二项是主机临时文件IO，未实现或验收生产原子替换、平台断电耐久或Android/Apple owner接管。普通正文测试的内存移交也不是磁盘耐久回执。Thread/FutureTask仅用于测试消费者，不是新App后台worker。

以下均未由本次验证：原owner/线程突然消失且尚未导出checkpoint、未完成输入的自动恢复、实时迁移可变会话、并发写者、UI/Store/worker/native资源pool跨线程迁移、Native/Swift内存模型、普通GUI、物理GPU未signal、断电。缺少旧owner退休与宿主责任交接时，不能把这条协作路径当自动接管授权。完整C1、M-1、ENG整项验收和DEV-041生产存储门禁保持。
