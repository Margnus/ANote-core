# ADR-0020：只读旧格式收口与测量优先

2026-10-15；依据蓝图远端e066cfb的12号评估与用户本轮指示调整。Mac锁屏涉及的桌面交互验证正式延后，不再作为独立开发的停点；未验证标记保留。

## 核对与决策

双端原生宿主在2159211已经只使用PagedPreviewNotebook，并无活跃的v1编辑/保存入口。按完整符号匹配，PagedPreviewNotebook不能被算作PreviewNotebook引用。风险实际在共享SDK：v1兼容解码依赖旧可变PreviewNotebook，旧writer/LegacyPreviewSaveSnapshot仍被公开与导出。

删除canvas-core旧PreviewNotebook、Snapshot/Header、shared-core旧保存adapter及v1 writer；仅保留shared-core内部LegacyPreviewReader。reader直接建立不可变DocumentRoot，不创建内存authority；完整迁入只通过PagedPreviewCodec对外开放，后续编辑和保存只走PagedPreviewNotebook/PagedPreviewSnapshot/v2。旧v1文件未编辑不改写的原生IO语义继续保持。

固定v1字节替代测试中即时生成旧文件；旧单页12项测试改为13项实际只读迁入反例（新增累计点上限/第十一笔缺payload的分配前拒绝），保存账本原12项改用唯一分页输入，原9项混合/冲突反例迁到迁入后规范根。已退休的可变实现不移到测试目录继续维护。架构校验使用完整符号扫描源码/测试，拒绝旧栈重新进入。

只读迁入继续限制500笔、每笔20000点、累计200000点、16M字符，行长/字段数在split前限制，点总数在分配前准入。保留对象顺序、颜色/宽度、真实时间压力、1000×1400原坐标；nextObject只验证旧身份合法性，后续分配使用新session身份，不再把旧计数器作为新保存元数据。

## 模块与证据

当前物理依赖仍是shared-core→canvas-core；宿主保留原生输入/绘制/预览IO。Core只含规范模型、几何基础、命令与参考调度/呈现状态，文档、预览会话和只读兼容在shared-core。canvas-session/RenderPlanner/平台适配产物是C1目标职责候选；先M-1/M-2/M-3测量，再决定物理拆分，不为图中方块预建空模块。

旧源码证据冻结到原记录的两个Git提交，不覆盖为另一机器的不同checkout。本机重新验证两棵树HEAD与80条原hash/anchor均匹配；另一机器缺失/失配结果仍按当地报告保留。源码证据匹配不能证明AAR等价，C0改为记录版本/差异并限制兼容承诺。

远端蓝图已修复其校验器，但ANote-core远端2159211尚无评估提及的UTF-8/LF修复。本轮补齐Core工具的显式UTF-8读写、文本LF检验口径、指纹key统一正斜杠及.gitattributes；真实APK/ZIP/NCP、图片、二进制fixture与旧源码证据保持原始字节hash。新增本机合成LF/CRLF、路径与文本IO检查，不宣称已在Windows实际构建。

## 调整后的下一交付

1. 本节点闭环单栈收口与可复现性，不新增预览功能。
2. M-1真机基线：中端Android和触笔平板，记录机型/系统/刷新率、采样/线程/内存和物理延迟测量方法；只有真实设备证据才填写指标。无设备时保持待测，不用模拟器吞吐替代触笔到光子。
3. M-2共同几何PoC：MONOLINE/PRESSURE/HIGHLIGHTER的版本化几何与覆盖规则、黄金样本、双端适配能力；先证明可表达性与成本，再选择平台后端。
4. M-3密集页索引PoC：固定S2笔迹集、uniform grid与R-tree的候选集/更新/查询对比，包含大bbox/倾斜线退化，暴力扫描作正确性oracle。桌面结果与原生/真机结果分开。
5. 按证据修订职责、估算和预算后进行D5/C1评审；通过后再推进shared-data的SQLite事务/资源/恢复与D6生产闭环。

