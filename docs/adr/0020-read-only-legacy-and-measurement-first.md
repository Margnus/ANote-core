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