iOS真实触摸、系统选择器、强停重开及真机性能列为延期门禁，保留已有独立API/IO/启动证据。完整PAGE/P0、生产持久化和C1均不因本轮收口而验收。

## DEV-035：依据M-2/M-3与DEV-034的决策修订（2026-10-15）

下表修订既有C1候选，不冻结公共API/格式或生产后端。逐项门禁与App接入执行卡统一在[现行C1清单](../C1未决事项.md)；专题PoC保留实验历史，不能把其“下一步”覆盖最新状态。

| 候选 | 结论 | 依据与适用范围 |
|---|---|---|
| 共同版本化覆盖与同笔union后一次alpha | 采用为语义约束 | DEV-027/028独立解析oracle、双端实际mask/颜色消费；swept-discs-draft-1仍为实验版本，未承诺尾段拟合或AA边缘等价 |
| 逐样本圆盘/整笔路径全页永久缓存 | 淘汰该默认策略 | S2累计约385–480MiB逻辑载荷、S9原默认output-budget；不代表淘汰有界局部Path或平台原生后端 |
| 精确共线/线性压力简化 | 调整后采用 | 只简化派生输入，保留规范样本；非网格微扰无收益，不承诺一般手写压缩率 |
| 全页常驻细分STR树 | 淘汰该默认策略 | DEV-030局部候选点下降同时整页重复读取和节点增长；不因此淘汰STR粗筛 |
| 整笔粗筛→按需分段→有界mask | 调整后采用为后续接入方向 | S2/S9是固定样本探索；增量更新/遮蔽旧条目、catalog重建、目录heap及阈值仍缺证据，生产grid/STR选择不足 |
| 单资源池、native唯一许可、回调外ACK | 采用为责任约束 | DEV-034双端10事件一致，关闭持有49152、最终0；executor账本为同一预留子集，不能重复加计 |
| CPU mask+Bitmap/CGImage替换普通App绘制 | 证据不足，暂不接入 | 单笔tile不是整页合成；Android holder拒绝硬件Canvas；Apple holder拒绝无data的CGContext；当前View没有异步渲染关闭/drain责任接入 |
| 新建另一生产worker/规范Scene或预算池 | 淘汰 | 既有执行器与资源池已覆盖实验需求；接入须复用同一owner和既有调度，不复制内容authority |
| Ink/Metal/GPU后端及物理模块拆分 | 证据不足 | 未做同机后端比较、M-1、GPU fence或heap；当前两层物理依赖保持 |

证据可用性以[审计报告](../../reports/c1-evidence-audit.json)为入口，工具只核对已有记录和保留产物，不替代运行/来源完整性证明。历史时钟与逻辑字节不晋升为真机性能。当前平台回归分别列272 JVM、85 ART、74 Native、16 app-host；测试数量不构成C1出口。

当前不通过C1、不进行SQLite生产替换。可独立推进DEV-036的帧完整性和同owner关闭责任接入，具体条件见C1清单；M-1设备未就绪仍待测。旧UI-001只证明其指定包，不转移到DEV-034恢复包。上一节iOS交互延期描述属于当时状态，UI-001后续已验证的范围以当前状态及其报告为准。

## DEV-036C：宿主owner采用与屏幕接入受阻（2026-10-17记录）

DEV-036A/B已有原作业内完整区域/多tile静态墨迹帧。C把原PreviewModel的AppleBoundedOwnerExecutor注入现有adapter，使用原onOwnerProgress在有限批量drain返回后ACK，关闭render不停止共享存储worker；Model持有唯一adapter/holder/当前展示元数据。CPU UIKit宿主四项新增用例已验证像素/方向、页面/视口/尺寸回退、View销毁后迟到ACK、后台/恢复、共享存储仍可重开，以及UNKNOWN holder不重试破坏性释放、只读协调后归零。共有22项宿主；它不能替代普通包目标证据。

最终普通模拟器包SHA `1389b318cc3d1f0eed623ef1855df5e3a070b6acf541978b29df47334c4f19d6` 的实际屏幕观察：PID7830于日志时间2026-10-16 20:26:38+0800记录 `NOTECORE_RENDER target-rejected non-bitmap`；该普通进程没有frame-presented事件。DEBUG配置最初缺少DEBUG编译条件，已修正并在一致配置重跑22项；事件按普通包PID筛选，排除测试宿主的成功呈现。事件证明context.data为nil、不满足已验证的CPU consumer契约，**不证明具体GPU后端类型或fence状态**。见[分范围证据](../../reports/ios-render-integration.json)。

决策：**采用原宿主owner/共享执行器/回调外ACK接缝；调整呈现接入方案；淘汰“bitmap宿主通过即可直接切普通屏幕”的推断，普通屏幕后端选择仍证据不足。** 保留CPU能力检查和完整Path回退，不关闭硬件加速或删除检查来伪造通过；不再以旁路单笔PoC、更多测试数量或同一次受控trace替代实际目标验收。现有Model接缝保持未冻结，普通iOS屏幕及Android普通App未切新后端。

实际新包回退回归完成：重开第一页2笔→加第二页并实写1笔→切回第一/二页、缩放/适合页面→Home后台/返回→从列表关闭再文件重开，最后2页3笔/逐页2、1。原5份文件hash未变。最初创建/命名/首两笔在DEBUG条件修正前的包上完成，仅作为夹具准备，不转移为最终包创建流程验收。未重复系统导入导出或显式强停测试，也不继承旧UI-001。

下一步必须先证明实际呈现目标的图像读取、复制/保留、释放确认和owner责任边界；若涉及GPU在途读者则补相应生命周期证据后再接入。M-1、heap/GPU、完全owner丢失/跨线程接管、任意变换、完整C1及SQLite恢复继续待验。DEV-036整体未验收，本目标按用户授权的“实际接入受阻时交付已验证子范围/具体阻挡”分支交付，不能据此将产品后端记为完成。

## DEV-036D：采用provider源释放回执的有界UIKit接入（2026-10-17）

C的实际屏幕拒绝促使修改源图像持有契约，未删除原CPU门禁。Apple SDK `CGDataProvider.h` 与[官方API](https://developer.apple.com/documentation/coregraphics/cgdataprovider/init(datainfo:data:size:releasedata:))规定provider释放时调用releaseData。采用独立不可变RGBA直接创建CGImage/provider：App在原native allowance内复制一次，provider回调才释放源字节；锁同步回执，回到主owner归还reader许可。原CPU holder的bitmap draw保留，新增UIKit入口只允许具备tracked source的holder。CG内部目标副本由CG持有，App没有提交或提前回收自己的GPU buffer；源回调不是GPU fence或隐藏heap回收证明。

真实app-host25项含3项provider责任反例：保留实际CGDataProvider时close仍计费且禁止画旧帧；在其他线程释放后仅owner结算；UNKNOWN在真实回执前不能协调；Model关闭/新文档期间保持旧池责任，回执后恢复新帧。原Model测试另比对半透明新对象提交时的完整Path和ACK后的完整帧像素。原共享executor、唯一资源池、native许可及有限drain外ACK不变，无旁路队列/Scene。

最终普通包f31799b9…/PID5747有8次bitmap=0的provider帧消费、真实源释放与owner结算，最终owner-drained charge为0。CUA在最终包重新新建/命名、两页三笔、切页/缩放/后台/文件重开，源图像可见；外部输入后最终两页10笔/逐页9、1，原6份文件hash保持。不声称后来9笔页全部由新后端消费；工作/预算拒绝继续整页规范Path。具体版本、事件、输入归属和包恢复见[原iOS分范围报告](../../reports/ios-render-integration.json)，不继承旧UI-001或早期D候选包验收。

决策：**采用provider回执方案作为未冻结的有界MONOLINE UIKit接入；保留完整失败回退。** 仅iOS最小接入范围通过，不解除Android hardware Canvas检查，不冻结GPU后端/物理模块，不放行C1/SQLite。下一步先明确Android真实目标读者与释放确认；M-1物理触笔/帧/heap、GPU fence/隐藏分配、任意变换、owner完全丢失/跨线程移交继续待验。

## DEV-036E：Android回收责任未满足，保留硬件门禁（2026-10-17）

采用实际目标/安全拒绝证据，**不采用直接硬件Canvas drawBitmap后recycle的接入**。app-host在原InkCanvasView的真实onDraw读到hardware=true、layer默认；ART新增真实RecordingCanvas反例证实原holder在drawBitmap前拒绝，拒绝仍计费、关闭且无外逸后归零。本次只有诊断及验证代码，没有删除保护或新增第二规范模型/执行器/资源池。APK d6e2642e…保留64份原文件并恢复普通进程；CUA未提供Android窗口，app-host不能替代普通包UI或新后端消费，报告准确记为接入受阻。

[Android官方Bitmap](https://developer.android.com/reference/android/graphics/Bitmap#recycle())建议避免回收曾被View使用或还在其他引用中的图像；isRecycled是回收后的Java可用性状态，不证明硬件读者预先结束。[frame commit](https://developer.android.com/reference/android/view/ViewTreeObserver#registerFrameCommitCallback(java.lang.Runnable))仅说明帧已提交交换链；reconfigure的失效/后续draw例子不补齐detach后无draw的任意View读者责任。淘汰无证明的post/invalidate/GC/引用置空/超时释放捷径。int[] drawBitmap的内部复制也不能直接当作既有native许可的有界替代：每次绘制的内部copy驻留/回收仍缺计费与生命周期证据。此结论不是否定所有经充分证明的View组合方案。

调整下一候选调查为API33+ [SurfaceControl.Transaction.setBuffer](https://developer.android.com/reference/android/view/SurfaceControl.Transaction)显式releaseCallback/SyncFence边界。尚未采用SurfaceControl作为后端：CPU RGBA到HardwareBuffer的有界上传和生产方完成、原App层级中静态/动态/纸张/裁剪的z序及原子替换、原NoteStore共享executor/drain外ACK与后台/关闭责任均未实现。callback带有效fence时还须确认signal，不将callback触发或Transaction.apply返回当完成；原UI/共享存储worker不做阻塞等待。具体文件级出口复用[C1清单](../C1未决事项.md)，不另建计划authority，不开发更多独立Surface展示来替代普通App集成。

原API26–32完整Path及minSdk26保持，当前CPU holder/UNKNOWN责任契约保持；此节点未新增GPU缓冲区生命周期证明。证据/源码/SDK契约hash见[Android原报告](../../reports/android-render-integration.json)，本机SDK源码hash只绑定所查契约，不证明模拟器系统二进制与该source逐字等价。iOS DEV-036D的provider接入仍按原包/报告有效，Android不继承其结论；M-1/heap/GPU后端比较/任意变换/完全owner丢失/C1/SQLite仍待门禁。

## DEV-036F：有界SurfaceControl候选接入原Android宿主（2026-10-17）

调整E的“尚未采用”为**采用受限的API33+原App接入候选，宿主呈现与释放责任通过，普通UI待验**。原MainActivity使用SurfaceView下层child，InkCanvasView保留上层active输入及完整Path；完整静态帧包含纸张与裁剪，经ImageWriter/Reader上传。只有原ViewRoot applyTransactionOnDraw成功安排完整buffer时才撤去Path，等待/失败/拒绝走完整Path；API26–32不改minSdk而保持原路径。

复用NoteStore的唯一BoundedOwnerExecutor/Handler、原render owner/pool与native许可，在有限drain返回后ACK；关闭render后存储继续工作。上传核对格式/stride并覆盖CPU墨迹、composite、staging与plane的原native allowance。入站fence随事务交付；出站真实releaseCallback的有效SyncFence非阻塞确认完成后，才关闭原生handle并归还CPU source许可。UNKNOWN/无回执/未signal继续保留计费，不以View消失/一次draw/GC/超时替代完成。完全不明交付或清理仍隔离，尚无恢复实证。

真实API36 app-host经原层级的compositor截图对照，证明半透明/静态动态顺序、页面/缩放/尺寸替换、等待完整回退；保存后由新NoteStore重开2页3笔，关闭迟到ACK、真实有效release fence与原生确认丢失后只读协调通过。最终APK 2cfe9f5c…及64原文件保持绑定，报告见[既有Android分范围证据](../../reports/android-render-integration.json)。普通包代码已连接，但CUA未能识别模拟器app，不能把宿主成功或恢复普通PID当作普通UI后端验收。

保留原CPU holder硬件门禁。此决策不冻结生产GPU后端；系统隐藏分配、物理性能、长时未signal/歧义交付、API26–32及厂商运行矩阵、完全owner丢失与跨线程接管仍待验。下一步先完成最终普通包书写/切页/缩放/导航/后台/文件重开与普通PID呈现释放证据；iOS D保持原包范围，跨端DEV-036、M-1/C1/SQLite仍未完成。

## DEV-036G：采用同buffer真实回执的交付歧义恢复（2026-10-17）

F的统一UNKNOWN会永久阻止交付异常后的迟到回执结算。调整为交付歧义与破坏性清理UNKNOWN两类责任：在setBuffer前建立可能交付持有；真实releaseCallback及有效SyncFence实际完成后，可消除交付歧义，随后才进行原生句柄关闭和原CPU许可归还。清理/fence检查异常保持隔离，CPU回收确认丢失仍仅在既有事实下显式只读协调；重复close不重新交付或破坏性重试。采用此修复，原资源/调度/内容权威不变。

真实原Android层级在实际ViewRoot交付成功后注入确认丢失；callback后软件延迟结算时，旧48,906,240逻辑字节/许可保持，新文档完整Path不发布新池，原存储worker仍能保存。gate放行不能代替真实fence检查；完成后旧owner归零并恢复新帧。原清理UNKNOWN反例保持，完整帧/输入叠加/页面缩放尺寸/迟到ACK/保存新owner重开回归通过。新APK6a417b5d…及原报告[分范围证据](../../reports/android-render-integration.json)绑定该实现。

此注入不证明真实长期未signal或厂商异常率；全部交付/清理失败矩阵与普通包CUA UI仍待验。G前后64文件保持；AVD恢复后、G安装/测试前观察到相对F历史1份共同文件hash变化、原因未核，未覆盖现有文件；历史差异和本轮保留分开。M-1/heap/API26–32/完全owner丢失/任意变换/C1/SQLite未放行。

## DEV-038：SQLite恢复能力调查与持久化候选边界（2026-10-17）

采用系统SQLite事务、唯一op身份及事务内base修订核对作为生产port候选；生产实施仍在C1之后。真实Android ART/API36（SQLite3.44.3）与iOS Simulator（3.54.0）隔离进程各20阶段证明合成根/操作原子更新、未提交SIGKILL回滚、提交丢回执/回执后SIGKILL重开、同op重放与payload/base冲突拒绝、事务/DDL版本回滚、受限页数FULL及非破坏性只读未知/损坏拒绝。报告与具体范围见[既有C1入口](../C1未决事项.md)和[能力证据](../../reports/sqlite-platform-capabilities.json)。

明确完整操作身份与根写入必须在同一事务；durable receipt在提交成功之后，模糊结果按原op协调。新版本/损坏不自动重建删除，生产port配置显式WAL/同步级别并记录能力。probe只是隔离可执行证据，不是shared-data或App存储接入；并发writer、实际磁盘IO/断电/fsync/设备、资源事务/GC/checkpoint及生产schema迁移仍未证明。保留原预览文件、同owner/有界executor和C1顺序，不因SQLite局部通过而冻结架构或标DATA整项完成。
